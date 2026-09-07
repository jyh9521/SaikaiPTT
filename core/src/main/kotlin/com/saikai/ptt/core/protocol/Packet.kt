package com.saikai.ptt.core.protocol

import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.protocol.WireFormat.HEADER_BYTES
import com.saikai.ptt.core.protocol.WireFormat.MAX_PAYLOAD_BYTES

/**
 * A header and its payload.
 *
 * The two are only ever built together, because [PacketHeader.payloadLength] and
 * [PacketPayload.encodedSize] describe the same bytes and a disagreement between
 * them is the classic wire-format bug: it produces a datagram that looks fine
 * locally and is dropped by every peer.
 */
data class Packet(
    val header: PacketHeader,
    val payload: PacketPayload,
) {

    init {
        require(header.payloadLength == payload.encodedSize) {
            "Header says ${header.payloadLength} payload bytes, payload encodes " +
                "${payload.encodedSize}"
        }
        require(payload.encodedSize <= MAX_PAYLOAD_BYTES) {
            "Payload is ${payload.encodedSize} bytes, limit is $MAX_PAYLOAD_BYTES"
        }
    }

    /** The packet type, or null if the header carried a code this build does not know. */
    val type: PacketType? get() = header.packetType

    /** Total datagram length. */
    val encodedSize: Int get() = HEADER_BYTES + payload.encodedSize

    /**
     * Writes the whole datagram into [target] at [offset] and returns its length.
     *
     * [target] is caller-owned: the socket layer allocates one buffer per sending
     * thread at start-up and reuses it, so the send path allocates nothing per
     * packet.
     */
    fun encodeTo(target: ByteArray, offset: Int = 0): Int {
        require(offset >= 0 && offset + encodedSize <= target.size) {
            "Need $encodedSize bytes at offset $offset in a ${target.size}-byte buffer"
        }
        header.writeTo(target, offset)
        payload.writeTo(target, offset + HEADER_BYTES)
        return encodedSize
    }

    /** Allocating convenience for tests and cold paths. */
    fun toBytes(): ByteArray = ByteArray(encodedSize).also { encodeTo(it) }

    companion object {

        /** Builds a packet, deriving the header's version and payload length. */
        fun of(
            type: PacketType,
            senderDeviceId: DeviceId,
            payload: PacketPayload,
            timestampMillis: Long,
            targetDeviceId: DeviceId = DeviceId.ZERO,
            sessionId: SessionId = SessionId.ZERO,
            sequenceNumber: Int = SequenceNumbers.CONTROL,
        ): Packet = Packet(
            header = PacketHeader.of(
                type = type,
                senderDeviceId = senderDeviceId,
                payload = payload,
                timestampMillis = timestampMillis,
                targetDeviceId = targetDeviceId,
                sessionId = sessionId,
                sequenceNumber = sequenceNumber,
            ),
            payload = payload,
        )
    }
}
