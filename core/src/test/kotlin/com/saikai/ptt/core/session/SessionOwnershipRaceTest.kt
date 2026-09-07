package com.saikai.ptt.core.session

import com.saikai.ptt.core.config.LoggingConfig
import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.LogSink
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.SessionId
import com.saikai.ptt.core.session.SessionFixtures.peer
import com.saikai.ptt.core.session.SessionFixtures.voiceStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `docs/03_Protocol.md` section 34, on real threads.
 *
 * This is the one test in the session package that must not run on a single
 * thread: the whole point is that two VOICE_STARTs arriving at the same instant
 * cannot both find this device idle. A virtual-time dispatcher would serialise
 * them for free and the test would pass with the lock removed.
 *
 * The forbidden outcome is concrete: two people's voices playing at once.
 */
class SessionOwnershipRaceTest {

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var allowInterrupt = false

    private val manager = SessionManager(
        config = config,
        logger = logger,
        signals = signals,
        audio = FakeAudio(),
        localName = { "Me" },
        allowInterrupt = { allowInterrupt },
        scope = scope,
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun stormOfRequests(callers: Int) = runBlocking {
        val peers = List(callers) { peer("Caller$it", address = "10.0.0.$it") }
        val gate = CompletableDeferred<Unit>()

        coroutineScope {
            peers.forEach { caller ->
                launch(Dispatchers.Default) {
                    gate.await()
                    manager.onVoiceStart(
                        sender = caller.deviceId,
                        sessionId = SessionId.random(),
                        payload = voiceStart(caller.userName),
                        endpoint = caller.endpoint,
                    )
                }
            }
            gate.complete(Unit)
        }
    }

    @Test
    fun `only one of many simultaneous callers gets through`() {
        stormOfRequests(callers = 32)

        assertEquals("exactly one session may be accepted", 1, signals.count("VOICE_ACCEPT"))
        assertEquals(31, signals.count("BUSY"))
        assertTrue(manager.state.value is SessionState.Receiving)
    }

    @Test
    fun `the accepted caller is the one the device ends up listening to`() {
        stormOfRequests(callers = 32)

        val accepted = signals.snapshot().single { it.startsWith("VOICE_ACCEPT") }
        val active = manager.state.value as SessionState.Receiving
        assertTrue(
            "accepted '$accepted' but listening to ${active.sessionId} from ${active.peer}",
            accepted.contains(active.sessionId.value) && accepted.contains(active.peer.value),
        )
    }

    @Test
    fun `with interruption allowed there is still never more than one session`() {
        // Every caller may take the channel, but only by displacing whoever has
        // it -- so accepts and terminations stay in lockstep, and exactly one
        // session is left standing.
        allowInterrupt = true

        stormOfRequests(callers = 32)

        val accepts = signals.count("VOICE_ACCEPT")
        val terminates = signals.count("TERMINATE")
        assertEquals(32, accepts)
        assertEquals(
            "every accepted session but the last must have displaced exactly one other",
            accepts - 1,
            terminates,
        )
        assertEquals(0, signals.count("BUSY"))
        assertTrue(manager.state.value is SessionState.Receiving)
    }

    @Test
    fun `a session is never handed out twice in a row without being taken back`() {
        allowInterrupt = true
        stormOfRequests(callers = 32)

        // Read as a transcript, the log must alternate: nothing may be accepted
        // while another session is still open.
        var open = false
        for (entry in signals.snapshot()) {
            when {
                entry.startsWith("VOICE_ACCEPT") -> {
                    assertTrue("accepted while another session was open: $entry", !open)
                    open = true
                }

                entry.startsWith("TERMINATE") -> {
                    assertTrue("terminated with no session open: $entry", open)
                    open = false
                }
            }
        }
        assertTrue("the last session should still be open", open)
    }
}
