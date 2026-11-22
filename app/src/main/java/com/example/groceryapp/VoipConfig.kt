package com.example.groceryapp

/**
 * Settings for the SIP/RTP voice call ([VoipActivity]).
 *
 * The other phone's IP and both SIP ports are typed by the user on the call screen;
 * the values here are only defaults and fixed audio parameters.
 */
object VoipConfig {
    /** Standard SIP port. Wireshark decodes UDP 5060 as SIP automatically. */
    const val DEFAULT_SIP_PORT = 5060

    /** Preferred local RTP (voice) port; if it's busy a free port is used. Announced to the other side in SDP. */
    const val PREFERRED_RTP_PORT = 40000

    /** G.711 µ-law (PCMU) — RTP payload type 0, 8 kHz mono. Wireshark can decode and play it. */
    const val RTP_PAYLOAD_PCMU = 0
    const val SAMPLE_RATE = 8000
    const val PACKET_MS = 20
    const val SAMPLES_PER_PACKET = SAMPLE_RATE * PACKET_MS / 1000 // 160 samples = 160 µ-law bytes

    /** Give up on an unanswered INVITE after this long (then CANCEL it). */
    const val INVITE_TIMEOUT_MS = 30_000L

    const val MIN_PORT = 1024        // ports below 1024 are reserved by the system
    const val MAX_PORT = 65535
}
