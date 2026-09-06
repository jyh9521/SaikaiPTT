package com.saikai.ptt.core.logger

import com.saikai.ptt.core.config.LoggingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggerTest {

    private class RecordingSink : LogSink {
        val entries = mutableListOf<String>()
        override fun write(
            level: LogLevel,
            category: LogCategory,
            message: String,
            throwable: Throwable?,
        ) {
            entries += "$level/$category: $message"
        }
    }

    private class FakeClock(var millis: Long = 0L) : () -> Long {
        override fun invoke(): Long = millis
    }

    private fun logger(
        config: LoggingConfig = LoggingConfig.debug(),
        sink: LogSink = RecordingSink(),
        clock: () -> Long = FakeClock(),
    ) = Logger(config, sink, clock)

    // --- Level and category filtering ---------------------------------------

    @Test
    fun `entries below the minimum level are dropped`() {
        val sink = RecordingSink()
        val log = logger(LoggingConfig(minLevel = LogLevel.WARN), sink)

        log.d(LogCategory.NETWORK) { "debug" }
        log.i(LogCategory.NETWORK) { "info" }
        log.w(LogCategory.NETWORK) { "warn" }
        log.e(LogCategory.NETWORK) { "error" }

        assertEquals(listOf("WARN/NETWORK: warn", "ERROR/NETWORK: error"), sink.entries)
    }

    @Test
    fun `entries in disabled categories are dropped`() {
        val sink = RecordingSink()
        val log = logger(
            LoggingConfig(enabledCategories = setOf(LogCategory.SERVICE)),
            sink,
        )

        log.i(LogCategory.AUDIO) { "audio" }
        log.i(LogCategory.SERVICE) { "service" }

        assertEquals(listOf("INFO/SERVICE: service"), sink.entries)
    }

    @Test
    fun `release configuration silences debug and the realtime categories`() {
        val sink = RecordingSink()
        val log = logger(LoggingConfig.release(), sink)

        log.d(LogCategory.SERVICE) { "debug is off in release" }
        log.i(LogCategory.PROTOCOL) { "per-packet" }
        log.i(LogCategory.AUDIO) { "per-frame" }
        log.i(LogCategory.SERVICE) { "kept" }
        log.e(LogCategory.NETWORK) { "errors always survive" }

        assertEquals(
            listOf("INFO/SERVICE: kept", "ERROR/NETWORK: errors always survive"),
            sink.entries,
        )
    }

    @Test
    fun `errors are never suppressed by category`() {
        // Categories cap volume, not diagnosis. NETWORK is off in release
        // because it logs per packet -- but a network error is rare and is
        // precisely what someone reading a field report needs.
        val sink = RecordingSink()
        val log = logger(LoggingConfig.release(), sink)

        LogCategory.entries.forEach { category ->
            log.e(category) { "failure in $category" }
        }

        assertEquals(
            "Every category must still be able to report an error",
            LogCategory.entries.size,
            sink.entries.size,
        )
    }

    @Test
    fun `the category bypass applies only to errors`() {
        // The bypass must stay narrow. A warning in a disabled category is
        // still volume -- the throttled invalid-packet path logs at WARN -- so
        // only ERROR is exempt.
        val sink = RecordingSink()
        val log = logger(LoggingConfig.release(), sink)

        log.w(LogCategory.NETWORK) { "warning in a disabled category" }
        log.e(LogCategory.NETWORK) { "error in a disabled category" }

        assertEquals(listOf("ERROR/NETWORK: error in a disabled category"), sink.entries)
    }

    // --- The performance requirement ----------------------------------------

    @Test
    fun `a disabled call never builds its message`() {
        // This is the requirement that shaped the API. Logging is called from
        // the UDP receive threads and the audio path 50 times a second per
        // direction; if a disabled call still formatted its string, release
        // builds would pay for logging they discard.
        var timesBuilt = 0
        val log = logger(LoggingConfig.release())

        repeat(1_000) {
            log.d(LogCategory.PROTOCOL) {
                timesBuilt++
                "expensive $it"
            }
        }

        assertEquals("The message lambda must not run when the call is disabled", 0, timesBuilt)
    }

    @Test
    fun `an enabled call does build its message`() {
        var timesBuilt = 0
        val sink = RecordingSink()
        val log = logger(LoggingConfig.debug(), sink)

        log.d(LogCategory.PROTOCOL) {
            timesBuilt++
            "built"
        }

        assertEquals(1, timesBuilt)
        assertEquals(listOf("DEBUG/PROTOCOL: built"), sink.entries)
    }

    // --- Throttling ----------------------------------------------------------

    @Test
    fun `a packet flood produces one entry per second, not one per packet`() {
        // docs/03_Protocol.md section 45: a malfunctioning peer can send
        // thousands of invalid packets a second. The log must not amplify that.
        val sink = RecordingSink()
        val clock = FakeClock()
        val log = logger(LoggingConfig.debug(), sink, clock)

        repeat(5_000) {
            log.throttled(LogLevel.WARN, LogCategory.PROTOCOL, "bad-magic") { "dropped a packet" }
        }

        assertEquals("Only the first of the burst should be emitted", 1, sink.entries.size)
    }

    @Test
    fun `the suppressed count is reported with the next entry`() {
        val sink = RecordingSink()
        val clock = FakeClock()
        val log = logger(LoggingConfig.debug(), sink, clock)

        repeat(43) {
            log.throttled(LogLevel.WARN, LogCategory.PROTOCOL, "bad-magic") { "dropped" }
        }
        clock.millis += 1_000
        log.throttled(LogLevel.WARN, LogCategory.PROTOCOL, "bad-magic") { "dropped" }

        assertEquals(2, sink.entries.size)
        assertEquals("WARN/PROTOCOL: dropped", sink.entries[0])
        assertTrue(
            "Suppression must be visible, or the log understates the problem: ${sink.entries[1]}",
            sink.entries[1].contains("+42 suppressed"),
        )
    }

    @Test
    fun `different kinds throttle independently`() {
        val sink = RecordingSink()
        val log = logger(LoggingConfig.debug(), sink, FakeClock())

        log.throttled(LogLevel.WARN, LogCategory.PROTOCOL, "bad-magic") { "a" }
        log.throttled(LogLevel.WARN, LogCategory.PROTOCOL, "wrong-target") { "b" }
        log.throttled(LogLevel.WARN, LogCategory.PROTOCOL, "bad-magic") { "a again" }

        assertEquals(2, sink.entries.size)
    }

    @Test
    fun `a throttled call in a disabled category never builds its message`() {
        var timesBuilt = 0
        val log = logger(LoggingConfig.release())

        repeat(1_000) {
            log.throttled(LogLevel.WARN, LogCategory.PROTOCOL, "bad-magic") {
                timesBuilt++
                "dropped"
            }
        }

        assertEquals(0, timesBuilt)
    }

    // --- Robustness ----------------------------------------------------------

    @Test
    fun `a failing sink does not propagate`() {
        // Losing a log line is always better than losing the audio path.
        val log = Logger(
            LoggingConfig.debug(),
            LogSink { _, _, _, _ -> error("sink is broken") },
        )

        log.e(LogCategory.SERVICE) { "should not throw" }
    }

    @Test
    fun `isEnabled reflects the configuration`() {
        val log = logger(LoggingConfig.release())
        assertFalse(log.isEnabled(LogLevel.DEBUG, LogCategory.SERVICE))
        assertTrue(log.isEnabled(LogLevel.ERROR, LogCategory.SERVICE))
    }
}
