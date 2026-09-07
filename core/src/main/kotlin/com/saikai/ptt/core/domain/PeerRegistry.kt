package com.saikai.ptt.core.domain

import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.config.PresenceConfig
import com.saikai.ptt.core.config.RateLimitConfig
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogFormat
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why the registry refused an observation. */
enum class PeerRegistryError {
    /** 64 peers already, none of them offline. */
    TABLE_FULL,

    /** No such peer. Only presence packets may create one. */
    UNKNOWN_PEER,

    /** The all-zero device id, which is "none" on the wire and never a device. */
    INVALID_IDENTITY,

    /** A name that is blank or past the 64-byte wire limit. */
    INVALID_NAME,
}

/**
 * What changed about one peer.
 *
 * The endpoint and name flags are separate from the state because they mean
 * different things to different listeners: the device list redraws for a name,
 * the transport re-targets for an endpoint, and neither cares about the other.
 */
data class PeerChange(
    val peer: Peer,
    /** Null when the peer was created by this observation. */
    val previousState: PresenceState?,
    val endpointChanged: Boolean,
    val userNameChanged: Boolean,
) {
    val isNew: Boolean get() = previousState == null
    val stateChanged: Boolean get() = previousState != null && previousState != peer.state
}

/**
 * The peer table and the presence state machine.
 *
 * Three facts are stored per peer -- when it was last heard from, whether its
 * own presence packets say it is busy, and whether this device is in a session
 * with it -- and [PresenceState] is recomputed from all three after every change.
 * Nothing ever assigns a state directly. That is the whole design: an assigned
 * state can be left stale by an event arriving in an unexpected order, and with
 * five states and three asynchronous inputs the unexpected orders are the
 * common case, not the corner case.
 *
 * Recomputation covers every peer, not just the one that changed. The table
 * holds at most 64 entries and changes a few times a second at the very most, so
 * the cost is irrelevant next to the class of bug it removes: opening a session
 * with B has to move A out of COMMUNICATING too, and remembering that at every
 * call site is exactly the kind of thing that gets forgotten.
 *
 * Identity is the device id. A peer that changes address updates its endpoint
 * (`docs/03_Protocol.md` section 11); it never becomes a second peer.
 *
 * Nothing here throws. An observation the registry will not accept comes back as
 * a value: this is fed directly from the network, and a malformed packet from a
 * device on the LAN must not be able to take down the receive loop.
 *
 * Safe to call from any thread. Every mutation runs under one lock; [peers] is a
 * StateFlow so the UI never reads a half-updated table.
 */
