package com.saikai.ptt.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.domain.Peer
import com.saikai.ptt.core.domain.PresenceState
import com.saikai.ptt.core.session.SendFailure
import com.saikai.ptt.core.session.SessionOutcome
import com.saikai.ptt.core.session.SessionState
import com.saikai.ptt.service.ServiceState
import com.saikai.ptt.usecase.HomeUseCases
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Home screen's state, and the only place its actions go.
 *
 * One direction, throughout: an action arrives here, goes to a use case, and
 * comes back as a new value on one of the flows below
 * (`docs/02_Architecture.md` section 28). Nothing on the screen writes state
 * of its own, and nothing here touches a socket, an audio device or storage --
 * [HomeUseCases] is the whole of what this can reach.
 *
 * ### Four flows, not one state
 *
 * A heartbeat arrives every five seconds from every device on the network, and
 * each one produces a new peer table. Behind a single `HomeUiState` that would
 * be a new value for the whole screen -- the user's name, the banner and the
 * talk button all recomposing because somebody across the room is still
 * switched on. So the screen's state is split by what changes it, and each flow
 * carries only what it describes.
 *
 * [peers] goes further and drops everything not on screen: a `Peer` carries a
 * `lastSeenMillis` that moves on every heartbeat, and mapping it away means an
 * ordinary heartbeat produces a list equal to the last one, which
 * `distinctUntilChanged` then swallows entirely. The list recomposes when
 * somebody arrives, leaves, or starts a call -- and not otherwise.
 */
class HomeViewModel(private val useCases: HomeUseCases) : ViewModel() {

    /**
     * The chosen target, held here rather than in the peer list.
     *
     * A device id, not a `Peer`: the peer record is replaced on every heartbeat
     * and holding one would mean holding a stale copy of everything except the
     * identity, which is the only part a selection is about.
     */
    private val selected = MutableStateFlow<DeviceId?>(null)

    private val message = MutableStateFlow<PttMessage?>(null)

    /**
     * The peer table, collected once and shared.
     *
     * Three things read it -- the list, the target's name, and the lookup a
     * press does to find an endpoint -- and each one collecting
     * `observePeers()` separately would be three collectors on the same
     * upstream for no gain. Sharing it also gives [currentPeer] something to
     * read that is a flow's value rather than a variable some operator happened
     * to have assigned.
     */
    private val peerTable: StateFlow<List<Peer>> = useCases.observePeers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val connection: StateFlow<ConnectionState> = useCases.observeServiceState()
        .map { it.toConnectionState() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.STOPPED)

