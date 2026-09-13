package com.saikai.ptt.core.common

import com.saikai.ptt.core.RecordingSink
import com.saikai.ptt.core.config.LoggingConfig
import com.saikai.ptt.core.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor

/**
 * The isolation the service depends on.
 *
 * Both of these exist because of one Android fact: an exception that reaches a
 * coroutine's uncaught handler ends the process. A supervisor job does not
 * prevent that, and every subsystem in this app runs inside one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubsystemsTest {

    private val sink = RecordingSink()
    private val logger = Logger(LoggingConfig.debug(), sink)

    @Test
    fun `a failing coroutine leaves its siblings running`() = runTest {
        val scope = subsystemScope(
            name = "discovery",
            logger = logger,
            context = coroutineContext[ContinuationInterceptor]!!,
        )
        var survivorRan = 0

        scope.launch { error("discovery blew up") }
        scope.launch { survivorRan++ }
        runCurrent()

        assertEquals(1, survivorRan)
        assertTrue(
            "the failure should be logged, not silent: ${sink.entries}",
            sink.entries.any { it.contains("discovery failed") },
        )
    }

    @Test
    fun `a repeating loop survives what its work throws`() = runTest {
        var attempts = 0
        val job = launch {
            repeatEvery(1_000, "heartbeat", logger) {
                attempts++
                if (attempts == 2) error("one bad beat")
            }
        }

        advanceTimeBy(3_500)
        job.cancel()

        // Three periods elapsed, the second threw, and the third still ran.
        assertEquals(3, attempts)
        assertTrue(sink.entries.any { it.contains("heartbeat threw") })
    }

    @Test
    fun `a repeating loop stops when it is cancelled`() = runTest {
        var attempts = 0
        val job = launch {
            repeatEvery(1_000, "heartbeat", logger) { attempts++ }
        }

        advanceTimeBy(2_500)
        job.cancel()
        advanceTimeBy(10_000)

        assertEquals(2, attempts)
    }

    @Test
    fun `cancellation from inside the work is not swallowed`() = runTest {
        // Swallowing it would make a subsystem impossible to shut down: stop()
        // cancels the job, the loop catches it and carries on regardless.
        var attempts = 0
        val job = launch {
            repeatEvery(1_000, "heartbeat", logger) {
                attempts++
                throw CancellationException("stopping")
            }
        }

        advanceTimeBy(5_000)

        assertEquals(1, attempts)
        assertTrue(job.isCancelled || job.isCompleted)
    }
}
