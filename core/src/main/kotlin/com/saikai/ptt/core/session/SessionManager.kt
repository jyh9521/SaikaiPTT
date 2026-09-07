package com.saikai.ptt.core.session

import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.domain.Peer
import com.saikai.ptt.core.domain.PeerEndpoint
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogFormat
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.AudioCodec
import com.saikai.ptt.core.protocol.SessionId
import com.saikai.ptt.core.protocol.TerminationReason
import com.saikai.ptt.core.protocol.VoiceStartPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The PTT session state machine, and the sole arbiter of who this device is
 * talking to.
 *
 * **One variable, one lock.** [SessionState] is the ownership variable of
 * `docs/03_Protocol.md` section 34, and every read and write of it happens
 * inside [mutex]. Critically, the *decision* and the *reply* happen in the same
 * critical section: two devices sending VOICE_START at the same instant must not
 * both see an idle receiver and both be told yes. Everything else in this class
 * follows from that one requirement.
 *
 * **Requesting is not transmitting.** The sending side goes IDLE to REQUESTING
 * on the button, and only VOICE_ACCEPT moves it on (section 19.5: without a
 * positive answer the state machine had no legal event to advance on at all).
 * Capture runs throughout, buffering, so the wait costs the user nothing.
 *
 * **The receiver decides.** Busy and force interrupt are both the callee's
 * policy (section 33). The caller only ever sends VOICE_START and finds out; it
 * never inspects the peer list to decide whether to bother, because that list is
 * up to a heartbeat old and the answer would be a guess.
 *
 * Every method here is safe to call from any thread, and the inbound handlers
 * are called from the control receive thread.
 */