    val header: StateFlow<HomeHeader> = combine(
        useCases.observeActiveUser().map { it?.displayName }.distinctUntilChanged(),
        connection,
    ) { name, state -> HomeHeader(name, state) }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            HomeHeader(null, ConnectionState.STOPPED),
        )

    val peers: StateFlow<PeerListState> = combine(
        peerTable,
        selected,
        connection,
    ) { peers, target, state -> peerList(peers, target, state) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeerListState.Searching)

    val target: StateFlow<TargetState> = combine(
        peerTable,
        selected,
    ) { peers, chosen ->
        TargetState(chosen, peers.firstOrNull { it.deviceId == chosen }?.userName)
    }
        // Without this every heartbeat would republish the same name.
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TargetState(null, null))

    val ptt: StateFlow<PttState> = combine(
        useCases.observeSession(),
        target,
        connection,
        message,
    ) { session, chosen, state, note -> pttState(session, chosen, state, note) }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PttState(PttPhase.UNAVAILABLE, null, null),
        )

    init {
        // Refusals never appear in the session state: by the time anything can
        // read it the session is back to idle, and the only record that the
        // peer said no is the outcome (sections 21.2 and 21.3).
        viewModelScope.launch {
            useCases.observeOutcomes().collect { outcome -> message.value = outcome.toMessage() }
        }
    }

    // --- Actions --------------------------------------------------------------------

    /**
     * The user picked a device to talk to.
     *
     * A busy device can be picked. The list says "in a call" from a heartbeat
     * that is up to a few hundred milliseconds old, the callee is the only one
     * who really knows, and refusing here would refuse calls that would have
     * connected (section 11.2).
     */
    fun select(deviceId: DeviceId) {
        selected.value = deviceId
        message.value = null
    }

    /** The talk button went down. */
    fun press() {
        val chosen = selected.value ?: return
        val peer = currentPeer(chosen)
        if (peer == null) {
            // Chosen a moment ago, timed out since. Saying so is the point:
            // silently doing nothing would look like the button is broken.
            message.value = PttMessage.UNREACHABLE
            return
        }
        message.value = null
        viewModelScope.launch {
            when (val outcome = useCases.startPtt(peer)) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> message.value = outcome.error.toMessage()
            }
        }
    }

    /** The talk button came up. */
    fun release() {
        viewModelScope.launch { useCases.stopPtt() }
    }

    fun dismissMessage() {
        message.value = null
    }

    // --- Internals --------------------------------------------------------------------

    /**
     * The peer record as it is now.
     *
     * Looked up at the moment of the press rather than held, because the
     * endpoint inside it is where the audio will be sent, and a selection made
     * a minute ago can be pointing at an address the peer has since left.
     *
     * Offline peers are excluded here as well as in the list: a peer can time
     * out between the last recomposition and the press, and the check that
     * matters is the one made at the moment the packet would be sent
     * (`docs/04_UI_UX.md` section 14, check 3). Busy peers are *not* excluded --
     * that is the callee's decision, not this device's (section 14.1).
     */
    private fun currentPeer(deviceId: DeviceId): Peer? =
        peerTable.value.firstOrNull { it.deviceId == deviceId && it.state.isReachable }

    private fun peerList(
        peers: List<Peer>,
        target: DeviceId?,
        connection: ConnectionState,
    ): PeerListState {
        if (connection == ConnectionState.NO_NETWORK) return PeerListState.Unavailable

        // Offline devices are not shown at all (section 11.1). They leave the
        // list by timing out, which is the same thing said from the other end.
        val rows = peers
            .filter { it.state != PresenceState.OFFLINE }
            .map { PeerRow(it.deviceId, it.userName, it.state, it.deviceId == target) }

        return if (rows.isEmpty()) PeerListState.Searching else PeerListState.Peers(rows)
    }

    private fun pttState(
        session: SessionState,
        target: TargetState,
        connection: ConnectionState,
        message: PttMessage?,
    ): PttState = when (session) {
        is SessionState.Requesting ->
            PttState(PttPhase.REQUESTING, session.peerName, null)

        is SessionState.Transmitting ->
            PttState(PttPhase.TRANSMITTING, session.peerName, null)

        is SessionState.Receiving ->
            PttState(PttPhase.RECEIVING, session.peerName, null)

        SessionState.Idle -> {
            val ready = connection == ConnectionState.READY && target.isChosen
            PttState(
                phase = if (ready) PttPhase.READY else PttPhase.UNAVAILABLE,
                peerName = target.userName,
                message = message,
            )
        }
    }

    class Factory(private val useCases: HomeUseCases) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(useCases) as T
    }
}

private fun ServiceState.toConnectionState(): ConnectionState = when (this) {
    ServiceState.STOPPED -> ConnectionState.STOPPED
    ServiceState.STARTING -> ConnectionState.STARTING
    ServiceState.STOPPING -> ConnectionState.STOPPING
    ServiceState.READY -> ConnectionState.READY
    // The service is up and the network is not. Section 23 wants that said at
    // the top of the screen, and it is the only degraded state a user can act
    // on -- by reconnecting to WiFi.
    ServiceState.DEGRADED -> ConnectionState.NO_NETWORK
    ServiceState.FAILED -> ConnectionState.FAILED
}

private fun SendFailure.toMessage(): PttMessage = when (this) {
    SendFailure.TARGET_BUSY -> PttMessage.TARGET_BUSY
    SendFailure.NO_RESPONSE -> PttMessage.NO_RESPONSE
    SendFailure.NO_LOCAL_NAME -> PttMessage.NO_NAME
    SendFailure.MIC_UNAVAILABLE -> PttMessage.NO_MICROPHONE
    SendFailure.APP_NOT_VISIBLE -> PttMessage.APP_NOT_VISIBLE
    SendFailure.ALREADY_IN_SESSION -> PttMessage.ALREADY_IN_SESSION
    SendFailure.UNREACHABLE, SendFailure.NETWORK_LOST -> PttMessage.UNREACHABLE
    // Letting go before the peer answered is the user's own doing. Telling them
    // about it would be telling them what they just did.
    SendFailure.CANCELLED -> PttMessage.UNREACHABLE
}

private fun SessionOutcome.toMessage(): PttMessage? = when (this) {
    is SessionOutcome.SendFailed ->
        if (reason == SendFailure.CANCELLED) null else reason.toMessage()

    is SessionOutcome.SendInterrupted -> PttMessage.INTERRUPTED

    // Finishing normally, in either direction, is not news.
    is SessionOutcome.SendEnded,
    is SessionOutcome.ReceiveEnded,
    is SessionOutcome.ReceiveInterrupted,
    -> null
}
