package com.saikai.ptt.core.protocol

/**
 * The busy flag a peer announces in DISCOVERY, DISCOVERY_RESPONSE and HEARTBEAT.
 *
 * `docs/04_UI_UX.md` sections 9 and 11 require the device list to show that a
 * peer is in a call. Without this byte a third device has no way to learn it,
 * because the packets of a call only ever go to the two devices in it.
 */
enum class PeerState(val code: Int) {
    IDLE(0x00),
    BUSY(0x01);

    companion object {
        fun fromCode(code: Int): PeerState? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The audio codec named in VOICE_START.
 *
 * One value in v1. It is on the wire so that a future build can offer a second
 * codec without a protocol version bump, and so that a mismatch is a clean
 * refusal rather than noise in the speaker.
 */
enum class AudioCodec(val code: Int) {
    OPUS(0x01);

    companion object {
        fun fromCode(code: Int): AudioCodec? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Why the receiving side ended a session (`docs/ADR/ADR-003-Wire-Format.md` section 4).
 *
 * The sender needs the reason to decide what to record: a displaced speaker
 * writes an INTERRUPTED history entry, a network loss does not mean the peer
 * refused, and a service shutdown is not an error to report at all.
 */
enum class TerminationReason(val code: Int) {
    /** Another device was allowed to take over this receiver. */
    INTERRUPTED_BY_PEER(0x01),

    /** No packets arrived for long enough that the session is assumed dead. */
    TIMEOUT(0x02),

    /** The receiving device is shutting the service down. */
    SERVICE_SHUTDOWN(0x03),

    /** The receiving device lost its network. */
    NETWORK_LOST(0x04);

    companion object {
        fun fromCode(code: Int): TerminationReason? = entries.firstOrNull { it.code == code }
    }
}
