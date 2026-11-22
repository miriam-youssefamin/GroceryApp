package com.example.groceryapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.example.groceryapp.databinding.ActivityVoipBinding
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors

/**
 * Voice over IP call between any two users (buyer ↔ seller, buyer ↔ buyer, …) using real
 * signaling and media protocols, so the whole call can be captured and analysed in Wireshark.
 *
 * SIGNALING — SIP over UDP (default port 5060), see [SipMessage]:
 *
 *     Caller (A)                         Callee (B)
 *        |------ INVITE (SDP offer) ------->|   A: "call me, send audio to my-ip:rtp-port, PCMU"
 *        |<----- 100 Trying ----------------|
 *        |<----- 180 Ringing ---------------|   B shows Answer / Decline
 *        |<----- 200 OK (SDP answer) -------|   B answered: "send audio to b-ip:rtp-port"
 *        |------ ACK ---------------------->|
 *        |<===== RTP voice (both ways) ====>|   MEDIA — RTP, G.711 µ-law, 20 ms per packet
 *        |------ BYE ---------------------->|   either side hangs up
 *        |<----- 200 OK --------------------|
 *
 *  Other endings: Decline → 603 Decline, busy → 486 Busy Here,
 *  caller gives up → CANCEL / 200 OK / 487 Request Terminated / ACK.
 *
 * MEDIA — RTP over UDP, see [RtpPacket] and [G711]: each phone records the microphone at 8 kHz,
 * compresses 20 ms (160 samples) to 160 µ-law bytes, adds a 12-byte RTP header and sends it to the
 * address the other side announced in its SDP. Received packets are decoded and played.
 *
 * The user types the other phone's IP address and SIP port plus their own SIP port
 * (Milestone 4: "manually enter the IP address and port number"). This screen must be open on
 * both phones: it listens for incoming INVITEs on "My SIP port" while it is visible.
 *
 * Threads: the UI/state machine runs on the main thread; one thread receives SIP, a single-thread
 * executor sends SIP and opens sockets (no network on the main thread), and during a call one
 * thread sends RTP and one receives it.
 */
class VoipActivity : AppCompatActivity() {

    /** Where the call is in the SIP flow above. */
    private enum class State { IDLE, CALLING, CANCELLING, INCOMING, IN_CALL }

    /**
     * Everything about the current call (a SIP "dialog").
     *
     * @property peer        Where SIP messages for the other phone are sent.
     * @property fromHeader  Our side, with our tag (used as From in requests we send).
     * @property remoteParty The other side as it appears in To (their tag is added once known).
     */
    private class Call(
        val callId: String,
        val localTag: String,
        val isCaller: Boolean,
        val peer: InetSocketAddress,
        val localIp: String,
        val localUri: String,
        var remoteUri: String,
        val fromHeader: String,
        val remoteParty: String
    ) {
        var remoteTag: String? = null
        var localCseq = 1
        var invite: SipMessage? = null          // the INVITE we sent (caller) or received (callee)
        var inviteBranch = ""                    // Via branch of our INVITE (reused by CANCEL / error ACK)
        var remoteRtp: InetSocketAddress? = null // where to send our RTP voice (from their SDP)

        /** To header: the other side, with their tag once we know it. */
        fun toHeader(): String =
            if (remoteTag == null || SipMessage.tagOf(remoteParty) != null) remoteParty
            else "$remoteParty;tag=$remoteTag"
    }

