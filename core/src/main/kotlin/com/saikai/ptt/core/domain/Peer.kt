package com.saikai.ptt.core.domain

/**
 * Another device on the LAN, as this one currently understands it.
 *
 * Identity is [deviceId] and nothing else. The name changes when the user
 * renames themselves, the endpoint changes when the network does, and neither
 * makes a new peer (`docs/03_Protocol.md` sections 10.4 and 11).
 *
 * [state] is the single answer to "what is this peer doing", derived by
 * [PeerRegistry] from the facts it has. The facts themselves are deliberately
 * not exposed: a screen that could read "busy" and "last seen" separately would
 * eventually combine them differently from the screen next to it.
 */
data class Peer(
    val deviceId: DeviceId,
    val userName: String,
    val endpoint: PeerEndpoint,
    val protocolVersion: Int,
    val state: PresenceState,
    /** Epoch milliseconds of the last valid packet of any kind from this peer. */
    val lastSeenMillis: Long,
) {
    init {
        require(!deviceId.isZero) { "The all-zero device id is not a peer" }
        require(userName.isNotEmpty()) { "A peer always has a name; the protocol requires one" }
    }
}
