package com.saikai.ptt.di

import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The container is deliberately constructible without Android, so the wiring it
 * performs can be checked on the JVM.
 */
class AppContainerTest {

    @Test
    fun `release builds get release logging`() {
        val logging = AppContainer(isDebugBuild = false).config.logging

        assertFalse(
            "A release build must not ship debug logging (PRD section 48)",
            logging.isEnabled(LogLevel.DEBUG, LogCategory.NETWORK),
        )
        assertFalse(
            "Per-packet protocol logging must be off in release",
            logging.isEnabled(LogLevel.INFO, LogCategory.PROTOCOL),
        )
    }

    @Test
    fun `debug builds get full logging`() {
        val logging = AppContainer(isDebugBuild = true).config.logging
        assertTrue(logging.isEnabled(LogLevel.DEBUG, LogCategory.PROTOCOL))
    }

    @Test
    fun `communication timing does not depend on the build type`() {
        // Anything that differed between debug and release would mean the build
        // that was tested is not the build that ships.
        val debug = AppContainer(isDebugBuild = true).config
        val release = AppContainer(isDebugBuild = false).config

        assertEquals(debug.presence, release.presence)
        assertEquals(debug.session, release.session)
        assertEquals(debug.audio, release.audio)
        assertEquals(debug.network, release.network)
        assertEquals(debug.protocol, release.protocol)
    }
}
