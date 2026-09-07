package com.saikai.ptt.service

import com.saikai.ptt.core.common.LifecycleStep
import com.saikai.ptt.core.config.LoggingConfig
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.LogSink
import com.saikai.ptt.core.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The start-up and shutdown sequence of `docs/02_Architecture.md` section 26.
 *
 * These are the paths a device will never exercise on demand -- a port that will
 * not bind, a lock that will not release, a stop arriving during a start -- and
 * the consequence of getting them wrong is a phone left holding a socket and a
 * notification with no way to get them back. Which is why the sequence is plain
 * Kotlin: all of it is reachable from a JVM test.
 */
class ServiceLifecycleTest {

    private val log = mutableListOf<String>()
    private val states = mutableListOf<ServiceState>()
    private val sink = object : LogSink {
        override fun write(
            level: LogLevel,
            category: LogCategory,
            message: String,
            throwable: Throwable?,
        ) = Unit
    }
    private val logger = Logger(LoggingConfig.debug(), sink)

    private inner class FakeStep(
        override val name: String,
        private val startFailure: Throwable? = null,
        private val stopFailure: Throwable? = null,
    ) : LifecycleStep {
        var startCount = 0
        var stopCount = 0

        override suspend fun start() {
            startCount++
            log += "start:$name"
            startFailure?.let { throw it }
        }

        override suspend fun stop() {
            stopCount++
            log += "stop:$name"
            stopFailure?.let { throw it }
        }
    }

    private fun lifecycleOf(vararg steps: LifecycleStep) =
        ServiceLifecycle(steps.toList(), logger) { states += it }

    // --- Starting -----------------------------------------------------------------

    @Test
    fun `steps start in the order they are declared`() = runBlocking {
        val lifecycle = lifecycleOf(FakeStep("a"), FakeStep("b"), FakeStep("c"))

        assertTrue(lifecycle.start().isSuccess)

        assertEquals(listOf("start:a", "start:b", "start:c"), log)
        assertEquals(ServiceState.READY, lifecycle.state.value)
        assertEquals(listOf(ServiceState.STARTING, ServiceState.READY), states)
    }

    @Test
    fun `starting when already ready does not restart anything`() = runBlocking {
        val first = FakeStep("a")
        val lifecycle = lifecycleOf(first)

        lifecycle.start()
        assertTrue(lifecycle.start().isSuccess)

        assertEquals(1, first.startCount)
        assertEquals(ServiceState.READY, lifecycle.state.value)
    }

    // --- Rolling back --------------------------------------------------------------

    @Test
    fun `a failing step rolls back everything before it, in reverse`() = runBlocking {
        val lifecycle = lifecycleOf(
            FakeStep("a"),
            FakeStep("b"),
            FakeStep("c", startFailure = IllegalStateException("no port")),
            FakeStep("d"),
        )

        val failure = lifecycle.start().errorOrNull()!!

        assertEquals("c", failure.step)
        assertEquals("no port", failure.cause.message)
        assertEquals(ServiceState.FAILED, lifecycle.state.value)
        assertEquals(
            listOf("start:a", "start:b", "start:c", "stop:c", "stop:b", "stop:a"),
            log,
        )
    }

    @Test
    fun `the step that failed is given a chance to release what it did acquire`() = runBlocking {
        // A step that takes two resources and fails on the second is the reason
        // this matters: nothing else knows what it got as far as.
        val failing = FakeStep("half-acquired", startFailure = IllegalStateException("second"))
        lifecycleOf(FakeStep("a"), failing).start()

        assertEquals(1, failing.stopCount)
    }

    @Test
    fun `a step after the failure is never started`() = runBlocking {
        val later = FakeStep("later")
        lifecycleOf(FakeStep("boom", startFailure = RuntimeException()), later).start()

        assertEquals(0, later.startCount)
        assertEquals(0, later.stopCount)
    }

    @Test
    fun `a release that throws does not strand the steps behind it`() = runBlocking {
        val first = FakeStep("a")
        val lifecycle = lifecycleOf(
            first,
            FakeStep("b", stopFailure = IllegalStateException("release failed")),
            FakeStep("c", startFailure = IllegalStateException("no port")),
        )

        lifecycle.start()

        assertEquals(1, first.stopCount)
        assertEquals(ServiceState.FAILED, lifecycle.state.value)
    }

    @Test
    fun `a start can be retried after a failure`() = runBlocking {
        val fine = FakeStep("a")
        val lifecycle = ServiceLifecycle(
            listOf(fine, FlakyStep()),
            logger,
        ) { states += it }

        assertNull(lifecycle.start().valueOrNull())
        assertTrue(lifecycle.start().isSuccess)

        assertEquals(ServiceState.READY, lifecycle.state.value)
        assertEquals(2, fine.startCount)
        assertEquals(1, fine.stopCount)
    }

    private class FlakyStep : LifecycleStep {
        private var attempts = 0
        override val name: String = "flaky"
        override suspend fun start() {
            if (attempts++ == 0) throw IllegalStateException("not this time")
        }

        override suspend fun stop() = Unit
    }

    // --- Stopping -------------------------------------------------------------------

    @Test
    fun `stopping releases in reverse order`() = runBlocking {
        val lifecycle = lifecycleOf(FakeStep("a"), FakeStep("b"), FakeStep("c"))
        lifecycle.start()
        log.clear()

        lifecycle.stop()

        assertEquals(listOf("stop:c", "stop:b", "stop:a"), log)
        assertEquals(ServiceState.STOPPED, lifecycle.state.value)
    }

