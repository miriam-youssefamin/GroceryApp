package com.example.groceryapp

import kotlin.random.Random

/**
 * A SIP message (RFC 3261) sent as text over UDP: a start line, headers, a blank line and an
 * optional body (here always SDP).
 *
 * Request:  "INVITE sip:groceryapp@192.168.1.20:5060 SIP/2.0"
 * Response: "SIP/2.0 200 OK"
 *
 * Content-Length is added automatically by [encode]. Lines end with CRLF as the standard requires,
 * so Wireshark decodes the messages as SIP.
 */
class SipMessage(
    val startLine: String,
    val headers: List<Pair<String, String>>,
    val body: String = ""
) {
    val isResponse: Boolean get() = startLine.startsWith("SIP/2.0")

    /** Request method (INVITE, ACK, BYE, CANCEL…); for a response, the method from its CSeq. */
    val method: String
        get() = if (isResponse) header("CSeq")?.substringAfter(' ')?.trim().orEmpty()
                else startLine.substringBefore(' ')

    /** Response status code (e.g. 180, 200), or 0 for requests. */
    val statusCode: Int get() = if (isResponse) startLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0 else 0

    val callId: String get() = header("Call-ID").orEmpty()

    /** Header value by name (case-insensitive; also accepts SIP's one-letter compact forms). */
    fun header(name: String): String? {
        val compact = COMPACT[name.lowercase()]
        return headers.firstOrNull { (k, _) ->
            k.equals(name, ignoreCase = true) || (compact != null && k.equals(compact, ignoreCase = true))
        }?.second
    }

    /** The bytes to put in the UDP packet. */
    fun encode(): ByteArray {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder().append(startLine).append(CRLF)
        headers.filterNot { it.first.equals("Content-Length", ignoreCase = true) }
            .forEach { (k, v) -> sb.append(k).append(": ").append(v).append(CRLF) }
        sb.append("Content-Length: ").append(bodyBytes.size).append(CRLF).append(CRLF)
        return sb.toString().toByteArray(Charsets.UTF_8) + bodyBytes
    }

    companion object {
        private const val CRLF = "\r\n"
        private val COMPACT = mapOf(
            "via" to "v", "from" to "f", "to" to "t", "call-id" to "i",
            "contact" to "m", "content-length" to "l", "content-type" to "c"
        )

        /** Parses a received UDP payload; null if it doesn't look like SIP. */
        fun parse(data: ByteArray, length: Int): SipMessage? {
            val text = String(data, 0, length, Charsets.UTF_8)
            val split = text.indexOf("\r\n\r\n").takeIf { it >= 0 } ?: text.indexOf("\n\n")
            val head = if (split >= 0) text.substring(0, split) else text
            val body = if (split >= 0) text.substring(split).trimStart('\r', '\n') else ""
            val lines = head.split("\r\n", "\n").filter { it.isNotEmpty() }
            val start = lines.firstOrNull() ?: return null
            if (!start.startsWith("SIP/2.0") && !start.endsWith("SIP/2.0")) return null
            val headers = lines.drop(1).mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            return SipMessage(start, headers, body)
        }

        /** Random token for tags, branches and Call-IDs. */
        fun token(length: Int = 10): String {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            return (1..length).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        }

        /** A new Via branch; "z9hG4bK" is the magic prefix RFC 3261 requires. */
        fun newBranch() = "z9hG4bK" + token(12)

        /** Reads ";tag=..." from a From/To header value. */
        fun tagOf(headerValue: String?): String? =
            headerValue?.let { Regex(";tag=([^;>\\s]+)").find(it)?.groupValues?.get(1) }

        /**
         * Builds a response to [request]: copies Via, From, Call-ID and CSeq, and adds our tag to To
         * (as RFC 3261 §8.2.6 requires).
         */
        fun response(
            request: SipMessage,
            code: Int,
            reason: String,
            toTag: String?,
            extraHeaders: List<Pair<String, String>> = emptyList(),
            body: String = ""
        ): SipMessage {
            val to = request.header("To").orEmpty().let { v ->
                if (toTag != null && tagOf(v) == null) "$v;tag=$toTag" else v
            }
            val headers = mutableListOf<Pair<String, String>>()
            request.headers.filter { it.first.equals("Via", true) || it.first.equals("v", true) }
                .forEach { headers += "Via" to it.second }
            headers += "From" to request.header("From").orEmpty()
            headers += "To" to to
            headers += "Call-ID" to request.callId
            headers += "CSeq" to request.header("CSeq").orEmpty()
            headers += extraHeaders
            return SipMessage("SIP/2.0 $code $reason", headers, body)
        }
    }
}

/**
 * SDP (RFC 4566) body inside INVITE / 200 OK: tells the other phone where to send our RTP voice
 * and which codec we use (PCMU/8000).
 */
object Sdp {
    /** Our offer/answer: "send me PCMU audio at [ip]:[rtpPort]". */
    fun describe(ip: String, rtpPort: Int, sessionId: Long): String = listOf(
        "v=0",
        "o=groceryapp $sessionId $sessionId IN IP4 $ip",
        "s=GroceryApp voice call",
        "c=IN IP4 $ip",
        "t=0 0",
        "m=audio $rtpPort RTP/AVP ${VoipConfig.RTP_PAYLOAD_PCMU}",
        "a=rtpmap:${VoipConfig.RTP_PAYLOAD_PCMU} PCMU/${VoipConfig.SAMPLE_RATE}",
        "a=ptime:${VoipConfig.PACKET_MS}",
        "a=sendrecv"
    ).joinToString("\r\n", postfix = "\r\n")

    /** Reads the other side's RTP address (c= line) and port (m=audio line); null if missing. */
    fun parseAudioTarget(body: String): Pair<String, Int>? {
        val lines = body.lines().map { it.trim() }
        val ip = lines.firstOrNull { it.startsWith("c=") }?.split(" ")?.getOrNull(2) ?: return null
        val port = lines.firstOrNull { it.startsWith("m=audio") }?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: return null
        return ip to port
    }
}
