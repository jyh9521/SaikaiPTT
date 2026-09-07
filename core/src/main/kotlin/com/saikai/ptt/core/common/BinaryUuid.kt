package com.saikai.ptt.core.common

import java.util.UUID

/**
 * The 16-byte big-endian encoding of a UUID.
 *
 * Both identifiers that travel in a packet header -- the Device ID and the
 * Session ID -- are UUIDs carried as 16 raw bytes rather than the 36-character
 * text form (`docs/ADR/ADR-003-Wire-Format.md` section 1). Two of them appear in
 * every header at roughly 50 packets a second, so the text form would cost
 * 40 extra bytes per packet for no benefit.
 *
 * Kept in one place because the byte order here is protocol-critical: a
 * disagreement between the two identifier types would be invisible on one
 * device and fatal between two.
 *
 * Reading never validates. The bytes are 128 opaque bits; whether a particular
 * value is acceptable (all-zero meaning "none", or a foreign device's id) is a
 * question for the caller, not the encoding.
 */
internal object BinaryUuid {

    /** Length of the binary form, in bytes. */
    const val BYTES: Int = 16

    fun write(value: UUID, target: ByteArray, offset: Int) {
        writeLong(target, offset, value.mostSignificantBits)
        writeLong(target, offset + Long.SIZE_BYTES, value.leastSignificantBits)
    }

    fun read(source: ByteArray, offset: Int): UUID =
        UUID(readLong(source, offset), readLong(source, offset + Long.SIZE_BYTES))

    private fun writeLong(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until Long.SIZE_BYTES) {
            target[offset + i] = (value ushr (8 * (Long.SIZE_BYTES - 1 - i))).toByte()
        }
    }

    private fun readLong(source: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until Long.SIZE_BYTES) {
            result = (result shl 8) or (source[offset + i].toLong() and 0xFF)
        }
        return result
    }
}
