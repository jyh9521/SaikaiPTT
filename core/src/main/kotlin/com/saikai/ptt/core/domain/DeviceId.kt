package com.saikai.ptt.core.domain

import java.util.UUID

/**
 * The permanent identity of one installation.
 *
 * Generated once as a UUID v4 and never changed afterwards. Deliberately not
 * derived from the MAC address, IMEI or serial number: those are restricted on
 * modern Android, they identify hardware rather than an installation, and they
 * would tie a user's communication history to a device they may not own for its
 * whole life (`docs/01_PRD.md` section 5.1).
 *
 * A Device ID is not an address. The IP changes whenever the network does; the
 * identity does not. Keeping the two apart is what lets a peer move between
 * access points without appearing as a new device (`docs/03_Protocol.md`
 * section 11).
 *
 * On the wire it is 16 raw bytes, not the 36-character text form. At 50 packets
 * a second with two ids in every header, the text form would cost 40 extra bytes
 * per packet for no benefit (`docs/ADR/ADR-003-Wire-Format.md`).
 */
class DeviceId private constructor(private val uuid: UUID) {

    /** Canonical text form, for storage and diagnostics. */
    val value: String get() = uuid.toString()

    /** True for the all-zero id, which the protocol uses to mean "none" or "broadcast". */
    val isZero: Boolean
        get() = uuid.mostSignificantBits == 0L && uuid.leastSignificantBits == 0L

    /** True when this was generated as a random (version 4) UUID. */
    val isVersion4: Boolean get() = uuid.version() == 4

    /**
     * Writes the 16-byte big-endian form into [target] at [offset].
     *
     * Takes a caller-owned buffer so packet encoding allocates nothing per
     * packet, which matters on the send path.
     */
    fun writeTo(target: ByteArray, offset: Int = 0) {
        require(offset >= 0 && offset + BYTES <= target.size) {
            "Need $BYTES bytes at offset $offset in a ${target.size}-byte buffer"
        }
        writeLong(target, offset, uuid.mostSignificantBits)
        writeLong(target, offset + Long.SIZE_BYTES, uuid.leastSignificantBits)
    }

    /** Allocating convenience for tests and cold paths. */
    fun toBytes(): ByteArray = ByteArray(BYTES).also { writeTo(it) }

    override fun equals(other: Any?): Boolean = this === other || (other is DeviceId && uuid == other.uuid)

    override fun hashCode(): Int = uuid.hashCode()

    override fun toString(): String = value

    companion object {
        /** Length of the binary form, in bytes. */
        const val BYTES: Int = 16

        /** The all-zero id: "no target" for broadcast packets, never a real device. */
        val ZERO: DeviceId = DeviceId(UUID(0L, 0L))

        /** A fresh random (version 4) identity. */
        fun random(): DeviceId = DeviceId(UUID.randomUUID())

        /**
         * Parses the canonical text form, or returns null.
         *
         * Never throws: the input is stored data, which can be truncated by a
         * process kill mid-write, and a damaged value must not stop the app
         * starting (`docs/05_DataModel.md` section 41).
         *
         * Accepts any well-formed UUID rather than requiring version 4. The
         * protocol carries 16 opaque bytes; rejecting an id that was stored by
         * an older build would change this installation's identity, which is a
         * far worse outcome than accepting an unusual variant.
         */
        fun parse(text: String?): DeviceId? {
            if (text.isNullOrBlank()) return null
            return try {
                val uuid = UUID.fromString(text.trim())
                // UUID.fromString is lenient about short groups, so round-trip
                // to reject anything that would not re-serialise identically.
                if (uuid.toString().equals(text.trim(), ignoreCase = true)) DeviceId(uuid) else null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /** Reads the 16-byte big-endian form, or returns null when the range is short. */
        fun fromBytes(source: ByteArray, offset: Int = 0): DeviceId? {
            if (offset < 0 || offset + BYTES > source.size) return null
            return DeviceId(
                UUID(
                    readLong(source, offset),
                    readLong(source, offset + Long.SIZE_BYTES),
                )
            )
        }

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
}
