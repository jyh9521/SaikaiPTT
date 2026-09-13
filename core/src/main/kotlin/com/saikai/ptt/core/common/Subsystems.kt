package com.saikai.ptt.core.common

import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlin.coroutines.CoroutineContext

/**
 * The two things that keep one subsystem's failure from becoming the app's.
 *
 * Lives in `core.common` rather than beside the service, for the same reason
 * [LifecycleStep] does: the components that need it -- the transport, discovery,
 * presence, the session machine -- sit in layers the service may know about and
 * that must not know about the service.
 */

/**
 * A scope whose failures are logged rather than fatal.
 *
 * [SupervisorJob] alone is not enough, and the gap is easy to miss.
 * A supervisor stops a failing child from cancelling its siblings; it does
 * nothing about the exception itself, which goes to the thread's uncaught
 * handler -- and on Android that is the process. So a heartbeat loop that threw
 * once would take the whole application down, including the voice session that
 * had nothing to do with it. `docs/02_Architecture.md` and Task30 both require
 * the opposite: one subsystem failing must leave the rest running.
 *
 * A [CoroutineExceptionHandler] is what closes it. The failing coroutine still
 * dies -- there is nothing sensible to resume into -- but its siblings, and the
 * process, carry on. [repeatEvery] is for the loops that should not even die.
 *
 * @param name identifies the subsystem in the log. This is the only clue a
 *   report will carry about which part stopped working, so it is worth being
 *   specific.
 */
fun subsystemScope(
    name: String,
    logger: Logger,
    context: CoroutineContext = Dispatchers.Default,
): CoroutineScope {
    return CoroutineScope(SupervisorJob() + context + subsystemFailures(name, logger))
}

/**
 * The handler [subsystemScope] installs, for the components that have to build
 * their own scope because they keep a reference to its job.
 */
fun subsystemFailures(name: String, logger: Logger): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, error ->
        logger.e(LogCategory.SERVICE, error) {
            "$name failed and has stopped; the rest of the service is still running"
        }
    }

/**
 * Runs [work] every [intervalMillis], and keeps running it when it throws.
 *
 * For the periodic loops a device's presence on the network depends on. A
 * heartbeat loop that dies does not announce itself -- and after three missed
 * periods this device disappears from every peer list on the LAN while its
 * screen still says everything is fine. That is the product failing silently,
 * which is worse than failing loudly, so a throw here costs one period rather
 * than the loop.
 *
 * Cancellation is not a failure and is rethrown: it is how [stop] gets the loop
 * to end, and swallowing it would make a subsystem impossible to shut down.
 *
 * The delay comes first, deliberately. Every caller has just done the thing the
 * loop repeats -- announcing at start-up, evaluating on a state change -- and a
 * second one in the same breath tells nobody anything new.
 */
suspend fun repeatEvery(
    intervalMillis: Long,
    name: String,
    logger: Logger,
    work: suspend () -> Unit,
) {
    require(intervalMillis > 0) { "A repeating loop needs a positive interval" }
    while (true) {
        delay(intervalMillis)
        try {
            work()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            logger.throttled(LogLevel.ERROR, LogCategory.SERVICE, "loop-$name", error) {
                "$name threw; retrying in ${intervalMillis}ms"
            }
        }
    }
}
