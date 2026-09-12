package com.saikai.ptt.service

import android.app.Service
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.domain.Peer
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.SessionId
import com.saikai.ptt.core.session.SendFailure
import com.saikai.ptt.core.session.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The talk button, as everything below the UI sees it.
 *
 * Two things happen here that the state machine must not know about, and one
 * that it must not be asked to do twice.
 *
 * The platform rule comes first: Android 14 will not let a foreground service
 * take the `microphone` type from the background, so transmitting requires a
 * visible screen while receiving does not (ADR-005 sections 2 and 3). This asks
 * whether the app is visible and then promotes the service, in that order,
 * *before* the machine is told anything -- so a refusal costs nothing and the
 * microphone is never opened by a request that was going to be refused anyway.
 *
 * Dropping back to the resident type is deliberately not done here. It belongs
 * to whatever watches the session state, because "the user let go" is only one
 * of the ways a transmission ends.
 *
 * ### Both methods leave the caller's thread, and finish
 *
 * Nothing below this suspends. `SessionSignals` is called while the mutex that
 * arbitrates session ownership is held, so its implementations must not -- and
 * the test suite asserts they do not. The consequence is that the whole chain
 * runs on whichever thread called it, and one link in that chain is a datagram
 * send. A talk button is pressed on the main thread, and Android kills a process
 * that touches a socket there.
 *
 * So the dispatcher is imposed here rather than at the call site. This is the
 * one door the UI comes through -- the debug screen today, the real button in
 * Task32, the overlay in Task36 -- and leaving it to each of them would be
 * leaving the same trap set three times.
 *
 * [NonCancellable] as well, and not as a precaution. A press cancelled partway
 * has already opened the microphone and not yet entered a session, and nothing
 * will ever close it; a cancelled release leaves the microphone open and the
 * peer waiting for audio that has stopped. Both are reachable, because a
 * `LaunchedEffect` is cancelled whenever its keys change or its composable
 * leaves. Neither call contains an unbounded wait, so running them to completion
 * costs milliseconds.
 */
class PttController(
    private val service: Service,
    private val notifications: ServiceNotifications,
    private val sessions: SessionManager,
    private val visibility: () -> Boolean,
    private val logger: Logger,
) {

    /** The talk button went down. */
    suspend fun press(peer: Peer): Outcome<SessionId, SendFailure> =
        withContext(Dispatchers.Default + NonCancellable) { pressOffTheCallersThread(peer) }

    /** The talk button came up. */
    suspend fun release() {
        withContext(Dispatchers.Default + NonCancellable) { sessions.release() }
    }

    private suspend fun pressOffTheCallersThread(
        peer: Peer,
    ): Outcome<SessionId, SendFailure> {
        if (!visibility()) {
            logger.i(LogCategory.SESSION) { "not transmitting: no screen is showing" }
            return Outcome.failure(SendFailure.APP_NOT_VISIBLE)
        }
        if (!notifications.promoteToMicrophone(service)) {
            // Visible a moment ago, refused now. The window is real -- the app
            // can be backgrounded between the two -- and it is the platform's
            // answer that counts.
            logger.w(LogCategory.SESSION) { "the platform refused the microphone service type" }
            return Outcome.failure(SendFailure.APP_NOT_VISIBLE)
        }

        val outcome = sessions.requestTalk(peer)
        if (outcome is Outcome.Failure) {
            // Nothing was started, so nothing will come through the state
            // machine to undo the promotion.
            notifications.demoteFromMicrophone(service)
        }
        return outcome
    }
}

/**
 * Where the UI finds the talk button when there is a service to talk to.
 *
 * Application scope, holding a service-scope object, for the same reason
 * [ServiceStatus] exists: the screen can open when no service is running, and
 * "there is nothing to press yet" has to be an answer rather than a crash. The
 * service attaches itself once it is up and detaches on the way down.
 *
 * A holder rather than a bound service. Binding would make every talk button
 * depend on a live connection, and the case that matters -- the process was
 * killed and the user has just reopened the app -- is exactly the case where
 * there is nothing to bind to.
 */
class PttGateway {

    @Volatile
    private var controller: PttController? = null

    /** True when a running service is behind this. */
    val isAvailable: Boolean get() = controller != null

    fun attach(controller: PttController) {
        this.controller = controller
    }

    fun detach() {
        controller = null
    }

    suspend fun press(peer: Peer): Outcome<SessionId, SendFailure> =
        controller?.press(peer) ?: Outcome.failure(SendFailure.UNREACHABLE)

    suspend fun release() {
        controller?.release()
    }
}
