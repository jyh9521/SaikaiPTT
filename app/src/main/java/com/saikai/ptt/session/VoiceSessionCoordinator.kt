package com.saikai.ptt.session

import com.saikai.ptt.core.common.LifecycleStep
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.TerminationReason
import com.saikai.ptt.core.session.SessionManager
import com.saikai.ptt.core.session.SessionState
import com.saikai.ptt.core.session.VoiceTransmitter
import com.saikai.ptt.network.InboundPacketRouter
import com.saikai.ptt.service.VoiceSessionPowerLocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The one place that watches the session and tells everything else.
 *
 * Four things need to know what the session is doing: the send pipeline (so it
 * knows when the peer accepted and can flush the audio captured while waiting),
 * the power locks (held only while a session exists, ADR-005 section 6), the
 * foreground service type (which carries `microphone` only while transmitting,
 * ADR-005 section 2), and the UI mirror.
 *
 * They subscribe to one collector rather than four. The reason is the number of
 * ways a session ends: the button coming up, the peer answering BUSY, no answer
 * at all, a force interrupt, WiFi disappearing, a phone call taking the audio
 * focus, the service stopping. Four subscriptions would be four chances for one
 * of those paths to be handled in three of them -- and the failure that leaves
 * behind is a wake lock, or a microphone indicator that never goes out.
 *
 * A [LifecycleStep], and the last one, so it is the first released: the session
 * has to be ended and its VOICE_END or SESSION_TERMINATE actually sent while the
 * sockets are still open.
 */
class VoiceSessionCoordinator(
    private val sessions: SessionManager,
    private val router: InboundPacketRouter,
    private val listener: SessionPacketListener,
    private val transmitter: VoiceTransmitter,
    private val powerLocks: VoiceSessionPowerLocks,
    private val onTransmittingEnded: () -> Unit,
    private val publishSession: (SessionState) -> Unit,
    private val logger: Logger,
    private val scope: CoroutineScope,
) : LifecycleStep {

    override val name: String = "voice-session"

    private var collector: Job? = null

    /** Whether the last observed state was one that holds the microphone. */
    private var transmitting = false

    override suspend fun start() {
        router.register(listener)
        collector = scope.launch {
            sessions.state.collect(::onState)
        }
        logger.i(LogCategory.SESSION) { "session machine wired to the transport" }
    }

    override suspend fun stop() {
        // Order matters throughout.
        // 1. No new requests: an incoming VOICE_START during shutdown would open
        //    a session nobody is left to end.
        router.unregister(listener)
        // 2. End what is open, while the collector is still running so that the
        //    pipeline, the locks and the service type are all told, and while the
        //    sockets are still open so the peer is told too.
        sessions.terminate(TerminationReason.SERVICE_SHUTDOWN)
        // 3. Now stop listening.
        collector?.cancel()
        collector = null
        // 4. And release unconditionally. Steps must tolerate a start that never
        //    ran, and a wake lock left behind by a failed start is the worst
        //    thing in this file.
        powerLocks.release()
        onTransmittingEnded()
        transmitting = false
        publishSession(SessionState.Idle)
    }

    private fun onState(state: SessionState) {
        transmitter.onSessionState(state)
        powerLocks.onSessionState(state)

        val nowTransmitting = state is SessionState.Requesting || state is SessionState.Transmitting
        if (transmitting && !nowTransmitting) onTransmittingEnded()
        transmitting = nowTransmitting

        publishSession(state)
    }
}
