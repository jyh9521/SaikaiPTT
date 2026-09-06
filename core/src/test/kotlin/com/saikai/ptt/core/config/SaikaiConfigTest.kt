package com.saikai.ptt.core.config

import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SaikaiConfigTest {

    private val config = SaikaiConfig()

    // --- Values the protocol and the ADRs fix -------------------------------

    @Test
    fun `protocol constants match ADR-003`() {
        with(config.protocol) {
            assertEquals(1, version)
            assertEquals(72, headerBytes)
            assertEquals(1024, maxPayloadBytes)
            assertEquals(400, voiceDataMaxPayloadBytes)
            assertEquals(64, maxUserNameBytes)
            assertEquals("SKPT", magic.toString(Charsets.US_ASCII))
        }
    }

    @Test
    fun `ports match ADR-002`() {
        assertEquals(45820, config.network.controlPort)
        assertEquals(45821, config.network.voicePort)
    }

    // --- Derived values ------------------------------------------------------

    @Test
    fun `audio frame size derives from sample rate and frame duration`() {
        with(config.audio) {
            assertEquals("16 kHz for 20 ms", 320, frameSizeSamples)
            assertEquals("320 samples of 16-bit mono", 640, frameSizeBytes)
            assertEquals("20 ms frames", 50, packetsPerSecond)
        }
    }

    @Test
    fun `changing frame duration moves every derived audio value`() {
        // The point of deriving rather than restating: one edit, no stale constant.
        val longer = config.audio.copy(frameDuration = 40.milliseconds)
        assertEquals(640, longer.frameSizeSamples)
        assertEquals(1280, longer.frameSizeBytes)
        assertEquals(25, longer.packetsPerSecond)
    }

    @Test
    fun `peer timeout derives from the heartbeat interval`() {
        assertEquals(16.seconds, config.presence.peerTimeout)

        val slower = config.presence.copy(heartbeatInterval = 10.seconds)
        assertEquals(
            "Retuning the heartbeat must move the timeout with it",
            31.seconds,
            slower.peerTimeout,
        )
    }

    @Test
    fun `datagram sizes stay below the MTU`() {
        with(config.protocol) {
            assertTrue("$maxDatagramBytes must not fragment", maxDatagramBytes < 1500)
            assertEquals(1096, maxDatagramBytes)
            assertEquals("Voice packets are far smaller than the cap", 472, maxVoiceDatagramBytes)
        }
    }

    // --- Invariants must actually reject bad values --------------------------

    @Test(expected = IllegalArgumentException::class)
    fun `a payload cap that would fragment is rejected`() {
        ProtocolConfig(maxPayloadBytes = 2000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a voice cap larger than the general cap is rejected`() {
        ProtocolConfig(voiceDataMaxPayloadBytes = 2048)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `declaring a peer offline after one missed heartbeat is rejected`() {
        // A single dropped broadcast is normal on WiFi and must not look like
        // an offline peer.
        PresenceConfig(missedIntervalsBeforeOffline = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sweeping for timeouts too rarely is rejected`() {
        // A 30s sweep against a 16s timeout means a peer can appear online for
        // 46s after it vanished.
        PresenceConfig(evaluationInterval = 30.seconds)
    }

    @Test
    fun `shortening the heartbeat for a test is a legal configuration`() {
        // Regression: the sweep interval used to be compared against the
        // heartbeat interval, which rejected this outright even though a 2s
        // sweep detects a 4s timeout twice over.
        val fast = PresenceConfig(heartbeatInterval = 1.seconds)
        assertEquals(4.seconds, fast.peerTimeout)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `retries that cannot fit inside the request timeout are rejected`() {
        // Otherwise the final retry is sent and abandoned in the same instant.
        SessionConfig(voiceStartRetry = 300.milliseconds, voiceStartMaxRetries = 5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a pre-roll buffer shorter than the handshake window is rejected`() {
        // Audio captured while waiting for acceptance would be dropped, which is
        // exactly the lost first syllable the pre-roll exists to prevent.
        SessionConfig(preRollBuffer = 100.milliseconds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stereo is rejected`() {
        AudioConfig(channelCount = 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a frame duration that does not divide one second is rejected`() {
        AudioConfig(frameDuration = 30.milliseconds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a jitter buffer that starts deeper than its maximum is rejected`() {
        JitterBufferConfig(startThresholdFrames = 20)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `raising the bitrate past the voice payload cap is rejected`() {
        // 256 kbps over 20 ms is 640 bytes, well past the 400-byte cap. Without
        // this check every frame would silently fail validation at the receiver.
        SaikaiConfig(audio = AudioConfig(opusBitrateBps = 256_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a receive buffer too small for the largest datagram is rejected`() {
        SaikaiConfig(network = NetworkConfig(receiveBufferBytes = 512))
    }

    // --- Build-type behaviour ------------------------------------------------

    @Test
    fun `release logging drops debug and narrows categories`() {
        val release = SaikaiConfig.forBuild(isDebugBuild = false).logging

        assertFalse(
            "Debug logging must be off in release (PRD section 48)",
            release.isEnabled(LogLevel.DEBUG, LogCategory.LIFECYCLE),
        )
        assertFalse(
            "Per-packet protocol logging must be off in release",
            release.isEnabled(LogLevel.INFO, LogCategory.PROTOCOL),
        )
        assertFalse(
            "Per-frame audio logging must be off in release",
            release.isEnabled(LogLevel.INFO, LogCategory.AUDIO),
        )
        assertTrue(
            "Service lifecycle stays loggable for field reports",
            release.isEnabled(LogLevel.INFO, LogCategory.SERVICE),
        )
        assertTrue(
            "Errors are never suppressed",
            release.isEnabled(LogLevel.ERROR, LogCategory.SERVICE),
        )
    }

    @Test
    fun `debug logging enables everything`() {
        val debug = SaikaiConfig.forBuild(isDebugBuild = true).logging
        LogCategory.entries.forEach { category ->
            assertTrue(
                "$category should log in debug builds",
                debug.isEnabled(LogLevel.DEBUG, category),
            )
        }
    }

    @Test
    fun `only logging differs between build types`() {
        // Timing, ports and audio must be identical, or the build that was
        // tested is not the build that ships.
        val debug = SaikaiConfig.forBuild(isDebugBuild = true)
        val release = SaikaiConfig.forBuild(isDebugBuild = false)
        assertEquals(debug.copy(logging = release.logging), release)
    }

    // --- Injectability -------------------------------------------------------

    @Test
    fun `config is a value that tests can vary`() {
        val fast = config.copy(presence = config.presence.copy(heartbeatInterval = 1.seconds))
        assertEquals(1.seconds, fast.presence.heartbeatInterval)
        assertEquals("The original is untouched", 5.seconds, config.presence.heartbeatInterval)
    }
}
