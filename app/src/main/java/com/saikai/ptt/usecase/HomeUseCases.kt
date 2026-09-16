package com.saikai.ptt.usecase

import android.content.Context
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.domain.LocalUser
import com.saikai.ptt.core.domain.LocalUserRepository
import com.saikai.ptt.core.domain.Peer
import com.saikai.ptt.core.protocol.SessionId
import com.saikai.ptt.core.session.SendFailure
import com.saikai.ptt.core.session.SessionOutcome
import com.saikai.ptt.core.session.SessionState
import com.saikai.ptt.service.CommunicationService
import com.saikai.ptt.service.PttGateway
import com.saikai.ptt.service.ServiceState
import com.saikai.ptt.service.ServiceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * What the Home screen can observe and do.
 *
 * Every one of these is a business action named as one, and none of them says
 * how it is carried out. That is the whole point of the layer: the ViewModel
 * above may not touch a socket, an audio device or a database
 * (`docs/02_Architecture.md` section 4.2), and below this line is exactly where
 * those live.
 *
 * Bundled into one object rather than injected one by one, because they are
 * always wanted together and a ViewModel with eight constructor parameters is
 * harder to read than the eight things it does.
 */
class HomeUseCases(
    val observePeers: ObservePeers,
    val observeServiceState: ObserveServiceState,
    val observeSession: ObserveSession,
    val observeOutcomes: ObserveOutcomes,
    val observeActiveUser: ObserveActiveUser,
    val startPtt: StartPtt,
    val stopPtt: StopPtt,
    val setServiceRunning: SetServiceRunning,
)

/**
 * The devices this one can hear, as they change.
 *
 * Reads the mirror rather than the registry: the registry belongs to the
 * service, and the screen has to work when there is no service to ask.
 */
class ObservePeers(private val status: ServiceStatus) {
    operator fun invoke(): Flow<List<Peer>> = status.peers
}

/** Whether the communication service is up, degraded, or not running. */
class ObserveServiceState(private val status: ServiceStatus) {
    operator fun invoke(): Flow<ServiceState> = status.state
}

/** What this device is doing about voice, right now. */
class ObserveSession(private val status: ServiceStatus) {
    operator fun invoke(): Flow<SessionState> = status.session
}

/**
 * How attempts finished.
 *
 * Separate from [ObserveSession] because a refusal is not a state: by the time
 * anything can look at it, the session is back to idle and the only record that
 * it was refused is this event (`docs/04_UI_UX.md` sections 21.2 and 21.3).
 */
class ObserveOutcomes(private val status: ServiceStatus) {
    operator fun invoke(): SharedFlow<SessionOutcome> = status.outcomes
}

/** The name this device transmits under, and null before one is chosen. */
class ObserveActiveUser(private val users: LocalUserRepository) {
    operator fun invoke(): Flow<LocalUser?> = users.activeUser
}

/**
 * The talk button went down.
 *
 * Returns as soon as the request is on its way. Whether the peer agrees arrives
 * later, through [ObserveSession] and [ObserveOutcomes] -- and the split is the
 * state machine's, not this layer's: a failure this device can determine alone
 * is returned here, one that depends on the peer never is.
 */
class StartPtt(private val ptt: PttGateway) {
    suspend operator fun invoke(peer: Peer): Outcome<SessionId, SendFailure> = ptt.press(peer)
}

/** The talk button came up. */
class StopPtt(private val ptt: PttGateway) {
    suspend operator fun invoke() = ptt.release()
}

/**
 * Starts or stops the communication service.
 *
 * Takes a Context because starting a service needs one, and it is the caller's
 * -- the screen's -- rather than one held here: `startForegroundService` is only
 * permitted from a context the platform considers foreground, which is a fact
 * about where the call comes from, not about this class.
 */
class SetServiceRunning {
    operator fun invoke(context: Context, running: Boolean) {
        if (running) CommunicationService.start(context) else CommunicationService.stop(context)
    }
}
