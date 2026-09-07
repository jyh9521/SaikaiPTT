package com.saikai.ptt.service

import com.saikai.ptt.core.domain.Peer
import kotlinx.coroutines.flow.MutableStateFlow
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

    fun publishPeers(peers: List<Peer>) {
        _peers.value = peers
    }

    fun publish(state: ServiceState) {
        _state.value = state
        if (state == ServiceState.READY) _lastFailure.value = null
    }

    fun publishFailure(failure: LifecycleFailure) {
        _lastFailure.value = failure
    }
}
