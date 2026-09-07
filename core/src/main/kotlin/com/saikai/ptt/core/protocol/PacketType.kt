package com.saikai.ptt.core.protocol

/**
 * The one-byte packet type at header offset 5.
 *
 * Values are fixed by `docs/ADR/ADR-003-Wire-Format.md` section 3 and must never
 * be renumbered: a device running an older build is on the same LAN.
 *
 * Four values are declared but not implemented in v1. They are here so that the
 * numbers are reserved rather than accidentally reused, and so that a v2 device
 * sending one is recognisably speaking a dialect this build does not, instead of
 * looking like a corrupt packet.
 *
 * [requiresTarget] and [requiresSession] answer steps 8 and 9 of the validation
 * order (ADR-003 section 8). They live on the type rather than in a `when` inside
 * the validator because they are a property of the packet -- a broadcast has no
 * target, a refusal has no session -- and a table that can only be read in one
 * place is a table that drifts.
 */
enum class PacketType(
    val code: Int,
    val isImplemented: Boolean,
    val requiresTarget: Boolean = false,
    val requiresSession: Boolean = false,
) {

    /** Broadcast announcement: coming online, network recovered, or renamed. */
    DISCOVERY(0x01, true),

    /** Unicast immediate answer to a DISCOVERY. */
    DISCOVERY_RESPONSE(0x02, true, requiresTarget = true),

    /** Broadcast periodic liveness and busy-state announcement. */
    HEARTBEAT(0x10, true),

    /** Unicast reachability probe. Diagnostics and recovery only. */
    PING(0x11, true, requiresTarget = true),

    /** Unicast answer to a PING. */
    PONG(0x12, true, requiresTarget = true),

    /** Unicast request to open a PTT session. */
    VOICE_START(0x20, true, requiresTarget = true, requiresSession = true),

    /** Unicast acceptance of a VOICE_START. The sender may now transmit. */
    VOICE_ACCEPT(0x21, true, requiresTarget = true, requiresSession = true),

    /** Unicast encoded audio frame. The only type on the voice socket. */
    VOICE_DATA(0x22, true, requiresTarget = true, requiresSession = true),

    /** Unicast normal end of transmission by the sender. */
    VOICE_END(0x23, true, requiresTarget = true, requiresSession = true),

    /**
     * Unicast refusal: the target is busy and does not allow interruption.
     *
     * Carries no session: the request was refused, so no session was ever
     * created. The requester has exactly one outstanding VOICE_START at a time
     * and matches the refusal by sender device id (ADR-003 section 6).
     */
    BUSY(0x24, true, requiresTarget = true),

    /** Unicast termination of a session by the receiving side. */
    SESSION_TERMINATE(0x25, true, requiresTarget = true, requiresSession = true),

    /**
     * Reserved, unused in v1.
     *
     * Force Interrupt is a receiver-side setting (ADR-003 section 7), so the
     * decision never travels: the receiver answers VOICE_ACCEPT or BUSY and
     * tells the displaced peer with SESSION_TERMINATE. The number stays reserved
     * because the original protocol draft named it.
     */
    FORCE_INTERRUPT(0x30, false),

    /** Reserved, unused in v1. */
    ERROR(0x31, false),

    /** Reserved, unused in v1. */
    GOODBYE(0x32, false),

    /** Reserved, unused in v1. */
    CAPABILITIES(0x33, false);

    /**
     * The largest payload this type may carry, in bytes.
     *
     * Step 12 of the validation order (ADR-003 section 8). Stated per type
     * rather than as the blanket 1024-byte limit: a HEARTBEAT is at most 68
     * bytes and a PING is zero, so a datagram claiming more is wrong long
     * before it is large. Reserved types accept nothing.
     */
    val maxPayloadBytes: Int
        get() = when (this) {
            DISCOVERY, DISCOVERY_RESPONSE, HEARTBEAT ->
                PresencePayload.FIXED_BYTES + WireFormat.MAX_USER_NAME_BYTES

            PING, PONG, VOICE_ACCEPT, BUSY -> 0

            VOICE_START -> VoiceStartPayload.FIXED_BYTES + WireFormat.MAX_USER_NAME_BYTES
            VOICE_DATA -> WireFormat.MAX_VOICE_PAYLOAD_BYTES
            VOICE_END -> VoiceEndPayload.BYTES
            SESSION_TERMINATE -> SessionTerminatePayload.BYTES

            FORCE_INTERRUPT, ERROR, GOODBYE, CAPABILITIES -> 0
        }

    companion object {

        // A 256-entry lookup instead of a scan over entries: every inbound
        // datagram hits this, including the ones that turn out to be someone
        // else's broadcast traffic.
        private val BY_CODE: Array<PacketType?> = arrayOfNulls<PacketType>(256).also { table ->
            for (type in entries) table[type.code] = type
        }

        /** The type for a wire byte, or null when this build does not know it. */
        fun fromCode(code: Int): PacketType? =
            if (code in 0..255) BY_CODE[code] else null

        /** The types v1 actually sends and parses. */
        val implemented: List<PacketType> = entries.filter { it.isImplemented }

        /** Numbers held back for future versions. */
        val reserved: List<PacketType> = entries.filter { !it.isImplemented }
    }
}