class SessionManager(
    private val config: SaikaiConfig,
    private val logger: Logger,
    private val signals: SessionSignals,
    private val audio: VoiceAudio = NoVoiceAudio,
    private val localName: suspend () -> String?,
    private val allowInterrupt: suspend () -> Boolean,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private val mutex = Mutex()

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /**
     * Whether this device is in a session, in either direction.
     *
     * Announced to the whole network in every presence packet, because that byte
     * is the only way a third device learns it (`docs/03_Protocol.md` section
     * 12.2). Kept alongside the state rather than mapped from it so that it is
     * never a frame behind at the moment somebody is deciding whether to call.
     */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _outcomes = MutableSharedFlow<SessionOutcome>(
        replay = 0,
        extraBufferCapacity = 32,
    )

    /** How sessions finished. The history record and the UI message come from these. */
    val outcomes: SharedFlow<SessionOutcome> = _outcomes.asSharedFlow()

    private var timer: Job? = null
    private var lastInboundMillis: Long = 0L

    // --- Local control ------------------------------------------------------------

    /**
     * The talk button went down.
     *
     * Returns as soon as VOICE_START is on its way. Whether the peer agrees
     * arrives later, on [state] and [outcomes].
     *
     * The split is deliberate and total: a failure this device can determine on
     * its own is returned here and never emitted, and a failure that depends on
     * the peer is emitted and never returned. Reporting one both ways would have
     * every caller either double-handling it or picking a side.
     */
    suspend fun requestTalk(peer: Peer): Outcome<SessionId, SendFailure> {
        mutex.withLock {
            if (_state.value !is SessionState.Idle) {
                return Outcome.failure(SendFailure.ALREADY_IN_SESSION)
            }
            val name = localName()
            if (name.isNullOrBlank()) return Outcome.failure(SendFailure.NO_LOCAL_NAME)

            // Before anything is sent: a microphone that will not open is a
            // failure the peer never needs to hear about.
            if (!audio.startCapture()) return Outcome.failure(SendFailure.MIC_UNAVAILABLE)

            val sessionId = SessionId.random()
            val now = nowMillis()
            enter(
                SessionState.Requesting(
                    sessionId = sessionId,
                    peer = peer.deviceId,
                    peerName = peer.userName,
                    endpoint = peer.endpoint,
                    startedAtMillis = now,
                )
            )

            if (!signals.voiceStart(sessionId, peer.deviceId, peer.endpoint, name)) {
                audio.stopCapture()
                enter(SessionState.Idle)
                return Outcome.failure(SendFailure.UNREACHABLE)
            }

            startRequestTimer(sessionId, name)
            return Outcome.success(sessionId)
        }
    }

    /** The talk button came up. */
    suspend fun release() {
        mutex.withLock {
            when (val current = _state.value) {
                is SessionState.Requesting -> {
                    // Let go before the peer answered. Nothing was transmitted,
                    // so there is nothing to record.
                    audio.stopCapture()
                    finish(SessionOutcome.SendFailed(
                        current.peer,
                        current.peerName,
                        SendFailure.CANCELLED,
                    ))
                }

                is SessionState.Transmitting -> {
                    signals.voiceEnd(
                        sessionId = current.sessionId,
                        target = current.peer,
                        endpoint = current.endpoint,
                        // Filled in by the sender pipeline in Task24; the machine
                        // itself counts no frames.
                        finalDataSequence = 0,
                        frameCount = 0,
                    )
                    audio.stopCapture()
                    finish(SessionOutcome.SendEnded(
                        sessionId = current.sessionId,
                        peer = current.peer,
                        peerName = current.peerName,
                        startedAtMillis = current.startedAtMillis,
                        endedAtMillis = nowMillis(),
                    ))
                }

                else -> Unit
            }
        }
    }

    /**
     * Ends whatever is happening, from this side, for a reason that is not the
     * user's doing -- the network going away, or the service stopping.
     */
    suspend fun terminate(reason: TerminationReason) {
        mutex.withLock {
            val current = _state.value as? SessionState.Active ?: return
            if (current is SessionState.Receiving) {
                signals.sessionTerminate(
                    current.sessionId, current.peer, current.endpoint, reason,
                )
                audio.stopPlayback()
                finish(interrupted(current, reason))
            } else {
                audio.stopCapture()
                if (current is SessionState.Transmitting) {
                    signals.voiceEnd(
                        current.sessionId, current.peer, current.endpoint, 0, 0,
                    )
                }
                finish(interrupted(current, reason))
            }
        }
    }

    // --- Inbound ---------------------------------------------------------------------

    /**
     * A peer wants to talk to this device. The arbitration of section 34.
     *
     * The whole decision -- read the current session, choose, reply -- is one
     * critical section. Two VOICE_STARTs arriving at once therefore cannot both
     * find this device idle, which is the failure the section exists to forbid:
     * two voices playing at the same time.
     */
    suspend fun onVoiceStart(
        sender: DeviceId,
        sessionId: SessionId,
        payload: VoiceStartPayload,
        endpoint: PeerEndpoint,
    ) {
        mutex.withLock {
            // Section 19.2 step 3: refuse parameters this build cannot decode
            // rather than resampling. A wrong-rate stream is worse than a
            // refusal, because it sounds like a broken radio instead of a busy one.
            if (!supported(payload)) {
                logger.w(LogCategory.SESSION) {
                    "refusing ${LogFormat.userName(payload.userName)}: " +
                        "${payload.codec} ${payload.sampleRateHz}Hz ${payload.frameMillis}ms"
                }
                signals.busy(sender, endpoint)
                return
            }

            when (val current = _state.value) {
                is SessionState.Idle -> acceptIncoming(sender, sessionId, payload, endpoint)

                is SessionState.Active -> when {
                    // A retransmission of the request we already accepted. The
                    // sender resends VOICE_START up to twice, so a lost
                    // VOICE_ACCEPT must produce another one -- answering BUSY
                    // here would turn one dropped packet into a refused call.
                    current.sessionId == sessionId && current.peer == sender ->
                        signals.voiceAccept(sessionId, sender, endpoint)

                    !allowInterrupt() -> {
                        logger.i(LogCategory.SESSION) {
                            "busy: refusing ${LogFormat.userName(payload.userName)}"
                        }
                        signals.busy(sender, endpoint)
                    }

                    else -> {
                        // Section 33: ownership moves in one step. The displaced
                        // peer is told rather than left to notice, so its
                        // recording is finalised as INTERRUPTED instead of
                        // trailing off.
                        logger.i(LogCategory.SESSION) {
                            "interrupting ${LogFormat.userName(current.peerName)} for " +
                                LogFormat.userName(payload.userName)
                        }
                        signals.sessionTerminate(
                            current.sessionId,
                            current.peer,
                            current.endpoint,
                            TerminationReason.INTERRUPTED_BY_PEER,
                        )
                        releaseAudio(current)
                        emit(interrupted(current, TerminationReason.INTERRUPTED_BY_PEER))
                        acceptIncoming(sender, sessionId, payload, endpoint)
                    }
                }
            }
        }
    }

    /** The peer agreed. The only event that starts a transmission. */
    suspend fun onVoiceAccept(sender: DeviceId, sessionId: SessionId) {
        mutex.withLock {
            val current = _state.value as? SessionState.Requesting ?: return
            if (current.sessionId != sessionId || current.peer != sender) return

            cancelTimer()
            enter(
                SessionState.Transmitting(
                    sessionId = current.sessionId,
                    peer = current.peer,
                    peerName = current.peerName,
                    endpoint = current.endpoint,
                    startedAtMillis = current.startedAtMillis,
                )
            )
            startDurationTimer(current.sessionId)
        }
    }

    /** The peer is in a call and does not allow interruption. */
    suspend fun onBusy(sender: DeviceId) {
        mutex.withLock {
            val current = _state.value as? SessionState.Requesting ?: return
            if (current.peer != sender) return
            cancelTimer()
            audio.stopCapture()
            finish(SessionOutcome.SendFailed(
                current.peer,
                current.peerName,
                SendFailure.TARGET_BUSY,
            ))
        }
    }

    /** The speaker stopped, normally. */
    suspend fun onVoiceEnd(sender: DeviceId, sessionId: SessionId) {
        mutex.withLock {
            val current = _state.value as? SessionState.Receiving ?: return
            if (current.sessionId != sessionId || current.peer != sender) return
            audio.stopPlayback()
            finish(SessionOutcome.ReceiveEnded(
                sessionId = current.sessionId,
                peer = current.peer,
                peerName = current.peerName,
                startedAtMillis = current.startedAtMillis,
                endedAtMillis = nowMillis(),
            ))
        }
    }

    /** The other side ended the session: a force interrupt, or its own shutdown. */
    suspend fun onSessionTerminate(
        sender: DeviceId,
        sessionId: SessionId,
        reason: TerminationReason,
    ) {
        mutex.withLock {
            val current = _state.value as? SessionState.Active ?: return
            if (current.sessionId != sessionId || current.peer != sender) return
            cancelTimer()
            releaseAudio(current)
            finish(interrupted(current, reason))
        }
    }

    /**
     * A voice frame arrived for the open session.
     *
     * Only refreshes liveness. Validation already established that the frame
     * belongs to this session and came from its speaker; what this adds is that
     * a receiver stops waiting when frames stop arriving, without a packet
     * saying so.
     */
    fun onVoiceFrame(sessionId: SessionId) {
        val current = _state.value
        if (current is SessionState.Receiving && current.sessionId == sessionId) {
            lastInboundMillis = nowMillis()
        }
    }

    // --- Internals ---------------------------------------------------------------------

    private fun supported(payload: VoiceStartPayload): Boolean =
        payload.codec == AudioCodec.OPUS &&
            payload.sampleRateHz == config.audio.sampleRateHz &&
            payload.frameMillis.toLong() == config.audio.frameDuration.inWholeMilliseconds

    /** Caller holds the lock. */
    private suspend fun acceptIncoming(
        sender: DeviceId,
        sessionId: SessionId,
        payload: VoiceStartPayload,
        endpoint: PeerEndpoint,
    ) {
        val now = nowMillis()
        if (!audio.startPlayback(sessionId)) {
            // Nothing to play it through -- a phone call has the audio focus, or
            // the output device will not open. Refusing is the honest answer:
            // accepting would tell the speaker they were heard while playing
            // silence.
            logger.w(LogCategory.SESSION) { "cannot open the speaker; refusing the session" }
            signals.busy(sender, endpoint)
            return
        }
        enter(
            SessionState.Receiving(
                sessionId = sessionId,
                peer = sender,
                // The name as it was when they pressed the button, not as it may
                // be by the time the history entry is read.
                peerName = payload.userName,
                endpoint = endpoint,
                startedAtMillis = now,
            )
        )
        lastInboundMillis = now
        signals.voiceAccept(sessionId, sender, endpoint)
        startIdleTimer(sessionId)
    }

    /** Caller holds the lock. */
    private fun enter(next: SessionState) {
        _state.value = next
        _busy.value = next !is SessionState.Idle
    }

    /** Caller holds the lock. Returns to idle and reports what happened. */
    private fun finish(outcome: SessionOutcome) {
        cancelTimer()
        enter(SessionState.Idle)
        emit(outcome)
    }

    private fun emit(outcome: SessionOutcome) {
        logger.i(LogCategory.SESSION) { "session outcome: $outcome" }
        _outcomes.tryEmit(outcome)
    }

    private suspend fun releaseAudio(current: SessionState.Active) {
        if (current.outgoing) audio.stopCapture() else audio.stopPlayback()
    }

    private fun interrupted(
        current: SessionState.Active,
        reason: TerminationReason,
    ): SessionOutcome = if (current.outgoing) {
        SessionOutcome.SendInterrupted(
            sessionId = current.sessionId,
            peer = current.peer,
            peerName = current.peerName,
            startedAtMillis = current.startedAtMillis,
            endedAtMillis = nowMillis(),
            reason = reason,
        )
    } else {
        SessionOutcome.ReceiveInterrupted(
            sessionId = current.sessionId,
            peer = current.peer,
            peerName = current.peerName,
            startedAtMillis = current.startedAtMillis,
            endedAtMillis = nowMillis(),
            reason = reason,
        )
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }

    /**
     * Resends VOICE_START, then gives up.
     *
     * The retry interval and the overall deadline are separate on purpose: the
     * config guarantees every retry fits inside the window, so the last wait is
     * whatever is left rather than another full interval, and the failure lands
     * at the deadline instead of past it.
     */
    private fun startRequestTimer(sessionId: SessionId, localName: String) {
        cancelTimer()
        val retryMillis = config.session.voiceStartRetry.inWholeMilliseconds
        val deadline = nowMillis() + config.session.voiceStartTimeout.inWholeMilliseconds

        timer = scope.launch {
            var attempts = 0
            while (true) {
                val remaining = deadline - nowMillis()
                delay(if (attempts < config.session.voiceStartMaxRetries) retryMillis else remaining)
                mutex.withLock {
                    val current = _state.value as? SessionState.Requesting ?: return@launch
                    if (current.sessionId != sessionId) return@launch

                    if (nowMillis() >= deadline) {
                        audio.stopCapture()
                        finish(SessionOutcome.SendFailed(
                            current.peer,
                            current.peerName,
                            SendFailure.NO_RESPONSE,
                        ))
                        return@launch
                    }

                    attempts++
                    signals.voiceStart(sessionId, current.peer, current.endpoint, localName)
                }
            }
        }
    }

    /**
     * Ends a receiving session that has gone quiet, and any session that has run
     * too long.
     *
     * The idle timeout is deliberately not applied to a transmission. Nothing
     * comes back during one -- there are no acknowledgements by design -- so a
     * sender that timed out on silence would cut off every transmission after
     * three seconds.
     */
    private fun startIdleTimer(sessionId: SessionId) {
        cancelTimer()
        val idleMillis = config.session.idleTimeout.inWholeMilliseconds
        val tick = (idleMillis / 4).coerceAtLeast(50L)
        timer = scope.launch {
            while (true) {
                delay(tick)
                mutex.withLock {
                    val current = _state.value as? SessionState.Receiving ?: return@launch
                    if (current.sessionId != sessionId) return@launch
                    val now = nowMillis()
                    val reason = when {
                        now - lastInboundMillis >= idleMillis -> TerminationReason.TIMEOUT
                        now - current.startedAtMillis >=
                            config.session.maxDuration.inWholeMilliseconds -> TerminationReason.TIMEOUT

                        else -> null
                    } ?: return@withLock

                    signals.sessionTerminate(
                        current.sessionId, current.peer, current.endpoint, reason,
                    )
                    audio.stopPlayback()
                    finish(interrupted(current, reason))
                    return@launch
                }
            }
        }
    }

    /** A transmission that runs past the maximum is a stuck button, not a speech. */
    private fun startDurationTimer(sessionId: SessionId) {
        cancelTimer()
        val maxMillis = config.session.maxDuration.inWholeMilliseconds
        val tick = (maxMillis / 20).coerceAtLeast(50L)
        timer = scope.launch {
            while (true) {
                delay(tick)
                mutex.withLock {
                    val current = _state.value as? SessionState.Transmitting ?: return@launch
                    if (current.sessionId != sessionId) return@launch
                    if (nowMillis() - current.startedAtMillis < maxMillis) return@withLock

                    signals.voiceEnd(
                        current.sessionId, current.peer, current.endpoint, 0, 0,
                    )
                    audio.stopCapture()
                    finish(interrupted(current, TerminationReason.TIMEOUT))
                    return@launch
                }
            }
        }
    }
}
