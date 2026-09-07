package com.saikai.ptt.core.domain

/**
 * One presence packet, translated out of protocol terms.
 *
 * The registry takes this rather than a packet so that the domain does not
 * depend on the wire format: the transport decodes, validates, and hands over
 * the four facts a peer record is made of. It also means the state machine can
 * be tested without constructing a single datagram.
 */
data class PeerObservation(
    val deviceId: DeviceId,
    val userName: String,
    val endpoint: PeerEndpoint,
    val protocolVersion: Int,
    /** The peer's own `peerState` byte: it is in a call with someone. */
    val remoteBusy: Boolean,
    val kind: PresenceKind,
)

/**
 * Which of the three presence packet types carried an observation.
 *
 * All three refresh liveness; only [HEARTBEAT] proves the periodic channel
 * works, which is the difference between [PresenceState.DISCOVERED] and
 * [PresenceState.ONLINE].
 */
enum class PresenceKind {
    /** A broadcast announcement: coming online, network recovered, or renamed. */
    DISCOVERY,

    /** The unicast answer to this device's own announcement. */
    DISCOVERY_RESPONSE,

    /** The periodic broadcast that liveness is actually measured by. */
    HEARTBEAT,
}
