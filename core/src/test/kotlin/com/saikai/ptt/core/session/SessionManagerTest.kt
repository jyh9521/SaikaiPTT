package com.saikai.ptt.core.session

import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.config.LoggingConfig
import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.domain.Peer
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.LogSink
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.SessionId
import com.saikai.ptt.core.protocol.TerminationReason
import com.saikai.ptt.core.session.SessionFixtures.peer
import com.saikai.ptt.core.session.SessionFixtures.voiceStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PTT state machine.
 *
 * Every timing path runs on virtual time, so the 150 ms retry and the 500 ms
 * give-up are asserted at the millisecond rather than waited out. The
 * concurrency test is the exception and runs on real threads, because a race
 * that only ever happens on one thread is not a race.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {

    private val config = SaikaiConfig()
    private val logger = Logger(
        LoggingConfig.debug(),
        object : LogSink {
            override fun write(
                level: LogLevel,
                category: LogCategory,
                message: String,
                throwable: Throwable?,
            ) = Unit
        },
    )

    private val signals = RecordingSignals()
    private val audio = FakeAudio()
    private var allowInterrupt = false
    private var localName: String? = "Me"

    private val outcomes = mutableListOf<SessionOutcome>()

    private fun TestScope.manager(): SessionManager {
        val manager = SessionManager(
            config = config,
            logger = logger,
            signals = signals,
            audio = audio,
            localName = { localName },
            allowInterrupt = { allowInterrupt },
            scope = backgroundScope,
            nowMillis = { testScheduler.currentTime },
        )
        backgroundScope.launch { manager.outcomes.collect { outcomes += it } }
        runCurrent()
        return manager
    }

    /**
     * The outcomes seen so far.
     *
     * Draining the scheduler first: outcomes are delivered to a collector on
     * another coroutine, and a test that read the list without letting it run
     * would be asserting on timing rather than on behaviour.
     */
    private fun TestScope.emitted(): List<SessionOutcome> {
        runCurrent()
        return outcomes
    }

    private val bob: Peer = peer("Bob")
    private val carol: Peer = peer("Carol", address = "192.168.1.21")

    // --- Sending -----------------------------------------------------------------

    @Test
    fun `pressing talk requests a session and starts capturing`() = runTest {
        val manager = manager()

        val started = manager.requestTalk(bob).valueOrNull()!!

        val state = manager.state.value as SessionState.Requesting
        assertEquals(started, state.sessionId)
        assertEquals(bob.deviceId, state.peer)
        assertTrue("capture must start before the peer answers", audio.capturing)
        assertTrue(manager.busy.value)
        assertEquals(1, signals.count("VOICE_START"))
    }

    @Test
    fun `only VOICE_ACCEPT begins a transmission`() = runTest {
        val manager = manager()
        val session = manager.requestTalk(bob).valueOrNull()!!

        manager.onVoiceAccept(bob.deviceId, session)

        assertTrue(manager.state.value is SessionState.Transmitting)
        assertEquals(session, (manager.state.value as SessionState.Active).sessionId)
    }

    @Test
    fun `an acceptance for another session or another peer is ignored`() = runTest {
        val manager = manager()
        manager.requestTalk(bob)

        manager.onVoiceAccept(bob.deviceId, SessionId.random())
        assertTrue(manager.state.value is SessionState.Requesting)

        manager.onVoiceAccept(carol.deviceId, (manager.state.value as SessionState.Active).sessionId)
        assertTrue(manager.state.value is SessionState.Requesting)
    }

    @Test
    fun `a refusal ends the attempt without a recording`() = runTest {
        val manager = manager()
        val session = (manager.requestTalk(bob) as Outcome.Success).value

        manager.onBusy(bob.deviceId, session)

        assertEquals(SessionState.Idle, manager.state.value)
        assertFalse(audio.capturing)
        assertFalse(manager.busy.value)
        assertEquals(
            SessionOutcome.SendFailed(bob.deviceId, "Bob", SendFailure.TARGET_BUSY),
            emitted().single(),
        )
    }

    @Test
    fun `silence is retried twice and then given up on at the deadline`() = runTest {
        val manager = manager()
        manager.requestTalk(bob)
        assertEquals(1, signals.count("VOICE_START"))

        advanceTimeBy(151)
        assertEquals(2, signals.count("VOICE_START"))

        advanceTimeBy(150)
        assertEquals(3, signals.count("VOICE_START"))

        // Still inside the window: nothing has failed yet.
        advanceTimeBy(150)
        assertEquals(3, signals.count("VOICE_START"))
        assertTrue(manager.state.value is SessionState.Requesting)

        advanceTimeBy(100)
        assertEquals(SessionState.Idle, manager.state.value)
        assertFalse(audio.capturing)
        assertEquals(
            SessionOutcome.SendFailed(bob.deviceId, "Bob", SendFailure.NO_RESPONSE),
            emitted().single(),
        )
    }

    @Test
    fun `an acceptance stops the retries`() = runTest {
        val manager = manager()
        val session = manager.requestTalk(bob).valueOrNull()!!
        manager.onVoiceAccept(bob.deviceId, session)

        advanceTimeBy(1_000)

        assertEquals(1, signals.count("VOICE_START"))
        assertTrue(manager.state.value is SessionState.Transmitting)
    }

    @Test
    fun `releasing while transmitting ends the session normally`() = runTest {
        val manager = manager()
        val session = manager.requestTalk(bob).valueOrNull()!!
        manager.onVoiceAccept(bob.deviceId, session)

        manager.release()

        assertEquals(1, signals.count("VOICE_END"))
        assertEquals(SessionState.Idle, manager.state.value)
        assertFalse(audio.capturing)
        assertTrue(emitted().single() is SessionOutcome.SendEnded)
    }

    @Test
    fun `releasing before the peer answers cancels quietly`() = runTest {
        val manager = manager()
        manager.requestTalk(bob)

        manager.release()

        assertEquals(0, signals.count("VOICE_END"))
        assertEquals(SessionState.Idle, manager.state.value)
        assertEquals(
            SessionOutcome.SendFailed(bob.deviceId, "Bob", SendFailure.CANCELLED),
            emitted().single(),
        )
    }

    @Test
    fun `talking twice at once is refused without sending anything`() = runTest {
        val manager = manager()
        manager.requestTalk(bob)

        val second = manager.requestTalk(carol)

        assertEquals(SendFailure.ALREADY_IN_SESSION, second.errorOrNull())
        assertEquals(1, signals.count("VOICE_START"))
    }

    @Test
    fun `an unavailable microphone fails before the peer is bothered`() = runTest {
        audio.microphoneAvailable = false
        val manager = manager()

        val outcome = manager.requestTalk(bob)

        assertEquals(SendFailure.MIC_UNAVAILABLE, outcome.errorOrNull())
        assertEquals(0, signals.count("VOICE_START"))
        assertEquals(SessionState.Idle, manager.state.value)
    }

    @Test
    fun `without a name there is no identity to transmit`() = runTest {
        localName = null
        val manager = manager()

        assertEquals(SendFailure.NO_LOCAL_NAME, manager.requestTalk(bob).errorOrNull())
        assertEquals(0, audio.captureStarts)
    }

    @Test
    fun `a send that cannot leave the device puts everything back`() = runTest {
        val failing = RecordingSignals(sendSucceeds = { false })
        val manager = SessionManager(
            config, logger, failing, audio, { localName }, { allowInterrupt },
            backgroundScope, { testScheduler.currentTime },
        )
        backgroundScope.launch { manager.outcomes.collect { outcomes += it } }
        runCurrent()

        assertEquals(SendFailure.UNREACHABLE, manager.requestTalk(bob).errorOrNull())
        assertEquals(SessionState.Idle, manager.state.value)
        assertFalse(audio.capturing)
    }

    @Test
    fun `being interrupted while transmitting is recorded as an interruption`() = runTest {
        val manager = manager()
        val session = manager.requestTalk(bob).valueOrNull()!!
        manager.onVoiceAccept(bob.deviceId, session)

        manager.onSessionTerminate(
            bob.deviceId, session, TerminationReason.INTERRUPTED_BY_PEER,
        )

        assertEquals(SessionState.Idle, manager.state.value)
        assertFalse(audio.capturing)
        val outcome = emitted().single() as SessionOutcome.SendInterrupted
        assertEquals(TerminationReason.INTERRUPTED_BY_PEER, outcome.reason)
    }

    // --- Receiving ----------------------------------------------------------------

    @Test
    fun `an idle device accepts and starts playing`() = runTest {
        val manager = manager()
        val session = SessionId.random()

        manager.onVoiceStart(bob.deviceId, session, voiceStart("Bob"), bob.endpoint)

        assertEquals(1, signals.count("VOICE_ACCEPT"))
        val state = manager.state.value as SessionState.Receiving
        assertEquals(session, state.sessionId)
        assertEquals("Bob", state.peerName)
        assertTrue(audio.playing)
        assertTrue(manager.busy.value)
    }

    @Test
    fun `the end of a transmission finishes the session`() = runTest {
        val manager = manager()
        val session = SessionId.random()
        manager.onVoiceStart(bob.deviceId, session, voiceStart("Bob"), bob.endpoint)

        manager.onVoiceEnd(bob.deviceId, session)

        assertEquals(SessionState.Idle, manager.state.value)
        assertFalse(audio.playing)
        assertTrue(emitted().single() is SessionOutcome.ReceiveEnded)
    }

    @Test
    fun `audio this build cannot decode is refused rather than resampled`() = runTest {
        val manager = manager()

        manager.onVoiceStart(
            bob.deviceId,
            SessionId.random(),
            voiceStart("Bob").copy(sampleRateHz = 48_000),
            bob.endpoint,
        )

        assertEquals(1, signals.count("BUSY"))
        assertEquals(0, signals.count("VOICE_ACCEPT"))
        assertEquals(SessionState.Idle, manager.state.value)
    }

    @Test
    fun `a call that cannot be played is refused rather than silently accepted`() = runTest {
        // A phone call holding the audio focus. Accepting would tell the speaker
        // they were heard while playing nothing.
        audio.speakerAvailable = false
        val manager = manager()

        manager.onVoiceStart(bob.deviceId, SessionId.random(), voiceStart("Bob"), bob.endpoint)

        assertEquals(1, signals.count("BUSY"))
        assertEquals(0, signals.count("VOICE_ACCEPT"))
        assertEquals(SessionState.Idle, manager.state.value)
    }

    @Test
    fun `a busy device refuses and keeps the session it has`() = runTest {
        val manager = manager()
        val first = SessionId.random()
        manager.onVoiceStart(bob.deviceId, first, voiceStart("Bob"), bob.endpoint)

        manager.onVoiceStart(carol.deviceId, SessionId.random(), voiceStart("Carol"), carol.endpoint)

        assertEquals(1, signals.count("BUSY"))
        assertEquals(1, signals.count("VOICE_ACCEPT"))
        assertEquals(first, (manager.state.value as SessionState.Active).sessionId)
        assertEquals("Bob", (manager.state.value as SessionState.Active).peerName)
    }

    @Test
    fun `a refusal names the session the caller asked about`() = runTest {
        // The caller's own id, echoed. It is what lets the caller tell this
        // answer from one left over from an attempt it has already abandoned,
        // and it discloses nothing: the session this device is really in never
        // goes on the wire.
        val manager = manager()
        val theirs = SessionId.random()
        manager.onVoiceStart(bob.deviceId, SessionId.random(), voiceStart("Bob"), bob.endpoint)

        manager.onVoiceStart(carol.deviceId, theirs, voiceStart("Carol"), carol.endpoint)

        assertTrue(
            "expected a BUSY naming $theirs, got ${signals.snapshot()}",
            signals.snapshot().contains("BUSY $theirs -> ${carol.deviceId}"),
        )
    }

    @Test
    fun `a refusal left over from an abandoned attempt does not fail the next one`() = runTest {
        // The first press timed out and was given up on. The peer's answer to
        // it arrives afterwards, while the user is part-way through a second
        // press -- and without the session id it would look exactly like an
        // answer to that one.
        val manager = manager()
        val abandoned = (manager.requestTalk(bob) as Outcome.Success).value
        advanceTimeBy(config.session.voiceStartTimeout.inWholeMilliseconds + 100)
        assertEquals(SessionState.Idle, manager.state.value)

        manager.requestTalk(bob)
        runCurrent()
        outcomes.clear()

        manager.onBusy(bob.deviceId, abandoned)

        assertTrue(manager.state.value is SessionState.Requesting)
        assertTrue("a stale refusal was acted on: ${emitted()}", emitted().isEmpty())
    }

    @Test
    fun `a resent request is accepted again, not refused`() = runTest {
        // The sender resends VOICE_START twice. Answering BUSY to the second
        // would turn one dropped VOICE_ACCEPT into a refused call.
        val manager = manager()
        val session = SessionId.random()
        manager.onVoiceStart(bob.deviceId, session, voiceStart("Bob"), bob.endpoint)

        manager.onVoiceStart(bob.deviceId, session, voiceStart("Bob"), bob.endpoint)

        assertEquals(2, signals.count("VOICE_ACCEPT"))
        assertEquals(0, signals.count("BUSY"))
    }

    @Test
    fun `with interruption allowed the old session is told before the new one starts`() = runTest {
        allowInterrupt = true
        val manager = manager()
        val first = SessionId.random()
        val second = SessionId.random()
        manager.onVoiceStart(bob.deviceId, first, voiceStart("Bob"), bob.endpoint)
        signals.sent.clear()
        outcomes.clear()

        manager.onVoiceStart(carol.deviceId, second, voiceStart("Carol"), carol.endpoint)

        // Order matters: the displaced peer is told, and only then is the new
        // one accepted. It finalises its recording as interrupted rather than
        // discovering the silence.
        assertEquals(
            listOf(
                "TERMINATE $first -> ${bob.deviceId} (INTERRUPTED_BY_PEER)",
                "VOICE_ACCEPT $second -> ${carol.deviceId}",
            ),
            signals.snapshot(),
        )
        assertEquals(second, (manager.state.value as SessionState.Active).sessionId)
        assertEquals("Carol", (manager.state.value as SessionState.Active).peerName)
        assertTrue(emitted().single() is SessionOutcome.ReceiveInterrupted)
    }

    @Test
    fun `a receiving session that goes quiet is ended`() = runTest {
        val manager = manager()
        val session = SessionId.random()
        manager.onVoiceStart(bob.deviceId, session, voiceStart("Bob"), bob.endpoint)

        advanceTimeBy(config.session.idleTimeout.inWholeMilliseconds + 1_000)

        assertEquals(SessionState.Idle, manager.state.value)
        assertFalse(audio.playing)
        assertEquals(1, signals.count("TERMINATE"))
        val outcome = emitted().single() as SessionOutcome.ReceiveInterrupted
        assertEquals(TerminationReason.TIMEOUT, outcome.reason)
    }

    @Test
    fun `frames keep a receiving session alive`() = runTest {
        val manager = manager()
        val session = SessionId.random()
        manager.onVoiceStart(bob.deviceId, session, voiceStart("Bob"), bob.endpoint)

        repeat(10) {
            advanceTimeBy(config.session.idleTimeout.inWholeMilliseconds / 2)
            manager.onVoiceFrame(session)
        }

        assertTrue(manager.state.value is SessionState.Receiving)
    }

    @Test
    fun `a transmission is not subject to the idle timeout`() = runTest {
        // Nothing comes back during a transmission -- there are no
        // acknowledgements by design -- so an idle timeout there would cut off
        // every message after three seconds.
        val manager = manager()
        val session = manager.requestTalk(bob).valueOrNull()!!
        manager.onVoiceAccept(bob.deviceId, session)

        advanceTimeBy(config.session.idleTimeout.inWholeMilliseconds * 3)

        assertTrue(manager.state.value is SessionState.Transmitting)
    }

    @Test
    fun `a stuck button is cut off at the maximum duration`() = runTest {
        val manager = manager()
        val session = manager.requestTalk(bob).valueOrNull()!!
        manager.onVoiceAccept(bob.deviceId, session)

        advanceTimeBy(config.session.maxDuration.inWholeMilliseconds + 1_000)

        assertEquals(SessionState.Idle, manager.state.value)
        assertEquals(1, signals.count("VOICE_END"))
        assertTrue(emitted().last() is SessionOutcome.SendInterrupted)
    }

    // --- Ending from this side ------------------------------------------------------

    @Test
    fun `the network going away ends a receiving session and tells the peer`() = runTest {
        val manager = manager()
        manager.onVoiceStart(bob.deviceId, SessionId.random(), voiceStart("Bob"), bob.endpoint)

        manager.terminate(TerminationReason.NETWORK_LOST)

        assertEquals(1, signals.count("TERMINATE"))
        assertEquals(SessionState.Idle, manager.state.value)
        assertFalse(audio.playing)
        assertTrue(emitted().single() is SessionOutcome.ReceiveInterrupted)
    }

    @Test
    fun `terminating when nothing is happening does nothing`() = runTest {
        val manager = manager()

        manager.terminate(TerminationReason.SERVICE_SHUTDOWN)

        assertTrue(signals.snapshot().isEmpty())
        assertTrue(emitted().isEmpty())
        assertNull(manager.state.value as? SessionState.Active)
    }
}
