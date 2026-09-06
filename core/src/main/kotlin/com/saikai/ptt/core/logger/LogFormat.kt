package com.saikai.ptt.core.logger

/**
 * Formatting helpers for things that must never be logged in full.
 *
 * `.claude/CLAUDE.md` section 26 and `docs/03_Protocol.md` section 50 forbid
 * logging raw audio payloads. The reasons are both privacy -- a debug log would
 * contain what people said -- and practicality: 50 frames a second of hex would
 * make the log useless and cost real battery.
 *
 * Binary is therefore never passed to the Logger directly. It goes through
 * [bytes], which reports the length and a short prefix, which is what is
 * actually useful when diagnosing a malformed packet.
 */
object LogFormat {

    /** Bytes shown before truncation. Enough to see a header, far too few to reconstruct speech. */
    const val PREVIEW_BYTES: Int = 8

    /**
     * A bounded description of a byte range: length, then up to [PREVIEW_BYTES]
     * bytes of hex.
     *
     * The output length does not grow with the input, so this is safe to call on
     * a voice frame or a 1 KB payload.
     */
    fun bytes(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): String {
        if (length <= 0) return "<0 bytes>"
        val shown = minOf(length, PREVIEW_BYTES)
        val builder = StringBuilder(shown * 2 + 24)
        builder.append('<').append(length).append(" bytes: ")
        for (i in 0 until shown) {
            val b = data[offset + i].toInt() and 0xFF
            builder.append(HEX[b ushr 4]).append(HEX[b and 0x0F])
        }
        if (length > shown) builder.append('…')
        return builder.append('>').toString()
    }

    /**
     * A user name reduced to something that identifies it in a log without
     * reproducing it. Names are personal data and appear in every voice packet.
     */
    fun userName(name: String): String =
        if (name.length <= 1) "<name:${name.length}>" else "${name.first()}…(${name.length})"

    private val HEX = "0123456789abcdef".toCharArray()
}
