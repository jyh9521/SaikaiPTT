package com.saikai.ptt.core.domain

/**
 * What this device says about itself when it announces.
 *
 * Assembled at the moment of sending rather than held as state: the name can
 * change while the service runs, the voice port is only known once the socket
 * is bound, and the busy flag belongs to the session machine. A snapshot taken
 * per announcement cannot go stale between them.
 */
data class LocalPresence(
    val deviceId: DeviceId,
    val userName: String,
    val voicePort: Int,
    val busy: Boolean,
)

/** Why this device is announcing itself (`docs/03_Protocol.md` section 10.1). */
enum class AnnounceReason {
    /** The communication service reached READY. */
    SERVICE_READY,

    /** The network came back, so peers have to be told the address again. */
    NETWORK_RECOVERED,

    /** The user renamed themselves or switched to another stored name. */
    USER_CHANGED,
}

/**
 * Makes this device visible on the local network.
 *
 * An interface with one implementation, which `docs/ADR/ADR-001` asks for
 * deliberately: v1 is pure UDP broadcast, and mDNS was rejected on the grounds
 * of Android version drift and low-end ROM behaviour rather than on principle.
 * Keeping the seam means a future NSD implementation slots in underneath the
 * peer model instead of through it.
 *
 * Discovering *others* is not part of this interface. Incoming presence packets
 * land in [PeerRegistry] whichever mechanism found them, and the registry is
 * what the UI reads.
 */
interface PeerDiscovery {

    /**
     * Announces this device, several times, spread over a moment.
     *
     * A single broadcast lost to a single dropped frame would leave two devices
     * invisible to each other until the next heartbeat, so
     * [com.saikai.ptt.core.config.DiscoveryConfig.announceDelays] sends three.
     *
     * Returns as soon as the burst is scheduled; it completes in the background.
     * Calling again replaces a burst still in flight -- two rapid renames should
     * announce the second name, not race the first.
     */
    suspend fun announce(reason: AnnounceReason)
}
