package com.saikai.ptt.service

import com.saikai.ptt.core.common.LifecycleStep
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the communication service is doing. */
enum class ServiceState {
    STOPPED,
    STARTING,
    READY,
    STOPPING,

    /**
     * Running, but the network half is released.
     *
     * The service still holds the foreground notification and the multicast
     * lock; the sockets, discovery and heartbeat are down because there is no
     * network to run them on (`docs/03_Protocol.md` section 41). Saying READY
     * here would be a lie, and saying STOPPED would invite a restart that is
     * not needed.
     */
    DEGRADED,

    /** A start step failed. Everything that had started was rolled back. */
    FAILED,
}

/** Which step failed to start, and why. */
data class LifecycleFailure(
    val step: String,
    val cause: Throwable,
)

/**
 * Runs the service start-up and shutdown sequences.
 *
 * `docs/02_Architecture.md` section 26 requires three things of this, and each
 * one is a bug that would otherwise be found on a user's phone:
 *
 * - **Every completed step is rolled back when a later one fails.** A device
 *   that cannot bind its ports must not be left holding a MulticastLock and a
 *   foreground notification for the rest of the day.
 * - **Stopping is idempotent.** `onDestroy` can follow an explicit stop, and a
 *   start that failed has already released everything; neither may throw.
 * - **Rollback finishes.** One step whose release throws must not strand the
 *   ones behind it, so every release is attempted and failures are logged
 *   rather than propagated.
 *
 * Pure Kotlin with no Android types, so all of that is provable in a JVM test --
 * which matters, because the failure paths are the ones a device will never
 * exercise on demand.
 */
class ServiceLifecycle(
    private val steps: List<LifecycleStep>,
    private val logger: Logger,
    /**
     * Called synchronously on every transition.
     *
     * A callback rather than a collector on [state]: the service has to report
     * FAILED before it stops itself, and a collector running on another
     * coroutine would sometimes get there after the process had moved on.
     */
    private val onState: (ServiceState) -> Unit = {},
) {

    private val mutex = Mutex()
    private val started = mutableListOf<LifecycleStep>()
    private val _state = MutableStateFlow(ServiceState.STOPPED)

    val state: StateFlow<ServiceState> = _state.asStateFlow()

    /**
     * Starts every step in order.
     *
     * Calling this when already [ServiceState.READY] succeeds without doing
     * anything: `onStartCommand` is called again for every `startService`, and
     * the second one must not restart the sockets underneath a live call.
     */
    suspend fun start(): Outcome<Unit, LifecycleFailure> = mutex.withLock {
        if (_state.value == ServiceState.READY) return Outcome.success(Unit)
        transition(ServiceState.STARTING)
        return startFrom(0)
    }

    /**
     * Releases the steps from [fromStep] onward, leaving the earlier ones running.
     *
     * What network loss does (`docs/03_Protocol.md` section 41): the sockets and
     * everything above them go, the foreground notification and the multicast
     * lock stay. Expressed as a suffix of the same ordered list rather than as a
     * second teardown routine, so that a task which inserts a step gets it torn
     * down here too without knowing this method exists.
     */
    suspend fun releaseFrom(fromStep: String): Unit = mutex.withLock {
        if (_state.value != ServiceState.READY && _state.value != ServiceState.DEGRADED) return
        val index = started.indexOfFirst { it.name == fromStep }
        if (index < 0 || index >= started.size) return
        release(index)
        transition(ServiceState.DEGRADED)
        logger.i(LogCategory.SERVICE) { "released everything from $fromStep onward" }
    }

    /** Starts whatever [releaseFrom] released, in the original order. */
    suspend fun restore(): Outcome<Unit, LifecycleFailure> = mutex.withLock {
        if (_state.value != ServiceState.DEGRADED) return Outcome.success(Unit)
        transition(ServiceState.STARTING)
        return startFrom(started.size)
    }

    private suspend fun startFrom(index: Int): Outcome<Unit, LifecycleFailure> {
        for (position in index until steps.size) {
            val step = steps[position]
            // Recorded before it runs, not after. A step that acquires two
            // things and fails on the second still has to be given the chance to
            // release the first, and only the step knows what it got that far.
            // The contract already requires stop() to tolerate a start that
            // never ran, which is what makes this safe.
            started += step
            try {
                step.start()
            } catch (cancellation: CancellationException) {
                // The caller is going away. Release what we hold, then let the
                // cancellation continue -- swallowing it would leave the service
                // half-started with nobody waiting to finish it.
                release(0)
                transition(ServiceState.STOPPED)
                throw cancellation
            } catch (error: Throwable) {
                logger.e(LogCategory.SERVICE, error) { "service start failed at ${step.name}" }
                release(0)
                transition(ServiceState.FAILED)
                return Outcome.failure(LifecycleFailure(step.name, error))
            }
        }

        transition(ServiceState.READY)
        logger.i(LogCategory.SERVICE) { "service ready" }
        return Outcome.success(Unit)
    }

    /**
     * Releases everything that started, in reverse order.
     *
     * Safe to call at any time, including when nothing ever started and when it
     * has already been called.
     */
    suspend fun stop(): Unit = mutex.withLock {
        if (_state.value == ServiceState.STOPPED) return
        transition(ServiceState.STOPPING)
        release(0)
        transition(ServiceState.STOPPED)
        logger.i(LogCategory.SERVICE) { "service stopped" }
    }

    private fun transition(next: ServiceState) {
        _state.value = next
        onState(next)
    }

    /** Releases started steps from [fromIndex] onward, last first. */
    private suspend fun release(fromIndex: Int) {
        for (position in started.indices.reversed()) {
            if (position < fromIndex) break
            val step = started[position]
            try {
                step.stop()
            } catch (cancellation: CancellationException) {
                // Do not abandon the remaining releases: this is the only chance
                // the sockets and the lock will get. Rethrown by the caller's own
                // cancellation once the list is drained.
                logger.w(LogCategory.SERVICE, cancellation) {
                    "cancelled while releasing ${step.name}; continuing"
                }
            } catch (error: Throwable) {
                logger.e(LogCategory.SERVICE, error) { "failed to release ${step.name}" }
            }
            started.removeAt(position)
        }
    }
}
