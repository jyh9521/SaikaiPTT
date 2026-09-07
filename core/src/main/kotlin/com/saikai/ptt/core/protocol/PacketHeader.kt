package com.saikai.ptt.core.protocol

import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.protocol.WireFormat.HEADER_BYTES
import com.saikai.ptt.core.protocol.WireFormat.MAGIC_0
import com.saikai.ptt.core.protocol.WireFormat.MAGIC_1
import com.saikai.ptt.core.protocol.WireFormat.MAGIC_2
import com.saikai.ptt.core.protocol.WireFormat.MAGIC_3
import com.saikai.ptt.core.protocol.WireFormat.OFFSET_FLAGS
import com.saikai.ptt.core.protocol.WireFormat.OFFSET_MAGIC
import com.saikai.ptt.core.protocol.WireFormat.OFFSET_PACKET_TYPE
import com.saikai.ptt.core.protocol.WireFormat.OFFSET_PAYLOAD_LENGTH
import com.saikai.ptt.core.protocol.WireFormat.OFFSET_PROTOCOL_VERSION
import com.saikai.ptt.core.protocol.WireFormat.OFFSET_RESERVED
import com.saikai.ptt.core.protocol.WireFormat.OFFSET_SENDER_DEVICE_ID
import com.saikai.ptt.core.protocol.WireFormat.OFFSET_SEQUENCE_NUMBER
import com.saikai.ptt.core.protocol.WireFormat.OFFSET_SESSION_ID
import com.saikai.ptt.core.protocol.WireFormat.OFFSET_TARGET_DEVICE_ID
import com.saikai.ptt.core.protocol.WireFormat.OFFSET_TIMESTAMP
import com.saikai.ptt.core.protocol.WireFormat.PROTOCOL_VERSION
import com.saikai.ptt.core.protocol.WireFormat.readI64
import com.saikai.ptt.core.protocol.WireFormat.readU16
import com.saikai.ptt.core.protocol.WireFormat.readU32
import com.saikai.ptt.core.protocol.WireFormat.readU8
import com.saikai.ptt.core.protocol.WireFormat.writeI64
import com.saikai.ptt.core.protocol.WireFormat.writeU16
import com.saikai.ptt.core.protocol.WireFormat.writeU32
import com.saikai.ptt.core.protocol.WireFormat.writeU8

/**
 * The fixed 72-byte header every packet begins with.
 *
 * Layout is fixed by `docs/ADR/ADR-003-Wire-Format.md` section 2 and pinned by a
 * byte-for-byte test. One header for all packet types costs a broadcast a few
 * unused bytes and buys a single parse path and a single validation path, which
 * is where the bugs would otherwise be.
 *
 * [packetTypeCode] is the raw wire byte rather than a [PacketType], because a
 * packet from a future version carries a type this build has no name for and
 * still has to be parsed far enough to be counted and dropped. [packetType] is
 * null for exactly those.
 *
 * [reserved] and [flags] are kept as read rather than normalised: whether a
 * non-zero `Reserved` means "drop" is a validation decision (section 8 step 5),
 * not something the reader should quietly erase.
 */
