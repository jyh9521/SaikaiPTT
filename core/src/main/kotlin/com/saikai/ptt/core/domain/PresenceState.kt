package com.saikai.ptt.core.domain

/**
 * What this device currently knows about one peer.
 *
 * `docs/03_Protocol.md` section 14 names the five states. One explicit state,
 * never a set of independent booleans: "online and busy and not communicating"
 * has eight combinations, six of which are nonsense, and every screen that reads
 * them has to agree on which six.
 *
 * The state is *derived*, not assigned. Three facts feed it -- when the peer was
 * last heard from, whether its own presence packets say it is busy, and whether
 * this device is in a session with it -- and [PeerRegistry] recomputes the state
 * from all three whenever any of them changes. That is what makes the machine
 * deterministic: there is no order of events that can leave two facts agreeing
 * and the stored state disagreeing with both.
 *
 * [DISCOVERED] and [ONLINE] are separated on the strength of the evidence, which
 * is what section 14's "heartbeat primarily decides ONLINE / OFFLINE" is getting
 * at. A DISCOVERY announcement proves a device exists right now; only a HEARTBEAT
 * proves its periodic liveness channel reaches us. A peer that sits at DISCOVERED
 * until it times out is the visible symptom of the access point in section 10.6
 * that forwards unicast but filters broadcast -- a distinction worth keeping,
 * even though the device list draws the two identically.
 */
enum class PresenceState {

    /** Announced itself, but no HEARTBEAT has arrived yet. Reachable. */
    DISCOVERED,

    /** Heard from within the timeout, including at least one HEARTBEAT. */
    ONLINE,

    /**
     * In a call with some other device.
     *
     * Driven only by the `peerState` byte in the peer's own presence packets
     * (`docs/03_Protocol.md` section 12.2) -- the sole way a third device can
     * learn this. Advisory: it lags by up to a heartbeat, so a request must
     * still be sent and answered (`docs/04_UI_UX.md` section 11.2).
     */
    BUSY,

    /** In a call with *this* device. Driven by the local session, not the network. */
    COMMUNICATING,

    /** Nothing valid heard for [com.saikai.ptt.core.config.PresenceConfig.peerTimeout]. */
    OFFLINE,
    ;

    /** Whether a packet sent to this peer has any chance of arriving. */
    val isReachable: Boolean get() = this != OFFLINE

    /**
     * Whether the UI should let the user press to talk to this peer.
     *
     * True even for [BUSY]: `docs/04_UI_UX.md` section 38 requires the request to
     * be made and answered rather than pre-empted, because the peer may allow
     * interruption and because the busy flag is up to a heartbeat old.
     */
    val acceptsRequest: Boolean get() = this != OFFLINE && this != COMMUNICATING

    /**
     * Whether [next] is a transition the registry is allowed to produce.
     *
     * Written out rather than derived, so that it is a specification the
     * derivation is checked against instead of a restatement of it. The one
     * asymmetry is worth reading twice: any state may fall back to [DISCOVERED]
     * except [ONLINE], because having once seen a HEARTBEAT is permanent -- a
     * peer that is BUSY or COMMUNICATING may never have sent one, and returns to
     * DISCOVERED when it stops being busy.
     */
    fun canTransitionTo(next: PresenceState): Boolean =
        next != this && next in allowedNext()

    private fun allowedNext(): Set<PresenceState> = when (this) {
        DISCOVERED -> setOf(ONLINE, BUSY, COMMUNICATING, OFFLINE)
        ONLINE -> setOf(BUSY, COMMUNICATING, OFFLINE)
        BUSY -> setOf(DISCOVERED, ONLINE, COMMUNICATING, OFFLINE)
        COMMUNICATING -> setOf(DISCOVERED, ONLINE, BUSY, OFFLINE)
        OFFLINE -> setOf(DISCOVERED, ONLINE, BUSY, COMMUNICATING)
    }
}
