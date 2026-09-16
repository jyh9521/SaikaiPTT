package com.saikai.ptt.service

import com.saikai.ptt.core.domain.Peer
import com.saikai.ptt.core.domain.StorageError
import com.saikai.ptt.core.session.ReceptionStats
import com.saikai.ptt.core.session.SessionOutcome
import com.saikai.ptt.core.session.SessionState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the UI is allowed to know about the communication service.
 *
 * Application scope, not service scope: its whole job is to be readable when the
 * service is not running. `docs/ADR/ADR-005` section 5 says the app does not
 * promise to survive a process kill, so the screen that opens next has to be
 * able to ask what state things are in -- and after a start that failed, why.
 *
 * A shared flow rather than a bound service. Binding would tie the answer to a
 * live connection, which is exactly the case where there is nothing to bind to.
 */
class ServiceStatus {

    private val _state = MutableStateFlow(ServiceState.STOPPED)
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    private val _lastFailure = MutableStateFlow<LifecycleFailure?>(null)

    /** The step that failed the last start attempt, cleared by the next success. */
    val lastFailure: StateFlow<LifecycleFailure?> = _lastFailure.asStateFlow()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())

    /**
     * The peer table, mirrored out of service scope so a screen can read it.
     *
     * Empty whenever the service is not running, which is the honest answer:
     * nothing has been heard from anyone since the sockets closed. Task32 gives
     * the UI its own view model over this.
     */
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _session = MutableStateFlow<SessionState>(SessionState.Idle)

    /**
     * What the session machine is doing, mirrored out of service scope.
     *
     * Idle whenever the service is not running, which is true rather than
     * merely convenient: there is no machine, so there is no session. The UI
     * reads this to know whether the talk button is pressed, waiting or live
     * without holding a reference to anything owned by the service.
     */
    val session: StateFlow<SessionState> = _session.asStateFlow()

    fun publishPeers(peers: List<Peer>) {
        _peers.value = peers
    }

    fun publishSession(state: SessionState) {
        _session.value = state
    }

    private val _lastReception = MutableStateFlow<ReceptionStats?>(null)

    /**
     * How the last incoming transmission went.
     *
     * `docs/01_PRD.md` asks for the loss count on a developer information page.
     * That page is Task35; this is where it will read from, and the debug
     * screen shows it in the meantime. Kept after the session ends, because the
     * numbers are only complete once there is nothing left to add to them.
     */
    val lastReception: StateFlow<ReceptionStats?> = _lastReception.asStateFlow()

    fun publishReception(stats: ReceptionStats?) {
        if (stats != null) _lastReception.value = stats
    }

    private val _outcomes = MutableSharedFlow<SessionOutcome>(
        replay = 0,
        extraBufferCapacity = 8,
    )

    /**
     * How sessions finished, for whatever wants to say so.
     *
     * A shared flow rather than a state flow, because these are events and not
     * a condition. "The peer is busy" is true for the moment it is shown and
     * false afterwards; held in a state flow it would be redelivered to the
     * next screen that collected it, and a user who rotated their phone would
     * be told again about a call they abandoned a minute ago.
     */
    val outcomes: SharedFlow<SessionOutcome> = _outcomes.asSharedFlow()

    fun publishOutcome(outcome: SessionOutcome) {
        _outcomes.tryEmit(outcome)
    }

    private val _storageErrors = MutableSharedFlow<StorageError>(
        replay = 0,
        extraBufferCapacity = 4,
    )

    /**
     * Recordings and history rows that could not be stored.
     *
     * Events, like [outcomes], and for the same reason: by the time anything
     * reads one the conversation it concerns is over, and a value held as state
     * would be shown again to the next screen that looked. Separate from
     * [outcomes] because a storage failure is not an outcome of the call --
     * `docs/02_Architecture.md` section 18 is explicit that the call itself was
     * unaffected.
     */
    val storageErrors: SharedFlow<StorageError> = _storageErrors.asSharedFlow()

    fun publishStorageError(error: StorageError) {
        _storageErrors.tryEmit(error)
    }

    fun publish(state: ServiceState) {
        _state.value = state
        if (state == ServiceState.READY) _lastFailure.value = null
    }

    fun publishFailure(failure: LifecycleFailure) {
        _lastFailure.value = failure
    }
}