    @Test
    fun `stopping twice releases once and does not throw`() = runBlocking {
        val step = FakeStep("a")
        val lifecycle = lifecycleOf(step)
        lifecycle.start()

        lifecycle.stop()
        lifecycle.stop()
        lifecycle.stop()

        assertEquals(1, step.stopCount)
        assertEquals(ServiceState.STOPPED, lifecycle.state.value)
    }

    @Test
    fun `stopping without ever starting does nothing`() = runBlocking {
        val step = FakeStep("a")
        lifecycleOf(step).stop()

        assertEquals(0, step.stopCount)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `stopping after a failed start does not release twice`() = runBlocking {
        val first = FakeStep("a")
        val lifecycle = lifecycleOf(first, FakeStep("b", startFailure = RuntimeException()))

        lifecycle.start()
        lifecycle.stop()

        assertEquals(1, first.stopCount)
        assertEquals(ServiceState.STOPPED, lifecycle.state.value)
    }

    @Test
    fun `a release that throws during stop does not stop the rest`() = runBlocking {
        val first = FakeStep("a")
        val lifecycle = lifecycleOf(first, FakeStep("b", stopFailure = RuntimeException()))
        lifecycle.start()

        lifecycle.stop()

        assertEquals(1, first.stopCount)
        assertEquals(ServiceState.STOPPED, lifecycle.state.value)
    }

    // --- Releasing and restoring part of the sequence ---------------------------------

    @Test
    fun `releasing from a step leaves everything before it running`() = runBlocking {
        val a = FakeStep("a")
        val b = FakeStep("b")
        val c = FakeStep("c")
        val lifecycle = lifecycleOf(a, b, c)
        lifecycle.start()
        log.clear()

        lifecycle.releaseFrom("b")

        assertEquals(listOf("stop:c", "stop:b"), log)
        assertEquals(0, a.stopCount)
        assertEquals(ServiceState.DEGRADED, lifecycle.state.value)
    }

    @Test
    fun `restoring starts exactly what was released, in order`() = runBlocking {
        val a = FakeStep("a")
        val b = FakeStep("b")
        val c = FakeStep("c")
        val lifecycle = lifecycleOf(a, b, c)
        lifecycle.start()
        lifecycle.releaseFrom("b")
        log.clear()

        assertTrue(lifecycle.restore().isSuccess)

        assertEquals(listOf("start:b", "start:c"), log)
        assertEquals(1, a.startCount)
        assertEquals(2, b.startCount)
        assertEquals(ServiceState.READY, lifecycle.state.value)
    }

    @Test
    fun `a network drop and recovery can repeat without stranding a step`() = runBlocking {
        val prefix = FakeStep("prefix")
        val cycled = FakeStep("cycled")
        val lifecycle = lifecycleOf(prefix, cycled)
        lifecycle.start()

        repeat(5) {
            lifecycle.releaseFrom("cycled")
            lifecycle.restore()
        }

        assertEquals(6, cycled.startCount)
        assertEquals(5, cycled.stopCount)
        assertEquals(1, prefix.startCount)
        assertEquals(0, prefix.stopCount)
        assertEquals(ServiceState.READY, lifecycle.state.value)

        lifecycle.stop()
        assertEquals(6, cycled.stopCount)
        assertEquals(1, prefix.stopCount)
    }

    @Test
    fun `releasing from an unknown step changes nothing`() = runBlocking {
        val step = FakeStep("a")
        val lifecycle = lifecycleOf(step)
        lifecycle.start()

        lifecycle.releaseFrom("not-a-step")

        assertEquals(0, step.stopCount)
        assertEquals(ServiceState.READY, lifecycle.state.value)
    }

    @Test
    fun `releasing twice from the same step does not release it twice`() = runBlocking {
        val step = FakeStep("a")
        val lifecycle = lifecycleOf(FakeStep("prefix"), step)
        lifecycle.start()

        lifecycle.releaseFrom("a")
        lifecycle.releaseFrom("a")

        assertEquals(1, step.stopCount)
    }

    @Test
    fun `stopping from degraded releases what is left`() = runBlocking {
        val prefix = FakeStep("prefix")
        val lifecycle = lifecycleOf(prefix, FakeStep("cycled"))
        lifecycle.start()
        lifecycle.releaseFrom("cycled")

        lifecycle.stop()

        assertEquals(1, prefix.stopCount)
        assertEquals(ServiceState.STOPPED, lifecycle.state.value)
    }

    @Test
    fun `restoring when nothing was released is a no-op`() = runBlocking {
        val step = FakeStep("a")
        val lifecycle = lifecycleOf(step)
        lifecycle.start()

        assertTrue(lifecycle.restore().isSuccess)

        assertEquals(1, step.startCount)
    }

    // --- Cancellation -------------------------------------------------------------------

    @Test
    fun `cancellation during a start releases what was acquired and propagates`() {
        val first = FakeStep("a")
        val lifecycle = lifecycleOf(
            first,
            FakeStep("b", startFailure = CancellationException("caller gave up")),
        )

        assertThrows(CancellationException::class.java) {
            runBlocking { lifecycle.start() }
        }

        assertEquals(1, first.stopCount)
        assertEquals(ServiceState.STOPPED, lifecycle.state.value)
    }

    @Test
    fun `state is reported on every transition`() = runBlocking {
        val lifecycle = lifecycleOf(FakeStep("a"))

        lifecycle.start()
        lifecycle.stop()

        assertEquals(
            listOf(
                ServiceState.STARTING,
                ServiceState.READY,
                ServiceState.STOPPING,
                ServiceState.STOPPED,
            ),
            states,
        )
    }
}
