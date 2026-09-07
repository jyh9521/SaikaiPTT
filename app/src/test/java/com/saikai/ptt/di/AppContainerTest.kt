package com.saikai.ptt.di

import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.domain.AppSettings
import com.saikai.ptt.core.domain.SettingsRepository
import com.saikai.ptt.core.logger.LogSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The container is deliberately constructible without Android, so the wiring it
 * performs can be checked on the JVM.
 */
class AppContainerTest {

    /**
     * Assembles the container with a sink that goes nowhere. The real one writes
     * to `android.util.Log`, whose stubs throw in a plain JVM test.
     */
    private fun container(isDebugBuild: Boolean) = AppContainer(
        isDebugBuild = isDebugBuild,
        logSink = LogSink.None,
        settingsRepositoryFactory = { InMemorySettings() },
    )

    /** Keeps the container assemblable without DataStore, which needs Android. */
    private class InMemorySettings : SettingsRepository {
        private val state = MutableStateFlow(AppSettings.DEFAULT)
        override val settings: Flow<AppSettings> = state
        override suspend fun current(): AppSettings = state.value
        override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings {
            state.value = transform(state.value)
            return state.value
        }
    }

    @Test
    fun `release builds get release logging`() {
        val logging = container(isDebugBuild = false).config.logging

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
        val logging = container(isDebugBuild = true).config.logging
        assertTrue(logging.isEnabled(LogLevel.DEBUG, LogCategory.PROTOCOL))
    }

    @Test
    fun `communication timing does not depend on the build type`() {
        // Anything that differed between debug and release would mean the build
        // that was tested is not the build that ships.
        val debug = container(isDebugBuild = true).config
        val release = container(isDebugBuild = false).config

        assertEquals(debug.presence, release.presence)
        assertEquals(debug.session, release.session)
        assertEquals(debug.audio, release.audio)
        assertEquals(debug.network, release.network)
        assertEquals(debug.protocol, release.protocol)
    }

    @Test
    fun `device identity is generated once and shared`() = runTest {
        // Every outgoing packet header and every history record keys off this
        // value, so two callers must never see different ids.
        val container = container(isDebugBuild = true)

        val first = container.deviceIdentity.deviceId()
        val second = container.deviceIdentity.deviceId()

        assertEquals(first, second)
        assertTrue("Must be a random UUID: $first", first.isVersion4)
    }

    @Test
    fun `the logger is built from the same config the container exposes`() {
        // One source of truth for the build type. If the logger were configured
        // separately, a release build could ship with debug logging while the
        // config said otherwise.
        val release = container(isDebugBuild = false)
        assertFalse(release.logger.isEnabled(LogLevel.DEBUG, LogCategory.SERVICE))
        assertTrue(release.logger.isEnabled(LogLevel.ERROR, LogCategory.SERVICE))

        val debug = container(isDebugBuild = true)
        assertTrue(debug.logger.isEnabled(LogLevel.DEBUG, LogCategory.PROTOCOL))
    }
}
