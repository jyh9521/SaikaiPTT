package com.saikai.ptt.core.protocol

/**
 * The byte-level constants of the SaikaiPTT wire format.
 *
 * Normative source: `docs/ADR/ADR-003-Wire-Format.md` sections 1 and 2. These
 * are not tuning knobs. Changing any value here changes what two devices agree
 * on, which requires a protocol version bump and a new ADR -- which is why they
 * are compile-time constants rather than configuration.
 *
 * `SaikaiConfig.protocol` mirrors these so that the rest of the application can
 * read them through one configuration object; it takes its defaults from here,
 * so there is a single source of truth.
 *
 * Every multi-byte field is big-endian (network byte order).
 */
object WireFormat {

    /** Protocol version carried in the header. v1. */
    const val PROTOCOL_VERSION: Int = 1

    // ASCII "SKPT". Held as four separate bytes rather than a ByteArray so that
    // magic checking compares constants instead of walking a shared array that
    // some caller could have mutated.
    const val MAGIC_0: Byte = 0x53 // 'S'
    const val MAGIC_1: Byte = 0x4B // 'K'
    const val MAGIC_2: Byte = 0x50 // 'P'
    const val MAGIC_3: Byte = 0x54 // 'T'

    /** Length of the magic prefix, in bytes. */
    const val MAGIC_BYTES: Int = 4

    /** Fixed header length. Every packet type shares it. */
    const val HEADER_BYTES: Int = 72

    /** Largest payload any packet type may carry. */
    const val MAX_PAYLOAD_BYTES: Int = 1024

    /**
     * Tighter cap for VOICE_DATA.
     *
     * At 16 kHz / 20 ms / 20 kbps an Opus packet is about 50 bytes. 400 leaves
     * room for a bad frame without letting the voice path anywhere near the
     * general limit.
     */
    const val MAX_VOICE_PAYLOAD_BYTES: Int = 400

    /** Largest datagram the protocol can produce. Well under the 1500-byte MTU. */
    const val MAX_DATAGRAM_BYTES: Int = HEADER_BYTES + MAX_PAYLOAD_BYTES

    /** Largest datagram a voice frame can produce. */
    const val MAX_VOICE_DATAGRAM_BYTES: Int = HEADER_BYTES + MAX_VOICE_PAYLOAD_BYTES

    /** Wire limit on a display name, in UTF-8 bytes. */
    const val MAX_USER_NAME_BYTES: Int = 64

    // Header field offsets, from the first byte of the header.
    const val OFFSET_MAGIC: Int = 0
    const val OFFSET_PROTOCOL_VERSION: Int = 4
    const val OFFSET_PACKET_TYPE: Int = 5
    const val OFFSET_FLAGS: Int = 6
    const val OFFSET_PAYLOAD_LENGTH: Int = 8
    const val OFFSET_RESERVED: Int = 10
    const val OFFSET_SENDER_DEVICE_ID: Int = 12
    const val OFFSET_TARGET_DEVICE_ID: Int = 28
    const val OFFSET_SESSION_ID: Int = 44
    const val OFFSET_SEQUENCE_NUMBER: Int = 60
    const val OFFSET_TIMESTAMP: Int = 64

    /** Allocating copy of the magic prefix, for configuration and diagnostics. */
    fun magic(): ByteArray = byteArrayOf(MAGIC_0, MAGIC_1, MAGIC_2, MAGIC_3)

    /**
     * True when [source] starts with `SKPT` at [offset].
     *
     * The cheapest possible rejection of the other broadcast traffic on a busy
     * LAN, and step 2 of the validation order in ADR-003 section 8. Never
     * throws: the input is a datagram from the network.
     */
    fun hasMagic(source: ByteArray, offset: Int = 0, length: Int = source.size - offset): Boolean {
        if (offset < 0 || length < MAGIC_BYTES || offset + MAGIC_BYTES > source.size) return false
        return source[offset] == MAGIC_0 &&
            source[offset + 1] == MAGIC_1 &&
            source[offset + 2] == MAGIC_2 &&
            source[offset + 3] == MAGIC_3
    }

    /** A receive or send buffer big enough for any packet. Allocate once per thread. */
    fun newDatagramBuffer(): ByteArray = ByteArray(MAX_DATAGRAM_BYTES)

    // --- Big-endian primitives -------------------------------------------------
    //
    // Hand-written rather than java.nio.ByteBuffer: a ByteBuffer wrapping a
    // reused array is itself an allocation, and the voice path encodes 50
    // packets a second.

    internal fun writeU8(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
    }

    internal fun readU8(source: ByteArray, offset: Int): Int = source[offset].toInt() and 0xFF

    internal fun writeU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    internal fun readU16(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 8) or (source[offset + 1].toInt() and 0xFF)

    internal fun writeU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    /** Reads a u32 as raw bits. Values above 2^31-1 come back negative by design. */
    internal fun readU32(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 24) or
            ((source[offset + 1].toInt() and 0xFF) shl 16) or
            ((source[offset + 2].toInt() and 0xFF) shl 8) or
            (source[offset + 3].toInt() and 0xFF)

    /** Reads a u32 into the full unsigned range, for fields that must be range-checked. */
    internal fun readU32AsLong(source: ByteArray, offset: Int): Long =
        readU32(source, offset).toLong() and 0xFFFFFFFFL

    internal fun writeI64(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until Long.SIZE_BYTES) {
            target[offset + i] = (value ushr (8 * (Long.SIZE_BYTES - 1 - i))).toByte()
        }
    }

    internal fun readI64(source: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until Long.SIZE_BYTES) {
            result = (result shl 8) or (source[offset + i].toLong() and 0xFF)
        }
        return result
    }
}
