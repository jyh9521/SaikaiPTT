package com.saikai.ptt.core.protocol

import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.protocol.WireFormat.HEADER_BYTES
import com.saikai.ptt.core.protocol.WireFormat.MAGIC_0
import com.saikai.ptt.core.protocol.WireFormat.MAGIC_1
import com.saikai.ptt.core.protocol.WireFormat.MAGIC_2
import com.saikai.ptt.core.protocol.WireFormat.MAGIC_3
import com.saikai.ptt.core.protocol.WireFormat.MAX_PAYLOAD_BYTES
import com.saikai.ptt.core.protocol.WireFormat.MAX_VOICE_PAYLOAD_BYTES
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
import com.saikai.ptt.core.protocol.WireFormat.writeI64
import com.saikai.ptt.core.protocol.WireFormat.writeU16
import com.saikai.ptt.core.protocol.WireFormat.writeU32
import com.saikai.ptt.core.protocol.WireFormat.writeU8

/**
 * Turns bytes into packets and packets into bytes.
 *
 * Stateless, so one instance serves both receive threads without a lock. The
 * buffers belong to the caller: the transport allocates one per thread with
 * [WireFormat.newDatagramBuffer] and hands the same array in every time.
 *
 * [decode] performs the structural checks needed to produce a packet at all --
 * magic, version, a known type, a payload length that matches the datagram, and
 * a payload that parses. It deliberately stops there. The ordered validation of
 * ADR-003 section 8, which also filters the device's own broadcast echo, packets
 * addressed elsewhere, and frames from a session that has ended, needs to know
 * which check failed in order to count and log it, and it needs this device's
 * own identity, which a codec has no business holding. That belongs to
 * `PacketValidator` (Task12), which calls these same primitives in order.
 */
object PacketCodec {

    /**
     * Parses one datagram, or returns null if it is not a packet this build can read.
     *
     * Never throws, whatever the bytes are.
     *
     * For VOICE_DATA the returned payload is a *view* over [source], not a copy,
     * so it stops being valid as soon as the caller reuses the buffer. Use
     * [VoiceDataPayload.copyFrame] for anything that outlives the receive call.
     */
    fun decode(source: ByteArray, offset: Int = 0, length: Int = source.size - offset): Packet? {
        if (offset < 0 || length < HEADER_BYTES || offset + length > source.size) return null
        if (!WireFormat.hasMagic(source, offset, length)) return null

        val header = PacketHeader.read(source, offset, length) ?: return null
        if (header.protocolVersion != PROTOCOL_VERSION) return null

        val type = header.packetType ?: return null
        if (!type.isImplemented) return null

        if (header.payloadLength > MAX_PAYLOAD_BYTES) return null
        if (HEADER_BYTES + header.payloadLength != length) return null

        val payload = decodePayload(
            type = type,
            source = source,
            offset = offset + HEADER_BYTES,
            length = header.payloadLength,
        ) ?: return null

        return Packet(header, payload)
    }

    /**
     * Parses the payload for [type], or returns null when it does not fit the layout.
     *
     * Split out so that validation can reach it as its own numbered step rather
     * than re-deriving the type-to-layout mapping.
     */
    fun decodePayload(
        type: PacketType,
        source: ByteArray,
        offset: Int,
        length: Int,
    ): PacketPayload? = when (type) {
        PacketType.DISCOVERY,
        PacketType.DISCOVERY_RESPONSE,
        PacketType.HEARTBEAT,
        -> PresencePayload.read(source, offset, length)

        PacketType.PING,
        PacketType.PONG,
        PacketType.VOICE_ACCEPT,
        PacketType.BUSY,
        -> if (length == 0) EmptyPayload else null

        PacketType.VOICE_START -> VoiceStartPayload.read(source, offset, length)
        PacketType.VOICE_DATA -> VoiceDataPayload.read(source, offset, length)
        PacketType.VOICE_END -> VoiceEndPayload.read(source, offset, length)
        PacketType.SESSION_TERMINATE -> SessionTerminatePayload.read(source, offset, length)

        PacketType.FORCE_INTERRUPT,
        PacketType.ERROR,
        PacketType.GOODBYE,
        PacketType.CAPABILITIES,
        -> null
    }

    /**
     * Encodes one VOICE_DATA packet straight into [target], allocating nothing.
     *
     * The general path through [Packet] allocates a header and a payload object
     * per call. That is negligible for control packets, which appear about once
     * a second, but VOICE_DATA is 50 packets a second for the whole length of a
     * transmission and is the one path where the garbage would be measurable
     * against the 150 ms end-to-end budget. This writes the header fields
     * directly instead.
     *
     * @return the datagram length.
     */
    fun encodeVoiceData(
        senderDeviceId: DeviceId,
        targetDeviceId: DeviceId,
        sessionId: SessionId,
        sequenceNumber: Int,
        timestampMillis: Long,
        frame: ByteArray,
        frameOffset: Int,
        frameLength: Int,
        target: ByteArray,
        targetOffset: Int = 0,
    ): Int {
        require(frameLength in 1..MAX_VOICE_PAYLOAD_BYTES) {
            "A voice frame is 1..$MAX_VOICE_PAYLOAD_BYTES bytes, was $frameLength"
        }
        require(frameOffset >= 0 && frameOffset + frameLength <= frame.size) {
            "Frame range is outside the source buffer"
        }
        val datagramLength = HEADER_BYTES + frameLength
        require(targetOffset >= 0 && targetOffset + datagramLength <= target.size) {
            "Need $datagramLength bytes at offset $targetOffset in a ${target.size}-byte buffer"
        }

        target[targetOffset + OFFSET_MAGIC] = MAGIC_0
        target[targetOffset + OFFSET_MAGIC + 1] = MAGIC_1
        target[targetOffset + OFFSET_MAGIC + 2] = MAGIC_2
        target[targetOffset + OFFSET_MAGIC + 3] = MAGIC_3
        writeU8(target, targetOffset + OFFSET_PROTOCOL_VERSION, PROTOCOL_VERSION)
        writeU8(target, targetOffset + OFFSET_PACKET_TYPE, PacketType.VOICE_DATA.code)
        writeU16(target, targetOffset + OFFSET_FLAGS, 0)
        writeU16(target, targetOffset + OFFSET_PAYLOAD_LENGTH, frameLength)
        writeU16(target, targetOffset + OFFSET_RESERVED, 0)
        senderDeviceId.writeTo(target, targetOffset + OFFSET_SENDER_DEVICE_ID)
        targetDeviceId.writeTo(target, targetOffset + OFFSET_TARGET_DEVICE_ID)
        sessionId.writeTo(target, targetOffset + OFFSET_SESSION_ID)
        writeU32(target, targetOffset + OFFSET_SEQUENCE_NUMBER, sequenceNumber)
        writeI64(target, targetOffset + OFFSET_TIMESTAMP, timestampMillis)
        frame.copyInto(target, targetOffset + HEADER_BYTES, frameOffset, frameOffset + frameLength)

        return datagramLength
    }
}
