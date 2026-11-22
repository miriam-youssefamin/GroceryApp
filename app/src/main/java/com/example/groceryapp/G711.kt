package com.example.groceryapp

/**
 * G.711 µ-law codec (ITU-T G.711), the "PCMU" codec of RTP payload type 0.
 *
 * Each 16-bit PCM sample is compressed to one byte, so 20 ms of 8 kHz audio is 160 bytes.
 * Standard telephony codec, so Wireshark (Telephony → VoIP Calls → Play Streams) can play the capture.
 */
object G711 {
    private const val BIAS = 0x84    // 132, added before finding the segment
    private const val CLIP = 32635   // largest magnitude that fits after adding BIAS

    /** One 16-bit linear PCM sample -> one µ-law byte. */
    fun encode(sample: Int): Byte {
        var pcm = sample
        val sign = if (pcm < 0) 0x80 else 0
        if (pcm < 0) pcm = -pcm
        if (pcm > CLIP) pcm = CLIP
        pcm += BIAS

        // Exponent = position of the highest set bit above bit 7 (segment 0–7).
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && (pcm and mask) == 0) {
            exponent--
            mask = mask shr 1
        }
        val mantissa = (pcm shr (exponent + 3)) and 0x0F
        // µ-law bytes are sent inverted (all bits flipped).
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    /** One µ-law byte -> one 16-bit linear PCM sample. */
    fun decode(ulaw: Byte): Short {
        val v = ulaw.toInt().inv() and 0xFF
        val sign = v and 0x80
        val exponent = (v shr 4) and 0x07
        val mantissa = v and 0x0F
        val magnitude = (((mantissa shl 3) + BIAS) shl exponent) - BIAS
        return (if (sign != 0) -magnitude else magnitude).toShort()
    }

    /** Encodes the first [count] samples of [pcm]. */
    fun encode(pcm: ShortArray, count: Int): ByteArray = ByteArray(count) { encode(pcm[it].toInt()) }

    /** Decodes [length] µ-law bytes starting at [offset]. */
    fun decode(data: ByteArray, offset: Int, length: Int): ShortArray =
        ShortArray(length) { decode(data[offset + it]) }
}
