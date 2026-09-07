package com.saikai.ptt.core.protocol

import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.config.LoggingConfig
import com.saikai.ptt.core.config.RateLimitConfig
import com.saikai.ptt.core.logger.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-source limits of `docs/03_Protocol.md` section 45.
 *
 * The property that matters is not "a flood is slow" but "a flood is bounded":
 * whatever a broken device on the LAN does, this device spends a fixed amount of
 * work per five seconds on it, and never more memory than the source table
 * allows.
 */
class PacketRateLimiterTest {

    private val config = RateLimitConfig()
    private val clock = FakeClock()
    private val sink = RecordingSink()
    private val limiter = PacketRateLimiter(config, Logger(LoggingConfig.debug(), sink, clock), clock)

    private val source = "192.168.1.20"

    // --- Control packet rate ----------------------------------------------------

    @Test
    fun `traffic under the limit is admitted`() {
        repeat(config.maxControlPacketsPerSecondPerSource) {
            assertTrue("packet $it should have been admitted", limiter.admit(source))
        }
        assertFalse(limiter.isSilenced(source))
    }

    @Test
    fun `exceeding the control limit silences the source`() {
        repeat(config.maxControlPacketsPerSecondPerSource) { limiter.admit(source) }

        assertFalse("the packet over the limit is dropped", limiter.admit(source))
        assertTrue(limiter.isSilenced(source))

        // Everything from that source costs nothing until the window passes.
        repeat(1_000) { assertFalse(limiter.admit(source)) }
    }

    @Test
    fun `silence expires after the configured duration`() {
        repeat(config.maxControlPacketsPerSecondPerSource + 1) { limiter.admit(source) }
        assertTrue(limiter.isSilenced(source))

        clock.advance(config.silenceDuration.inWholeMilliseconds - 1)
        assertTrue(limiter.isSilenced(source))

        clock.advance(1)
        assertFalse(limiter.isSilenced(source))
        assertTrue(limiter.admit(source))
    }

    @Test
    fun `the counter resets when the window rolls`() {
        repeat(config.maxControlPacketsPerSecondPerSource) { limiter.admit(source) }
        clock.advance(1_000)

        repeat(config.maxControlPacketsPerSecondPerSource) {
            assertTrue(limiter.admit(source))
        }
        assertFalse(limiter.isSilenced(source))
    }

    @Test
    fun `sources are limited independently`() {
        repeat(config.maxControlPacketsPerSecondPerSource + 1) { limiter.admit("noisy") }

        assertTrue(limiter.isSilenced("noisy"))
        assertFalse(limiter.isSilenced("quiet"))
        assertTrue(limiter.admit("quiet"))
    }

    // --- Invalid packet rate ----------------------------------------------------

    @Test
    fun `the invalid limit trips before the control limit`() {
        // 20 invalid a second, against 50 control a second: a source sending
        // nothing but garbage is silenced well before the general limit.
        assertTrue(config.maxInvalidPacketsPerSecondPerSource < config.maxControlPacketsPerSecondPerSource)

        repeat(config.maxInvalidPacketsPerSecondPerSource) {
            limiter.admit(source)
            limiter.recordInvalid(source)
        }
        assertFalse(limiter.isSilenced(source))

        limiter.admit(source)
        limiter.recordInvalid(source)
        assertTrue(limiter.isSilenced(source))
    }

    @Test
    fun `an invalid flood is reported once, at a level release builds keep`() {
        repeat(500) { limiter.recordInvalid(source) }

        val warnings = sink.entries.filter { it.startsWith("WARN/SERVICE") }
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains(source))

        // SERVICE rather than PROTOCOL on purpose: section 45 asks for this line,
        // and PROTOCOL is off in release, which is where it matters most.
        assertTrue(LoggingConfig.release().isEnabled(
            com.saikai.ptt.core.logger.LogLevel.WARN,
            com.saikai.ptt.core.logger.LogCategory.SERVICE,
        ))
    }

    // --- Feeding validation results back in --------------------------------------

    @Test
    fun `only genuine faults count towards the invalid rate`() {
        val harmless = listOf(RejectionReason.OWN_BROADCAST_ECHO, RejectionReason.FOREIGN_SESSION)
        for (reason in harmless) {
            repeat(1_000) { limiter.record(source, Outcome.failure(reason)) }
        }
        assertFalse("normal traffic must never silence a peer", limiter.isSilenced(source))

        repeat(config.maxInvalidPacketsPerSecondPerSource + 1) {
            limiter.record(source, Outcome.failure(RejectionReason.BAD_MAGIC))
        }
        assertTrue(limiter.isSilenced(source))
    }

    @Test
    fun `a successful validation records nothing`() {
        val packet = Packet.of(
            type = PacketType.PING,
            senderDeviceId = ProtocolFixtures.SENDER,
            payload = EmptyPayload,
            timestampMillis = ProtocolFixtures.TIMESTAMP,
            targetDeviceId = ProtocolFixtures.TARGET,
        )
        repeat(1_000) { limiter.record(source, Outcome.success(packet)) }

        assertFalse(limiter.isSilenced(source))
        assertEquals(0, limiter.trackedSources)
    }

    @Test
    fun `every rejection reason is classified`() {
        // A new reason added without deciding whether it is a fault would silently
        // default to counting, so make the classification explicit and reviewed.
        val normalTraffic = setOf(
            RejectionReason.OWN_BROADCAST_ECHO,
            RejectionReason.FOREIGN_SESSION,
        )
        for (reason in RejectionReason.entries) {
            assertEquals(
                "$reason",
                reason !in normalTraffic,
                reason.countsAsInvalid,
            )
            assertEquals("$reason", reason.countsAsInvalid, reason.isLoggable)
        }
    }

    // --- The source table itself --------------------------------------------------

    @Test
    fun `the source table cannot grow without bound`() {
        // A device forging source addresses must not be able to exhaust memory.
        val cap = config.maxPeers * 4
        repeat(cap * 2) { limiter.admit("10.0.0.$it") }

        assertTrue(
            "tracking ${limiter.trackedSources} sources, cap is $cap",
            limiter.trackedSources <= cap,
        )
    }

    @Test
    fun `idle sources are pruned once the table fills`() {
        val cap = config.maxPeers * 4
        repeat(cap) { limiter.admit("10.0.0.$it") }
        assertEquals(cap, limiter.trackedSources)

        // Long enough that every existing entry counts as idle.
        clock.advance(config.silenceDuration.inWholeMilliseconds + 1)
        limiter.admit("10.9.9.9")

        assertTrue(limiter.trackedSources < cap)
    }

    @Test
    fun `forget and reset drop state`() {
        limiter.admit(source)
        assertEquals(1, limiter.trackedSources)

        limiter.forget(source)
        assertEquals(0, limiter.trackedSources)

        limiter.admit("a")
        limiter.admit("b")
        limiter.reset()
        assertEquals(0, limiter.trackedSources)
    }
}
