package com.saikai.ptt.core.domain

import com.saikai.ptt.core.config.LoggingConfig
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.LogSink
import com.saikai.ptt.core.logger.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredDeviceIdentityProviderTest {

    private val recorded = mutableListOf<String>()
    private val logger = Logger(
        LoggingConfig.debug(),
        LogSink { level, category, message, _ -> recorded += "$level/$category: $message" },
    )

    private fun provider(
        settings: SettingsRepository,
        generate: () -> DeviceId = DeviceId::random,
    ) = StoredDeviceIdentityProvider(settings, logger, generate)

    @Test
    fun `generates a version 4 id on first use`() = runTest {
        val settings = FakeSettingsRepository()

        val id = provider(settings).deviceId()

        assertTrue("Must be a random UUID: $id", id.isVersion4)
        assertEquals("and it must be persisted", id.value, settings.current().deviceId)
    }

    @Test
    fun `repeated reads return the same id`() = runTest {
        val subject = provider(FakeSettingsRepository())
        val first = subject.deviceId()
        repeat(10) { assertEquals(first, subject.deviceId()) }
    }

    @Test
    fun `the id survives a restart`() = runTest {
        // A new provider over the same store is what a process restart looks
        // like from here. PRD section 5.1: the id must outlive restarts, device
        // reboots and network changes.
        val settings = FakeSettingsRepository()
        val before = provider(settings).deviceId()

        val after = provider(settings).deviceId()

        assertEquals(before, after)
    }

    @Test
    fun `an existing id is never regenerated`() = runTest {
        val existing = DeviceId.random()
        val settings = FakeSettingsRepository(AppSettings(deviceId = existing.value))
        var generated = 0

        val id = provider(settings) { generated++; DeviceId.random() }.deviceId()

        assertEquals(existing, id)
        assertEquals("Nothing should have been generated", 0, generated)
        assertEquals("and nothing should have been written", 0, settings.updateCount)
    }

    @Test
    fun `the store is read once, then cached`() = runTest {
        // The id goes into every packet header at 50 packets a second. Hitting
        // storage each time would be pointless work for a value that cannot
        // change.
        val settings = FakeSettingsRepository()
        val subject = provider(settings)

        repeat(100) { subject.deviceId() }

        assertEquals("Only the first-generation write", 1, settings.updateCount)
    }

    @Test
    fun `concurrent first calls agree on one id`() = runTest {
        // Two coroutines starting the app at once must not each mint an id and
        // let one silently win.
        val settings = FakeSettingsRepository()
        var generated = 0
        val subject = provider(settings) { generated++; DeviceId.random() }

        val ids = coroutineScope {
            (1..20).map { async { subject.deviceId() } }.awaitAll()
        }

        assertEquals("All callers must see one identity", 1, ids.toSet().size)
        assertEquals("Only one id may be generated", 1, generated)
        assertEquals(ids.first().value, settings.current().deviceId)
    }

    @Test
    fun `an unreadable stored id is replaced and reported`() = runTest {
        // It cannot be put on the wire, so a replacement is the only way
        // forward -- but peers will see this installation as a new device, and
        // that deserves to be loud in the log rather than silent.
        val settings = FakeSettingsRepository(AppSettings(deviceId = "not-a-uuid"))

        val id = provider(settings).deviceId()

        assertTrue(id.isVersion4)
        assertEquals(id.value, settings.current().deviceId)
        assertTrue(
            "Replacement must be logged as an error: $recorded",
            recorded.any { it.startsWith("${LogLevel.ERROR}/${LogCategory.STORAGE}") },
        )
    }

    @Test
    fun `a blank stored id is treated as absent without an error`() = runTest {
        // Never written rather than damaged: nothing to report.
        val settings = FakeSettingsRepository(AppSettings(deviceId = "   "))

        val id = provider(settings).deviceId()

        assertNotNull(id)
        assertTrue("A blank value is not corruption: $recorded", recorded.none { "ERROR" in it })
    }
}