    private lateinit var binding: ActivityVoipBinding
    private val prefs by lazy { getSharedPreferences("voip", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    /** Sends SIP and opens sockets in order, off the main thread. */
    private val executor = Executors.newSingleThreadExecutor()

    private var state = State.IDLE
    private var currentCall: Call? = null
    private var displayName = "GroceryApp user"

    /** SIP socket, bound to "My SIP port" while the screen is open. */
    @Volatile private var sipSocket: DatagramSocket? = null
    @Volatile private var boundPort = -1

    /** RTP socket of the current call (its port is announced in our SDP). */
    @Volatile private var rtpSocket: DatagramSocket? = null
    @Volatile private var mediaRunning = false
    @Volatile private var rtpSent = 0
    @Volatile private var rtpReceived = 0
    private var callStartedAt = 0L

    private val logLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoipBinding.inflate(layoutInflater)
        setContentView(binding.root)

        listOf(binding.tilRemoteIp, binding.tilRemotePort, binding.tilLocalPort).forEach { it.clearErrorOnType() }

        // Pre-fill the fields with the last values used (or the standard SIP port).
        binding.etRemoteIp.setText(prefs.getString(KEY_REMOTE_IP, ""))
        binding.etRemotePort.setText(prefs.getInt(KEY_REMOTE_PORT, VoipConfig.DEFAULT_SIP_PORT).toString())
        val localPort = prefs.getInt(KEY_LOCAL_PORT, VoipConfig.DEFAULT_SIP_PORT)
        binding.etLocalPort.setText(localPort.toString())

        // Our name appears in the SIP From header (visible in Wireshark).
        fetchUserName { name -> displayName = name.replace("\"", "") }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnVoip.setOnClickListener {
            when (state) {
                State.IDLE -> placeCall()
                State.CALLING -> cancelCall("Call cancelled")
                State.IN_CALL -> hangUp()
                else -> Unit
            }
        }
        binding.btnAnswer.setOnClickListener { answer() }
        binding.btnDecline.setOnClickListener { decline() }

        // Changing "My SIP port" while idle re-opens the listening socket on the new port.
        binding.etLocalPort.doAfterTextChanged {
            handler.removeCallbacks(rebindRunnable)
            handler.postDelayed(rebindRunnable, 700)
        }

        // Ask for the microphone up front, so answering a call isn't interrupted by the dialog.
        if (!hasMic()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)

        updateMyAddress()
        setStatus("Not in a call")
        showState()
        bindSip(localPort)
    }

    // ==========================================================================================
    // SIP socket
    // ==========================================================================================

    /** Rebinds the SIP socket when "My SIP port" was changed to a valid new value while idle. */
    private val rebindRunnable = Runnable {
        val port = parsePort(binding.etLocalPort.text?.toString())
        if (state == State.IDLE && port != null && port != boundPort) bindSip(port)
    }

    /** (Re)opens the SIP socket on [port] and starts the receiving thread; [then] runs once it's ready. */
    private fun bindSip(port: Int, then: (() -> Unit)? = null) {
        executor.execute {
            try {
                sipSocket?.close()
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                sipSocket = socket
                boundPort = port
                Thread({ sipReceiveLoop(socket) }, "sip-rx").start()
                ui {
                    log("Listening for SIP on UDP $port")
                    updateMyAddress()
                    then?.invoke()
                }
            } catch (e: Exception) {
                boundPort = -1
                ui {
                    binding.tilLocalPort.error = "Port $port is busy"
                    log("Couldn't open UDP $port: ${e.message}")
                }
            }
        }
    }

    /** Receives SIP messages until the socket is closed and hands them to the main thread. */
    private fun sipReceiveLoop(socket: DatagramSocket) {
        val buffer = ByteArray(8192)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: Exception) {
                break // socket closed (rebind or screen closed)
            }
            val message = SipMessage.parse(packet.data, packet.length) ?: continue // not SIP: ignore
            val from = InetSocketAddress(packet.address, packet.port)
            ui { onSip(message, from) }
        }
    }

    /** Sends a SIP message to [to] (on the executor) and logs it as "→ ...". */
    private fun sendSip(message: SipMessage, to: InetSocketAddress) {
        log("→ ${message.startLine.toShortLine()}")
        val bytes = message.encode()
        executor.execute {
            try {
                sipSocket?.send(DatagramPacket(bytes, bytes.size, to))
            } catch (e: Exception) {
                ui { log("Send failed: ${e.message}") }
            }
        }
    }

    /** Builds a request inside [call] (INVITE, ACK, BYE) with the standard RFC 3261 headers. */
    private fun buildRequest(
        call: Call,
        method: String,
        cseq: Int,
        branch: String = SipMessage.newBranch(),
        body: String = ""
    ): SipMessage {
        val headers = mutableListOf(
            "Via" to "SIP/2.0/UDP ${call.localIp}:$boundPort;branch=$branch;rport",
            "Max-Forwards" to "70",
            "From" to call.fromHeader,
            "To" to call.toHeader(),
            "Call-ID" to call.callId,
            "CSeq" to "$cseq $method",
            "Contact" to "<${call.localUri}>",
            "User-Agent" to USER_AGENT
        )
        if (body.isNotEmpty()) headers += "Content-Type" to "application/sdp"
        return SipMessage("$method ${call.remoteUri} SIP/2.0", headers, body)
    }

    // ==========================================================================================
    // Outgoing call (caller side)
    // ==========================================================================================

    /** Validates the fields, then sends the INVITE (rebinding first if "My SIP port" changed). */
    private fun placeCall() {
        val ip = binding.etRemoteIp.text?.toString()?.trim().orEmpty()
        val remotePort = parsePort(binding.etRemotePort.text?.toString())
        val localPort = parsePort(binding.etLocalPort.text?.toString())
        if (!isValidIpv4(ip)) {
            binding.tilRemoteIp.error = "Enter a valid IPv4 address, e.g. 192.168.1.20"; return
        }
        if (remotePort == null) {
            binding.tilRemotePort.error = "${VoipConfig.MIN_PORT}–${VoipConfig.MAX_PORT}"; return
        }
        if (localPort == null) {
            binding.tilLocalPort.error = "${VoipConfig.MIN_PORT}–${VoipConfig.MAX_PORT}"; return
        }
        prefs.edit()
            .putString(KEY_REMOTE_IP, ip)
            .putInt(KEY_REMOTE_PORT, remotePort)
            .putInt(KEY_LOCAL_PORT, localPort)
            .apply()

        if (!hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC); return
        }
        if (localPort != boundPort) bindSip(localPort) { startOutgoing(ip, remotePort) }
        else startOutgoing(ip, remotePort)
    }

    /** Opens the RTP socket, builds the INVITE with our SDP offer and sends it. */
    private fun startOutgoing(ip: String, remotePort: Int) {
        state = State.CALLING
        setStatus("Calling $ip…")
        showState()
        executor.execute {
            try {
                val peer = InetSocketAddress(InetAddress.getByName(ip), remotePort)
                val localIp = localIpFor(peer.address)
                val rtp = openRtpSocket()
                val tag = SipMessage.token()
                val call = Call(
                    callId = SipMessage.token(16) + "@" + localIp,
                    localTag = tag,
                    isCaller = true,
                    peer = peer,
                    localIp = localIp,
                    localUri = "sip:$SIP_USER@$localIp:$boundPort",
                    remoteUri = "sip:$SIP_USER@$ip:$remotePort",
                    fromHeader = "\"$displayName\" <sip:$SIP_USER@$localIp:$boundPort>;tag=$tag",
                    remoteParty = "<sip:$SIP_USER@$ip:$remotePort>"
                )
                call.inviteBranch = SipMessage.newBranch()
                val sdp = Sdp.describe(localIp, rtp.localPort, System.currentTimeMillis() / 1000)
                val invite = buildRequest(call, "INVITE", call.localCseq, call.inviteBranch, sdp)
                call.invite = invite
                ui {
                    if (state != State.CALLING) { rtp.close(); return@ui } // cancelled meanwhile
                    currentCall = call
                    rtpSocket = rtp
                    sendSip(invite, peer)
                    handler.postDelayed(inviteTimeout, VoipConfig.INVITE_TIMEOUT_MS)
                }
            } catch (e: Exception) {
                ui { endCall("Couldn't start the call: ${e.message}") }
            }
        }
    }

    /** No final answer to our INVITE in time: cancel it. */
    private val inviteTimeout = Runnable { if (state == State.CALLING) cancelCall("No answer") }

    /** Caller gives up before the other side answers: CANCEL (same branch and CSeq number as the INVITE). */
    private fun cancelCall(reason: String) {
        val call = currentCall
        val invite = call?.invite
        if (call == null || invite == null) { endCall(reason); return }
        handler.removeCallbacks(inviteTimeout)
        state = State.CANCELLING
        setStatus("Cancelling…")
        showState()
        val cancel = SipMessage(
            "CANCEL ${call.remoteUri} SIP/2.0",
            listOf(
                "Via" to invite.header("Via").orEmpty(),
                "Max-Forwards" to "70",
                "From" to invite.header("From").orEmpty(),
                "To" to invite.header("To").orEmpty(),
                "Call-ID" to call.callId,
                "CSeq" to "${call.localCseq} CANCEL",
                "User-Agent" to USER_AGENT
            )
        )
        sendSip(cancel, call.peer)
        // The callee answers 487 for the INVITE (we ACK it); stop waiting after a few seconds anyway.
        handler.postDelayed({ if (state == State.CANCELLING) endCall(reason) }, 4000)
    }

    // ==========================================================================================
    // Incoming call (callee side)
    // ==========================================================================================

    /** An INVITE arrived while idle: reply 100 Trying + 180 Ringing and show Answer / Decline. */
    private fun onIncomingInvite(invite: SipMessage, from: InetSocketAddress) {
        state = State.INCOMING
        showState()
        executor.execute {
            val localIp = localIpFor(from.address)
            ui {
                if (state != State.INCOMING) return@ui
                val tag = SipMessage.token()
                val to = invite.header("To").orEmpty()
                val contact = invite.header("Contact")
                val call = Call(
                    callId = invite.callId,
                    localTag = tag,
                    isCaller = false,
                    peer = from,
                    localIp = localIp,
                    localUri = "sip:$SIP_USER@$localIp:$boundPort",
                    remoteUri = contact?.let { uriOf(it) } ?: "sip:$SIP_USER@${from.address.hostAddress}:${from.port}",
                    fromHeader = if (SipMessage.tagOf(to) == null) "$to;tag=$tag" else to,
                    remoteParty = invite.header("From").orEmpty()
                )
                call.invite = invite
                call.remoteRtp = Sdp.parseAudioTarget(invite.body)?.let { (ip, port) ->
                    InetSocketAddress(if (isValidIpv4(ip) && ip != "0.0.0.0") InetAddress.getByName(ip) else from.address, port)
                }
                currentCall = call
                sendSip(SipMessage.response(invite, 100, "Trying", null), from)
                sendSip(
                    SipMessage.response(invite, 180, "Ringing", tag, listOf("Contact" to "<${call.localUri}>")),
                    from
                )
                val caller = Regex("\"([^\"]*)\"").find(call.remoteParty)?.groupValues?.get(1) ?: "Someone"
                setStatus("📞 Incoming call from $caller (${from.address.hostAddress})")
                showState()
            }
        }
    }

    /** Callee answers: 200 OK with our SDP answer, then start sending/receiving RTP. */
    private fun answer() {
        val call = currentCall ?: return
        if (state != State.INCOMING) return
        if (!hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            log("Allow the microphone, then tap Answer again")
            return
        }
        val invite = call.invite ?: return
        val remoteRtp = call.remoteRtp
        if (remoteRtp == null) { // the INVITE had no usable SDP
            sendSip(SipMessage.response(invite, 488, "Not Acceptable Here", call.localTag), call.peer)
            endCall("The caller didn't send audio details")
            return
        }
        executor.execute {
            val rtp = try { openRtpSocket() } catch (e: Exception) { null }
            ui {
                if (currentCall !== call || state != State.INCOMING) { rtp?.close(); return@ui }
                if (rtp == null) { decline(); return@ui }
                rtpSocket = rtp
                val sdp = Sdp.describe(call.localIp, rtp.localPort, System.currentTimeMillis() / 1000)
                sendSip(
                    SipMessage.response(
                        invite, 200, "OK", call.localTag,
                        listOf("Contact" to "<${call.localUri}>", "User-Agent" to USER_AGENT, "Content-Type" to "application/sdp"),
                        sdp
                    ),
                    call.peer
                )
                startMedia(rtp, remoteRtp)
            }
        }
    }

    /** Callee refuses: 603 Decline. */
    private fun decline() {
        val call = currentCall ?: return
        call.invite?.let { sendSip(SipMessage.response(it, 603, "Decline", call.localTag), call.peer) }
        endCall("Call declined")
    }

    // ==========================================================================================
    // Handling received SIP messages (main thread)
    // ==========================================================================================

    /** Entry point for every received SIP message. */
    private fun onSip(message: SipMessage, from: InetSocketAddress) {
        log("← ${message.startLine.toShortLine()}")
        if (message.isResponse) onResponse(message) else onRequest(message, from)
    }

    /** Requests from the other phone: INVITE, ACK, BYE, CANCEL (and OPTIONS pings). */
    private fun onRequest(request: SipMessage, from: InetSocketAddress) {
        val call = currentCall
        val sameCall = call != null && call.callId == request.callId
        when (request.method) {
            "INVITE" -> when {
                sameCall -> Unit // retransmission of the INVITE we're already handling
                state != State.IDLE ->
                    sendSip(SipMessage.response(request, 486, "Busy Here", SipMessage.token()), from)
                else -> onIncomingInvite(request, from)
            }
            "ACK" -> Unit // confirms our 200 OK (or our error response); already logged
            "BYE" -> {
                sendSip(SipMessage.response(request, 200, "OK", null), from)
                if (sameCall) endCall("The other side hung up")
            }
            "CANCEL" -> {
                sendSip(SipMessage.response(request, 200, "OK", call?.localTag), from)
                if (sameCall && state == State.INCOMING) {
                    call!!.invite?.let { sendSip(SipMessage.response(it, 487, "Request Terminated", call.localTag), from) }
                    endCall("The caller cancelled")
                }
            }
            "OPTIONS" -> sendSip(SipMessage.response(request, 200, "OK", SipMessage.token(), listOf("Allow" to ALLOW)), from)
            else -> sendSip(SipMessage.response(request, 501, "Not Implemented", SipMessage.token()), from)
        }
    }

    /** Responses to our requests. Only responses to our INVITE change the call state. */
    private fun onResponse(response: SipMessage) {
        val call = currentCall ?: return
        if (response.callId != call.callId || response.method != "INVITE") return // e.g. 200 OK to BYE/CANCEL
        val code = response.statusCode
        SipMessage.tagOf(response.header("To"))?.let { call.remoteTag = it }
        when {
            code < 200 -> if (code == 180 || code == 183) setStatus("Ringing…") // provisional

            code < 300 -> { // 200 OK: the callee answered
                // ACK for a 2xx is its own transaction (new branch), same CSeq number as the INVITE.
                sendSip(buildRequest(call, "ACK", call.localCseq), call.peer)
                if (state == State.IN_CALL) return // a repeated 200 OK: the ACK above is enough
                handler.removeCallbacks(inviteTimeout)
                if (state == State.CANCELLING) { hangUp(); return } // the answer crossed our CANCEL
                response.header("Contact")?.let { call.remoteUri = uriOf(it) }
                val target = Sdp.parseAudioTarget(response.body)
                val rtp = rtpSocket
                if (target == null || rtp == null) {
                    hangUp(); setStatus("The other phone sent no audio details"); return
                }
                val ip = if (isValidIpv4(target.first) && target.first != "0.0.0.0")
                    InetAddress.getByName(target.first) else call.peer.address
                startMedia(rtp, InetSocketAddress(ip, target.second))
            }

            else -> { // 3xx–6xx: the call failed / was refused
                handler.removeCallbacks(inviteTimeout)
                // ACK for a failure reuses the INVITE's branch (RFC 3261 §17.1.1.3).
                sendSip(buildRequest(call, "ACK", call.localCseq, call.inviteBranch), call.peer)
                endCall(
                    when (code) {
                        486 -> "The other phone is busy"
                        487 -> "Call cancelled"
                        603 -> "Call declined"
                        else -> "Call failed (${response.startLine.removePrefix("SIP/2.0 ")})"
                    }
                )
            }
        }
    }

    /** Either side ends an established call: send BYE (new CSeq) and stop the audio. */
    private fun hangUp() {
        val call = currentCall ?: return
        call.localCseq++
        sendSip(buildRequest(call, "BYE", call.localCseq), call.peer)
        endCall("Call ended")
    }

    /** Back to idle: stop audio, forget the call, show [reason] and the RTP statistics. */
    private fun endCall(reason: String) {
        val wasInCall = state == State.IN_CALL
        stopMedia()
        handler.removeCallbacks(inviteTimeout)
        currentCall = null
        state = State.IDLE
        if (wasInCall) log("RTP: sent $rtpSent, received $rtpReceived packets")
        setStatus(reason)
        showState()
    }

    // ==========================================================================================
    // RTP media
    // ==========================================================================================

    /** Opens the local RTP socket (preferred port, or any free port if it's busy). */
    private fun openRtpSocket(): DatagramSocket =
        try { DatagramSocket(VoipConfig.PREFERRED_RTP_PORT) } catch (e: Exception) { DatagramSocket(0) }

    /** Starts the two RTP threads: microphone → [remote], and incoming packets → speaker. */
    private fun startMedia(socket: DatagramSocket, remote: InetSocketAddress) {
        state = State.IN_CALL
        mediaRunning = true
        rtpSent = 0
        rtpReceived = 0
        callStartedAt = SystemClock.elapsedRealtime()
        log("RTP: port ${socket.localPort} ⇄ ${remote.address.hostAddress}:${remote.port} (PCMU 8 kHz)")
        Thread({ rtpSendLoop(socket, remote) }, "rtp-tx").start()
        Thread({ rtpReceiveLoop(socket) }, "rtp-rx").start()
        handler.post(callTicker)
        showState()
    }

    /** Stops the RTP threads and frees the RTP port. */
    private fun stopMedia() {
        mediaRunning = false
        rtpSocket?.close() // also unblocks receive() in the receiver thread
        rtpSocket = null
        handler.removeCallbacks(callTicker)
    }

    /** Updates "In call · 01:23 · RTP sent/received" once a second. */
    private val callTicker = object : Runnable {
        override fun run() {
            if (state != State.IN_CALL) return
            val seconds = (SystemClock.elapsedRealtime() - callStartedAt) / 1000
            setStatus("In call · %02d:%02d\nRTP sent $rtpSent · received $rtpReceived".format(seconds / 60, seconds % 60))
            handler.postDelayed(this, 1000)
        }
    }

    /** Sender: 20 ms of microphone audio → G.711 µ-law → RTP packet → UDP. */
    @Suppress("MissingPermission") // checked before every call/answer
    private fun rtpSendLoop(socket: DatagramSocket, remote: InetSocketAddress) {
        val minBuf = AudioRecord.getMinBufferSize(
            VoipConfig.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) { ui { if (state == State.IN_CALL) { hangUp(); setStatus("This phone can't record at 8 kHz") } }; return }
        // VOICE_COMMUNICATION enables echo cancellation / noise suppression where the phone supports it.
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, VoipConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, VoipConfig.SAMPLES_PER_PACKET * 2 * 4)
        )
        val rtp = RtpPacket.Sender(VoipConfig.RTP_PAYLOAD_PCMU, VoipConfig.SAMPLES_PER_PACKET)
        try {
            val pcm = ShortArray(VoipConfig.SAMPLES_PER_PACKET)
            recorder.startRecording()
            while (mediaRunning) {
                val read = recorder.read(pcm, 0, pcm.size)
                if (read <= 0) continue
                val packet = rtp.next(G711.encode(pcm, read))
                socket.send(DatagramPacket(packet, packet.size, remote))
                rtpSent++
            }
        } catch (e: Exception) {
            if (mediaRunning) ui { if (state == State.IN_CALL) { hangUp(); setStatus("Audio error: ${e.message}") } }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    /** Receiver: UDP → RTP header check → G.711 decode → speaker. */
    private fun rtpReceiveLoop(socket: DatagramSocket) {
        val minBuf = AudioTrack.getMinBufferSize(
            VoipConfig.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA) // loudspeaker: easier for a demo than the earpiece
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(VoipConfig.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, VoipConfig.SAMPLES_PER_PACKET * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)
            track.play()
            while (mediaRunning) {
                packet.length = buffer.size                 // reset before each receive
                socket.receive(packet)                      // blocks until a packet arrives / socket closes
                val rtp = RtpPacket.parse(packet.data, packet.length) ?: continue
                if (rtp.payloadType != VoipConfig.RTP_PAYLOAD_PCMU) continue
                val pcm = G711.decode(packet.data, rtp.payloadOffset, rtp.payloadLength)
                track.write(pcm, 0, pcm.size)
                rtpReceived++
            }
        } catch (e: Exception) {
            // socket closed by stopMedia(): normal end of the call
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    // ==========================================================================================
    // UI helpers
    // ==========================================================================================

    /** Shows the right buttons for the current [state] and locks the fields during a call. */
    private fun showState() {
        val idle = state == State.IDLE
        binding.btnVoip.visibility = if (state == State.INCOMING) View.GONE else View.VISIBLE
        binding.incomingRow.visibility = if (state == State.INCOMING) View.VISIBLE else View.GONE
        binding.btnVoip.isEnabled = state != State.CANCELLING
        binding.btnVoip.text = when (state) {
            State.IDLE -> "Call"
            State.CALLING -> "Cancel"
            State.CANCELLING -> "Cancelling…"
            State.IN_CALL -> "Hang up"
            State.INCOMING -> ""
        }
        listOf(binding.etRemoteIp, binding.etRemotePort, binding.etLocalPort).forEach { it.isEnabled = idle }
    }

    private fun setStatus(text: String) {
        binding.tvVoipStatus.text = text
    }

    /** "Your IP: 192.168.1.10 · SIP port 5060", so the user can tell the other person. */
    private fun updateMyAddress() {
        val ip = findLocalIpv4()
        binding.tvMyIp.text = when {
            ip == null -> "Not connected to Wi-Fi"
            boundPort > 0 -> "Your IP: $ip · SIP port $boundPort"
            else -> "Your IP: $ip"
        }
    }

    /** Adds a line to the on-screen signaling log (keeps the last 40 lines). */
    private fun log(line: String) {
        logLines.addLast(line)
        while (logLines.size > 40) logLines.removeFirst()
        binding.tvSipLog.text = logLines.joinToString("\n")
    }

    /** Runs [block] on the main thread if the screen still exists. */
    private fun ui(block: () -> Unit) = runOnUiThread { if (!isDestroyed) block() }

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            setStatus("Microphone permission is needed for calls")
        }
    }

    /** Leaving the screen ends the call properly (BYE / CANCEL / Decline) before closing the sockets. */
    override fun onDestroy() {
        when (state) {
            State.IN_CALL -> hangUp()
            State.CALLING -> cancelCall("Call cancelled")
            State.INCOMING -> decline()
            else -> Unit
        }
        handler.removeCallbacksAndMessages(null)
        stopMedia()
        executor.execute { sipSocket?.close() } // runs after the messages queued above
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQ_MIC = 1
        private const val KEY_REMOTE_IP = "remote_ip"
        private const val KEY_REMOTE_PORT = "remote_sip_port"
        private const val KEY_LOCAL_PORT = "local_sip_port"

        /** User part of our SIP URIs, e.g. sip:groceryapp@192.168.1.10:5060. */
        private const val SIP_USER = "groceryapp"
        private const val USER_AGENT = "GroceryApp-VoIP/1.0"
        private const val ALLOW = "INVITE, ACK, BYE, CANCEL, OPTIONS"

        /** Four numbers 0–255 separated by dots, e.g. "192.168.1.20". */
        fun isValidIpv4(ip: String): Boolean {
            val parts = ip.split(".")
            return parts.size == 4 && parts.all { p ->
                p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() in 0..255
            }
        }

        /** The port number, or null if it is empty or outside 1024–65535. */
        fun parsePort(text: String?): Int? =
            text?.trim()?.toIntOrNull()?.takeIf { it in VoipConfig.MIN_PORT..VoipConfig.MAX_PORT }

        /** "<sip:groceryapp@1.2.3.4:5060>;expires=60" -> "sip:groceryapp@1.2.3.4:5060". */
        fun uriOf(headerValue: String): String =
            Regex("<([^>]+)>").find(headerValue)?.groupValues?.get(1) ?: headerValue.substringBefore(';').trim()

        /** Shortens a start line for the log: "INVITE sip:a@b SIP/2.0" -> "INVITE sip:a@b". */
        private fun String.toShortLine() = removeSuffix(" SIP/2.0")

        /**
         * The local IP the phone uses to reach [peer] (asks the routing table by "connecting" a UDP
         * socket, which sends nothing). Falls back to the first Wi-Fi address.
         */
        fun localIpFor(peer: InetAddress): String = runCatching {
            DatagramSocket().use { s ->
                s.connect(peer, 9)
                s.localAddress.hostAddress?.takeIf { it != "0.0.0.0" }
            }
        }.getOrNull() ?: findLocalIpv4() ?: "0.0.0.0"

        /** This phone's IPv4 address on the local network (e.g. Wi-Fi), or null if there is none. */
        fun findLocalIpv4(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