data class PacketHeader(
    val protocolVersion: Int,
    val packetTypeCode: Int,
    val flags: Int,
    val payloadLength: Int,
    val reserved: Int,
    val senderDeviceId: DeviceId,
    val targetDeviceId: DeviceId,
    val sessionId: SessionId,
    val sequenceNumber: Int,
    val timestampMillis: Long,
) {

    init {
        // These bounds are what a u8 or u16 field can physically hold, so they
        // are always true for a decoded header and catch a caller building an
        // impossible one. Semantic limits -- PayloadLength at most 1024, for
        // instance -- deliberately live in validation, because a hostile
        // datagram must be dropped, never thrown at.
        require(protocolVersion in 0..0xFF) { "protocolVersion is u8, was $protocolVersion" }
        require(packetTypeCode in 0..0xFF) { "packetTypeCode is u8, was $packetTypeCode" }
        require(flags in 0..0xFFFF) { "flags is u16, was $flags" }
        require(payloadLength in 0..0xFFFF) { "payloadLength is u16, was $payloadLength" }
        require(reserved in 0..0xFFFF) { "reserved is u16, was $reserved" }
    }

    /** The known type, or null when this build does not recognise the code. */
    val packetType: PacketType? get() = PacketType.fromCode(packetTypeCode)

    /** Total datagram length this header describes. */
    val datagramLength: Int get() = HEADER_BYTES + payloadLength

    /**
     * Writes all 72 bytes into [target] at [offset], magic included.
     *
     * Takes a caller-owned buffer so that the send path can reuse one array per
     * thread and allocate nothing per packet.
     */
    fun writeTo(target: ByteArray, offset: Int = 0) {
        require(offset >= 0 && offset + HEADER_BYTES <= target.size) {
            "Need $HEADER_BYTES bytes at offset $offset in a ${target.size}-byte buffer"
        }
        target[offset + OFFSET_MAGIC] = MAGIC_0
        target[offset + OFFSET_MAGIC + 1] = MAGIC_1
        target[offset + OFFSET_MAGIC + 2] = MAGIC_2
        target[offset + OFFSET_MAGIC + 3] = MAGIC_3
        writeU8(target, offset + OFFSET_PROTOCOL_VERSION, protocolVersion)
        writeU8(target, offset + OFFSET_PACKET_TYPE, packetTypeCode)
        writeU16(target, offset + OFFSET_FLAGS, flags)
        writeU16(target, offset + OFFSET_PAYLOAD_LENGTH, payloadLength)
        writeU16(target, offset + OFFSET_RESERVED, reserved)
        senderDeviceId.writeTo(target, offset + OFFSET_SENDER_DEVICE_ID)
        targetDeviceId.writeTo(target, offset + OFFSET_TARGET_DEVICE_ID)
        sessionId.writeTo(target, offset + OFFSET_SESSION_ID)
        writeU32(target, offset + OFFSET_SEQUENCE_NUMBER, sequenceNumber)
        writeI64(target, offset + OFFSET_TIMESTAMP, timestampMillis)
    }

    /** Allocating convenience for tests and cold paths. */
    fun toBytes(): ByteArray = ByteArray(HEADER_BYTES).also { writeTo(it) }

    companion object {

        /**
         * Builds a header for [payload], filling in version and payload length.
         *
         * Defaults match what a control packet carries: no target for a
         * broadcast, no session, sequence zero (ADR-003 section 5).
         */
        fun of(
            type: PacketType,
            senderDeviceId: DeviceId,
            payload: PacketPayload,
            timestampMillis: Long,
            targetDeviceId: DeviceId = DeviceId.ZERO,
            sessionId: SessionId = SessionId.ZERO,
            sequenceNumber: Int = SequenceNumbers.CONTROL,
            flags: Int = 0,
        ): PacketHeader = PacketHeader(
            protocolVersion = PROTOCOL_VERSION,
            packetTypeCode = type.code,
            flags = flags,
            payloadLength = payload.encodedSize,
            reserved = 0,
            senderDeviceId = senderDeviceId,
            targetDeviceId = targetDeviceId,
            sessionId = sessionId,
            sequenceNumber = sequenceNumber,
            timestampMillis = timestampMillis,
        )

        /**
         * Reads 72 bytes into a header, or returns null when the range is short.
         *
         * Structural only: it does not check the magic, the version, the type or
         * the payload length. Those are separate steps in a fixed order
         * (ADR-003 section 8) and each is counted differently, so this reader
         * must not collapse them into one null.
         */
        fun read(
            source: ByteArray,
            offset: Int = 0,
            length: Int = source.size - offset,
        ): PacketHeader? {
            if (offset < 0 || length < HEADER_BYTES || offset + HEADER_BYTES > source.size) return null
            val sender = DeviceId.fromBytes(source, offset + OFFSET_SENDER_DEVICE_ID) ?: return null
            val target = DeviceId.fromBytes(source, offset + OFFSET_TARGET_DEVICE_ID) ?: return null
            val session = SessionId.fromBytes(source, offset + OFFSET_SESSION_ID) ?: return null
            return PacketHeader(
                protocolVersion = readU8(source, offset + OFFSET_PROTOCOL_VERSION),
                packetTypeCode = readU8(source, offset + OFFSET_PACKET_TYPE),
                flags = readU16(source, offset + OFFSET_FLAGS),
                payloadLength = readU16(source, offset + OFFSET_PAYLOAD_LENGTH),
                reserved = readU16(source, offset + OFFSET_RESERVED),
                senderDeviceId = sender,
                targetDeviceId = target,
                sessionId = session,
                sequenceNumber = readU32(source, offset + OFFSET_SEQUENCE_NUMBER),
                timestampMillis = readI64(source, offset + OFFSET_TIMESTAMP),
            )
        }
    }
}
