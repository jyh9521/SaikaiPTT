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
 */
enum class PacketType(val code: Int, val isImplemented: Boolean) {

    /** Broadcast announcement: coming online, network recovered, or renamed. */
    DISCOVERY(0x01, true),

    /** Unicast immediate answer to a DISCOVERY. */
    DISCOVERY_RESPONSE(0x02, true),

    /** Broadcast periodic liveness and busy-state announcement. */
    HEARTBEAT(0x10, true),

    /** Unicast reachability probe. Diagnostics and recovery only. */
    PING(0x11, true),

    /** Unicast answer to a PING. */
    PONG(0x12, true),

    /** Unicast request to open a PTT session. */
    VOICE_START(0x20, true),

    /** Unicast acceptance of a VOICE_START. The sender may now transmit. */
    VOICE_ACCEPT(0x21, true),

    /** Unicast encoded audio frame. The only type on the voice socket. */
    VOICE_DATA(0x22, true),

    /** Unicast normal end of transmission by the sender. */
    VOICE_END(0x23, true),

    /** Unicast refusal: the target is busy and does not allow interruption. */
    BUSY(0x24, true),

    /** Unicast termination of a session by the receiving side. */
    SESSION_TERMINATE(0x25, true),

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
