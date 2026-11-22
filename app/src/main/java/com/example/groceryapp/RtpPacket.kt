package com.example.groceryapp

import kotlin.random.Random

/**
 * RTP (RFC 3550) packets carrying the voice of the call.
 *
 * Every packet = 12-byte header + payload (20 ms of G.711 µ-law audio):
 *
 *      0                   1                   2                   3
 *     |V=2|P|X| CC  |M|     PT      |       sequence number         |
 *     |                           timestamp                           |
 *     |                             SSRC                              |
 *     |                     payload (160 bytes)                       |
 *
 *  - V   version, always 2
 *  - M   marker bit, set on the first packet of the call (start of talk-spurt)
 *  - PT  payload type, 0 = PCMU
 *  - sequence number +1 per packet (lets the receiver notice loss / reordering)
 *  - timestamp +160 per packet (sampling clock: 160 samples = 20 ms at 8 kHz)
 *  - SSRC random id of this audio stream
 */
object RtpPacket {
    const val HEADER_SIZE = 12

    /** A received packet's header fields plus where its payload is inside the buffer. */
    class Parsed(
        val payloadType: Int,
        val marker: Boolean,
        val sequence: Int,
        val timestamp: Long,
        val ssrc: Long,
        val payloadOffset: Int,
        val payloadLength: Int
    )

    /** Builds one RTP packet. */
    fun build(payloadType: Int, marker: Boolean, sequence: Int, timestamp: Long, ssrc: Long, payload: ByteArray): ByteArray {
        val p = ByteArray(HEADER_SIZE + payload.size)
        p[0] = 0x80.toByte()                                            // V=2, P=0, X=0, CC=0
        p[1] = ((if (marker) 0x80 else 0) or (payloadType and 0x7F)).toByte()
        p[2] = (sequence shr 8).toByte(); p[3] = sequence.toByte()
        p[4] = (timestamp shr 24).toByte(); p[5] = (timestamp shr 16).toByte()
        p[6] = (timestamp shr 8).toByte(); p[7] = timestamp.toByte()
        p[8] = (ssrc shr 24).toByte(); p[9] = (ssrc shr 16).toByte()
        p[10] = (ssrc shr 8).toByte(); p[11] = ssrc.toByte()
        payload.copyInto(p, HEADER_SIZE)
        return p
    }

    /** Reads the header of a received packet; null if it isn't RTP version 2. */
    fun parse(data: ByteArray, length: Int): Parsed? {
        if (length < HEADER_SIZE) return null
        val b0 = data[0].toInt() and 0xFF
        if (b0 shr 6 != 2) return null                                   // not RTP v2
        val csrcCount = b0 and 0x0F
        val headerLength = HEADER_SIZE + csrcCount * 4
        if (length < headerLength) return null
        val b1 = data[1].toInt() and 0xFF
        fun u8(i: Int) = (data[i].toLong() and 0xFF)
        return Parsed(
            payloadType = b1 and 0x7F,
            marker = b1 and 0x80 != 0,
            sequence = ((u8(2) shl 8) or u8(3)).toInt(),
            timestamp = (u8(4) shl 24) or (u8(5) shl 16) or (u8(6) shl 8) or u8(7),
            ssrc = (u8(8) shl 24) or (u8(9) shl 16) or (u8(10) shl 8) or u8(11),
            payloadOffset = headerLength,
            payloadLength = length - headerLength
        )
    }

    /**
     * Keeps the sending state of one outgoing stream. Sequence number, timestamp and SSRC
     * start at random values, as RFC 3550 recommends.
     */
    class Sender(private val payloadType: Int, private val samplesPerPacket: Int) {
        val ssrc: Long = Random.nextLong(0, 0xFFFFFFFFL)
        private var sequence = Random.nextInt(0, 0xFFFF)
        private var timestamp = Random.nextLong(0, 0xFFFFFFFFL)
        private var first = true

        /** Wraps [payload] in the next RTP header. */
        fun next(payload: ByteArray): ByteArray {
            val packet = build(payloadType, first, sequence, timestamp, ssrc, payload)
            first = false
            sequence = (sequence + 1) and 0xFFFF
            timestamp = (timestamp + samplesPerPacket) and 0xFFFFFFFFL
            return packet
        }
    }
}
