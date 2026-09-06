package com.saikai.ptt.core.logger

import com.saikai.ptt.core.config.LoggingConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * The application's logger.
 *
 * Three properties drive the design, and each of them shows up in the API.
 *
 * **A disabled call must cost nothing.** Logging is called from the UDP receive
 * threads and the audio path, 50 times a second per direction during a call.
 * Messages are therefore lambdas and the entry points are `inline`: when a level
 * or category is off, the string is never built and no lambda object is
 * allocated. `logger.d(NETWORK) { "seq=$seq from $peer" }` compiles to a boolean
 * test in release.
 *
 * **A packet flood must not become a log flood.** A malfunctioning or hostile
 * device on the LAN can send thousands of invalid packets a second
 * (`docs/03_Protocol.md` section 45). [throttled] emits at most one entry per
 * second per kind and counts the rest, so the log stays readable and the CPU
 * stays free.
 *
 * **Logging must never take down a call.** A sink that throws is swallowed:
 * losing a log line is always better than losing the audio path.
 *
 * Raw binary never goes in directly -- see [LogFormat].
 *
 * Safe to call from any thread.
 */
class Logger(
    private val config: LoggingConfig,
    private val sink: LogSink,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    // Indexed by category ordinal so the hot path does no allocation to look up
    // a throttle bucket. Categories are a fixed enum, so an array is enough.
    private val throttles: Array<ConcurrentHashMap<String, ThrottleState>> =
        Array(LogCategory.entries.size) { ConcurrentHashMap() }

    /**
     * Public because the `inline` entry points call it. Cheap: two comparisons.
     */
    fun isEnabled(level: LogLevel, category: LogCategory): Boolean =
        config.isEnabled(level, category)

    /**
     * Emits an already-built message. Public for the same reason as [isEnabled];
     * prefer the lambda-taking [d]/[i]/[w]/[e].
     */
    fun log(
        level: LogLevel,
        category: LogCategory,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (!isEnabled(level, category)) return
        emit(level, category, message, throwable)
    }

    /**
     * Reserves a throttle slot.
     *
     * Returns the number of entries suppressed since the last emission when this
     * call may log, or `null` when it is being suppressed. Public because
     * [throttled] is inline.
     */
    fun claimThrottleSlot(category: LogCategory, kind: String): Int? {
        // computeIfAbsent, not getOrPut: the latter is get-then-put and two
        // threads racing on the same kind would each get their own bucket,
        // briefly doubling the rate the throttle is supposed to cap.
        val state = throttles[category.ordinal].computeIfAbsent(kind) { ThrottleState() }
        val now = nowMillis()
        val minimumGapMillis = 1_000L / config.maxEntriesPerSecondPerKind.coerceAtLeast(1)
        synchronized(state) {
            // A "never emitted" flag rather than a sentinel timestamp: seeding
            // lastEmittedAt with Long.MIN_VALUE would overflow the subtraction,
            // and seeding it with 0 would suppress the first entry under any
            // clock that starts near zero -- which is every injected test clock.
            val due = !state.hasEmitted || now - state.lastEmittedAt >= minimumGapMillis
            return if (due) {
                val suppressed = state.suppressed
                state.hasEmitted = true
                state.lastEmittedAt = now
                state.suppressed = 0
                suppressed
            } else {
                state.suppressed++
                null
            }
        }
    }

    /** Public for [throttled]; appends the suppressed count when there is one. */
    fun logThrottled(
        level: LogLevel,
        category: LogCategory,
        message: String,
        suppressedSinceLast: Int,
        throwable: Throwable? = null,
    ) {
        val text =
            if (suppressedSinceLast > 0) "$message (+$suppressedSinceLast suppressed)" else message
        emit(level, category, text, throwable)
    }

    private fun emit(
        level: LogLevel,
        category: LogCategory,
        message: String,
        throwable: Throwable?,
    ) {
        try {
            sink.write(level, category, message, throwable)
        } catch (_: Throwable) {
            // Deliberately swallowed. A failing log destination must not
            // propagate into the audio or network path; losing a line is
            // always preferable to dropping a call.
        }
    }

    // --- Lambda entry points -------------------------------------------------
    // Inline so that a disabled call builds no string and allocates nothing.

    inline fun d(category: LogCategory, throwable: Throwable? = null, message: () -> String) {
        if (isEnabled(LogLevel.DEBUG, category)) {
            log(LogLevel.DEBUG, category, message(), throwable)
        }
    }

    inline fun i(category: LogCategory, throwable: Throwable? = null, message: () -> String) {
        if (isEnabled(LogLevel.INFO, category)) {
            log(LogLevel.INFO, category, message(), throwable)
        }
    }

    inline fun w(category: LogCategory, throwable: Throwable? = null, message: () -> String) {
        if (isEnabled(LogLevel.WARN, category)) {
            log(LogLevel.WARN, category, message(), throwable)
        }
    }

    inline fun e(category: LogCategory, throwable: Throwable? = null, message: () -> String) {
        if (isEnabled(LogLevel.ERROR, category)) {
            log(LogLevel.ERROR, category, message(), throwable)
        }
    }

    /**
     * Rate-limited logging for paths that can be triggered by a remote device.
     *
     * At most one entry per second per [kind]; the rest are counted and reported
     * with the next one that gets through. Use a small fixed set of kinds --
     * "bad-magic", "wrong-target" -- not per-packet values, or the throttle map
     * grows without bound.
     */
    inline fun throttled(
        level: LogLevel,
        category: LogCategory,
        kind: String,
        throwable: Throwable? = null,
        message: () -> String,
    ) {
        if (!isEnabled(level, category)) return
        val suppressed = claimThrottleSlot(category, kind) ?: return
        logThrottled(level, category, message(), suppressed, throwable)
    }

    private class ThrottleState {
        var hasEmitted: Boolean = false
        var lastEmittedAt: Long = 0L
        var suppressed: Int = 0
    }
}
