package com.saikai.ptt.core.protocol

import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogFormat
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.WireFormat.HEADER_BYTES
import com.saikai.ptt.core.protocol.WireFormat.MAX_PAYLOAD_BYTES
import com.saikai.ptt.core.protocol.WireFormat.PROTOCOL_VERSION

/**
 * The twelve ordered checks that stand between a UDP datagram and the rest of
 * the application.
 *
 * The order is normative (`docs/ADR/ADR-003-Wire-Format.md` section 8) and this
 * class implements it literally, one numbered step per block, because the order
 * is what makes the cost bounded: the magic check rejects the LAN's other
 * broadcast traffic after four byte comparisons, long before anything allocates
 * or parses a field. A validator that decoded first and judged afterwards would
 * be correct and would also do the expensive work on every stray packet.
 *
 * Nothing here throws. Every failure is a value. `docs/03_Protocol.md` section 29
 * is unambiguous: an invalid packet is dropped, counted, and never reaches the
 * business layer, and it must not be able to crash the app. The receive loop is
 * the most exposed surface this application has -- anything on the network can
 * reach it -- so the guarantee has to be structural rather than a promise, which
 * is why decoding returns null everywhere instead of raising.
 *
 * Logging is throttled per rejection kind and sits in the PROTOCOL category,
 * which release builds do not enable (section 45: a packet flood must not become
 * a log flood, and unknown types must not print in release at all).
 *
 * Safe to call from both receive threads. It holds no mutable state of its own;
 * [stats] is lock-free and [receivingSession] is expected to be a cheap read of
 * state someone else owns.
 *
 * @param localDeviceId this device's identity, for the loopback and target checks.
 * @param receivingSession the voice session currently open, or null. Read on
 *   every VOICE_DATA, so it must not block.
 */
class PacketValidator(
    private val localDeviceId: DeviceId,
    private val logger: Logger,
    val stats: ProtocolStats = ProtocolStats(),
    private val receivingSession: () -> ReceivingSession? = { null },
) {

    /**
     * Runs the twelve checks over one datagram.
     *
     * @return the packet, or the step that rejected it. For VOICE_DATA the
     *   payload is a view over [source]; see [PacketCodec.decode].
     */
    fun validate(
        source: ByteArray,
        offset: Int = 0,
        length: Int = source.size - offset,
    ): Outcome<Packet, RejectionReason> {
        if (offset < 0 || length < 0 || offset + length > source.size) {
            return reject(RejectionReason.TOO_SHORT, length)
        }

        // 1. At least a full header.
        if (length < HEADER_BYTES) return reject(RejectionReason.TOO_SHORT, length)

        // 2. Magic. Four byte comparisons, and most of a busy LAN stops here.
        if (!WireFormat.hasMagic(source, offset, length)) {
            return rejectWithPreview(RejectionReason.BAD_MAGIC, source, offset, length)
        }

        val header = PacketHeader.read(source, offset, length)
            ?: return reject(RejectionReason.TOO_SHORT, length)

        // 3. A version this build speaks.
        if (header.protocolVersion != PROTOCOL_VERSION) {
            return reject(RejectionReason.UNSUPPORTED_VERSION, length, header)
        }

        // 4. Declared payload length inside the limit and matching what arrived.
        if (header.payloadLength > MAX_PAYLOAD_BYTES ||
            HEADER_BYTES + header.payloadLength != length
        ) {
            return reject(RejectionReason.PAYLOAD_LENGTH_MISMATCH, length, header)
        }

        // 5. Reserved must be zero. Flags are deliberately not checked: v1
        //    requires them to be zero but also requires unknown bits to be
        //    ignored, so a future version can set one without being dropped.
        if (header.reserved != 0) {
            return reject(RejectionReason.RESERVED_NOT_ZERO, length, header)
        }

        // 6. A type this build implements.
        val type = header.packetType
        if (type == null || !type.isImplemented) {
            return reject(RejectionReason.UNKNOWN_PACKET_TYPE, length, header)
        }

        // 7. A real sender, and not this device's own broadcast coming back.
        if (header.senderDeviceId.isZero) {
            return reject(RejectionReason.SENDER_INVALID, length, header)
        }
        if (header.senderDeviceId == localDeviceId) {
            return reject(RejectionReason.OWN_BROADCAST_ECHO, length, header)
        }

        // 8. Addressed to this device, for the types that are addressed at all.
        if (type.requiresTarget && header.targetDeviceId != localDeviceId) {
            return reject(RejectionReason.WRONG_TARGET, length, header)
        }

        // 9. Session packets must name a session.
        if (type.requiresSession && header.sessionId.isZero) {
            return reject(RejectionReason.MISSING_SESSION, length, header)
        }

        // 10. The payload fits its type's layout.
        val payload = PacketCodec.decodePayload(
            type = type,
            source = source,
            offset = offset + HEADER_BYTES,
            length = header.payloadLength,
        ) ?: return reject(RejectionReason.MALFORMED_PAYLOAD, length, header)

        // 11. A voice frame must belong to the open session and come from the
        //     device that opened it (03_Protocol section 35).
        if (type == PacketType.VOICE_DATA) {
            val open = receivingSession()
            if (open == null || open.sessionId != header.sessionId) {
                return reject(RejectionReason.FOREIGN_SESSION, length, header)
            }
            if (open.senderDeviceId != header.senderDeviceId) {
                return reject(RejectionReason.FOREIGN_SESSION_SENDER, length, header)
            }
        }

        // 12. The per-type size cap. Step 10 already refuses anything that does
        //     not parse, so this rarely fires -- but the cap is a protocol rule,
        //     and a rule that only exists inside a payload reader is a rule the
        //     next payload reader will forget.
        if (header.payloadLength > type.maxPayloadBytes) {
            return reject(RejectionReason.PAYLOAD_TOO_LARGE, length, header)
        }

        stats.recordAccepted()
        return Outcome.success(Packet(header, payload))
    }

    private fun reject(
        reason: RejectionReason,
        length: Int,
        header: PacketHeader? = null,
    ): Outcome<Packet, RejectionReason> {
        stats.recordRejected(reason)
        if (reason.isLoggable) {
            logger.throttled(LogLevel.DEBUG, LogCategory.PROTOCOL, reason.logKind) {
                buildString {
                    append("drop step=").append(reason.step)
                    append(' ').append(reason.logKind)
                    append(" len=").append(length)
                    if (header != null) {
                        append(" type=0x").append(header.packetTypeCode.toString(16))
                        append(" from=").append(header.senderDeviceId)
                    }
                }
            }
        }
        return Outcome.failure(reason)
    }

    private fun rejectWithPreview(
        reason: RejectionReason,
        source: ByteArray,
        offset: Int,
        length: Int,
    ): Outcome<Packet, RejectionReason> {
        stats.recordRejected(reason)
        if (reason.isLoggable) {
            logger.throttled(LogLevel.DEBUG, LogCategory.PROTOCOL, reason.logKind) {
                "drop step=${reason.step} ${reason.logKind} len=$length " +
                    LogFormat.bytes(source, offset, length)
            }
        }
        return Outcome.failure(reason)
    }
}