class PeerRegistry(
    private val presence: PresenceConfig,
    private val limits: RateLimitConfig,
    private val logger: Logger,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private val entries = LinkedHashMap<DeviceId, Entry>()
    private var sessionPeer: DeviceId? = null
    private var networkAvailable: Boolean = true

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())

    /** The table, in the order peers were first seen. Ordering for display is the UI's. */
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val timeoutMillis: Long get() = presence.peerTimeout.inWholeMilliseconds

    /**
     * Records a DISCOVERY, DISCOVERY_RESPONSE or HEARTBEAT.
     *
     * Creates the peer if it is new, updates its name, endpoint and busy flag if
     * it is not, and refreshes liveness either way.
     */
    fun onPresence(observation: PeerObservation): Outcome<PeerChange, PeerRegistryError> {
        if (observation.deviceId.isZero) return Outcome.failure(PeerRegistryError.INVALID_IDENTITY)
        val name = UserNameValidator.normalizeRemote(observation.userName)
            ?: return Outcome.failure(PeerRegistryError.INVALID_NAME)

        val now = nowMillis()
        synchronized(lock) {
            val existing = entries[observation.deviceId]
            if (existing == null && !makeRoom(now)) {
                logger.throttled(LogLevel.WARN, LogCategory.PRESENCE, "peer-table-full") {
                    "peer table is full at ${limits.maxPeers}; ignoring " +
                        "${LogFormat.userName(name)}"
                }
                return Outcome.failure(PeerRegistryError.TABLE_FULL)
            }

            val previousState = existing?.state
            val entry = existing ?: Entry(
                userName = name,
                endpoint = observation.endpoint,
                protocolVersion = observation.protocolVersion,
                lastSeenMillis = now,
            ).also { entries[observation.deviceId] = it }

            val endpointChanged = existing != null && entry.endpoint != observation.endpoint
            val userNameChanged = existing != null && entry.userName != name

            if (endpointChanged) {
                // Section 11: the same device on a new address. Worth a line --
                // this is what a roaming phone looks like in a bug report.
                logger.i(LogCategory.PRESENCE) {
                    "peer ${LogFormat.userName(name)} moved ${entry.endpoint} -> " +
                        "${observation.endpoint}"
                }
            }

            entry.userName = name
            entry.endpoint = observation.endpoint
            entry.protocolVersion = observation.protocolVersion
            entry.remoteBusy = observation.remoteBusy
            entry.lastSeenMillis = now
            if (observation.kind == PresenceKind.HEARTBEAT) entry.heartbeatSeen = true

            // A brand new peer is derived before the sweep, so that its arrival
            // is one event -- Added -- rather than Added immediately followed by
            // a spurious DISCOVERED -> ONLINE transition it was never in.
            if (existing == null) entry.state = derive(observation.deviceId, entry, now)

            refresh(now)
            return Outcome.success(
                PeerChange(
                    peer = entry.toPeer(observation.deviceId),
                    previousState = previousState,
                    endpointChanged = endpointChanged,
                    userNameChanged = userNameChanged,
                )
            )
        }
    }

    /**
     * Refreshes liveness from any other valid packet -- VOICE_*, PONG.
     *
     * `docs/03_Protocol.md` section 13: any valid packet proves the peer is
     * there. Section 12.3: only presence packets decide what it *is*, which is
     * why this cannot promote DISCOVERED to ONLINE.
     *
     * @return false when the peer is unknown. Activity never creates one: a
     *   record with no name and no voice port would be worse than none.
     */
    fun onActivity(deviceId: DeviceId): Boolean {
        val now = nowMillis()
        synchronized(lock) {
            val entry = entries[deviceId] ?: return false
            entry.lastSeenMillis = now
            refresh(now)
            return true
        }
    }

    /**
     * Sets which peer this device is in a session with, or null for none.
     *
     * One at a time: SaikaiPTT is one-to-one, and letting two peers be
     * COMMUNICATING at once would make the table describe something the product
     * cannot do.
     *
     * @return false when the peer is unknown, in which case nothing changes.
     */
    fun setSessionPeer(deviceId: DeviceId?): Boolean {
        val now = nowMillis()
        synchronized(lock) {
            if (deviceId != null && deviceId !in entries) return false
            if (sessionPeer == deviceId) return true
            sessionPeer = deviceId
            refresh(now)
            return true
        }
    }

    /**
     * Records whether this device has a network at all.
     *
     * A fourth fact, and the one that outranks the rest: with no network, every
     * peer is unreachable regardless of when it was last heard from
     * (`docs/03_Protocol.md` section 41). Setting it is what makes the list go
     * offline the moment WiFi drops, rather than sixteen seconds later after a
     * sweep that could not have heard anything anyway.
     *
     * Entries are kept, not cleared. Their endpoints are stale, but nothing
     * sends to an offline peer, and a list that empties and refills is a worse
     * answer to "did my radio just disappear" than one that greys out.
     */
    fun setNetworkAvailable(available: Boolean): Boolean = synchronized(lock) {
        if (networkAvailable == available) return false
        networkAvailable = available
        refresh(nowMillis())
        return true
    }

    /**
     * The periodic sweep (`docs/03_Protocol.md` section 13).
     *
     * Timeouts are the one transition with no event behind it, so something has
     * to ask. Scheduling belongs to the service; the interval is
     * [PresenceConfig.evaluationInterval].
     *
     * @return the peers whose state changed.
     */
    fun evaluate(): List<PeerChange> {
        val now = nowMillis()
        synchronized(lock) {
            return refresh(now)
        }
    }

    fun peer(deviceId: DeviceId): Peer? = synchronized(lock) {
        entries[deviceId]?.toPeer(deviceId)
    }

    fun snapshot(): List<Peer> = _peers.value

    /** Drops a peer. Used when the table is being rebuilt, not on timeout. */
    fun forget(deviceId: DeviceId): Boolean = synchronized(lock) {
        val removed = entries.remove(deviceId) != null
        if (removed) {
            if (sessionPeer == deviceId) sessionPeer = null
            refresh(nowMillis())
        }
        removed
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        sessionPeer = null
        networkAvailable = true
        _peers.value = emptyList()
    }

    /**
     * Recomputes every state and republishes. Caller holds the lock.
     *
     * @return the peers whose state changed.
     */
    private fun refresh(now: Long): List<PeerChange> {
        var changes: MutableList<PeerChange>? = null
        for ((deviceId, entry) in entries) {
            val next = derive(deviceId, entry, now)
            if (next == entry.state) continue
            val previous = entry.state
            entry.state = next
            val peer = entry.toPeer(deviceId)
            logger.d(LogCategory.PRESENCE) {
                "peer ${LogFormat.userName(peer.userName)} $previous -> $next"
            }
            (changes ?: mutableListOf<PeerChange>().also { changes = it }).add(
                PeerChange(peer, previous, endpointChanged = false, userNameChanged = false)
            )
        }
        _peers.value = entries.map { (deviceId, entry) -> entry.toPeer(deviceId) }
        return changes ?: emptyList()
    }

    /**
     * The whole state machine.
     *
     * Order is precedence, and each line earns its place:
     *
     * - No network outranks everything, including silence: nothing is reachable
     *   and no amount of waiting will change that.
     * - Silence outranks the rest. A peer that stopped answering is offline
     *   even mid-call; the session layer finds out from here, not the reverse.
     * - A session with *this* device outranks the peer's own busy flag, which is
     *   up to a heartbeat old and would otherwise show our own call as somebody
     *   else's.
     * - Busy outranks online because it is strictly more specific.
     * - A HEARTBEAT is what separates ONLINE from DISCOVERED.
     */
    private fun derive(deviceId: DeviceId, entry: Entry, now: Long): PresenceState = when {
        !networkAvailable -> PresenceState.OFFLINE
        now - entry.lastSeenMillis >= timeoutMillis -> PresenceState.OFFLINE
        deviceId == sessionPeer -> PresenceState.COMMUNICATING
        entry.remoteBusy -> PresenceState.BUSY
        entry.heartbeatSeen -> PresenceState.ONLINE
        else -> PresenceState.DISCOVERED
    }

    /**
     * Makes space for a new peer if the table is full. Caller holds the lock.
     *
     * `docs/03_Protocol.md` section 45 caps the table at 64 and says to refuse
     * additions beyond it. Refusing outright would mean that a room which has
     * seen 64 devices over a week can never discover the 65th, so an offline
     * entry -- a device that has not been heard from in over 16 seconds and
     * cannot be called -- is given up first. A table of 64 *live* peers still
     * refuses, which is the case the cap exists for.
     */
    private fun makeRoom(now: Long): Boolean {
        if (entries.size < limits.maxPeers) return true
        val stalest = entries.entries
            .filter { it.value.state == PresenceState.OFFLINE }
            .minByOrNull { it.value.lastSeenMillis }
            ?: return false
        entries.remove(stalest.key)
        logger.i(LogCategory.PRESENCE) {
            "peer table full; dropped offline peer ${LogFormat.userName(stalest.value.userName)}"
        }
        return true
    }

    private class Entry(
        var userName: String,
        var endpoint: PeerEndpoint,
        var protocolVersion: Int,
        var lastSeenMillis: Long,
        var remoteBusy: Boolean = false,
        var heartbeatSeen: Boolean = false,
        var state: PresenceState = PresenceState.DISCOVERED,
    ) {
        fun toPeer(deviceId: DeviceId): Peer = Peer(
            deviceId = deviceId,
            userName = userName,
            endpoint = endpoint,
            protocolVersion = protocolVersion,
            state = state,
            lastSeenMillis = lastSeenMillis,
        )
    }
}
