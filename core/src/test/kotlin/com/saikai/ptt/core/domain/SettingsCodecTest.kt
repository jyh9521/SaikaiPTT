package com.saikai.ptt.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsCodecTest {

    @Test
    fun `an empty store yields the documented defaults`() {
        // docs/05_DataModel.md section 3.
        val settings = SettingsCodec.decode(emptyMap())

        assertNull("No device id until first launch generates one", settings.deviceId)
        assertEquals(emptyList<LocalUser>(), settings.localUsers)
        assertNull(settings.activeUserId)
        assertEquals("Japanese is the product default", AppLanguage.JAPANESE, settings.language)
        assertEquals("Being interruptible is opt-in", false, settings.allowInterrupt)
        assertEquals(HistoryRetention.SEVEN_DAYS, settings.historyRetention)
        assertEquals("ASR is off until the user enables it", false, settings.asrEnabled)
        assertEquals(false, settings.asrModelReady)
        assertEquals(false, settings.overlayEnabled)
        assertEquals(false, settings.firstLaunchCompleted)
        assertEquals(false, settings.permissionGuidanceShown)
        assertEquals(AppSettings.DEFAULT, settings)
    }

    @Test
    fun `a full round trip preserves every field`() {
        val original = AppSettings(
            deviceId = "6e84d6d2-b7d2-4fa9-9d9d-7cbf72000000",
            localUsers = listOf(LocalUser("u1", "田中", 1, 2), LocalUser("u2", "倉庫", 3, 4)),
            activeUserId = "u2",
            language = AppLanguage.BURMESE,
            allowInterrupt = true,
            historyRetention = HistoryRetention.THIRTY_DAYS,
            asrEnabled = true,
            asrModelReady = true,
            overlayEnabled = true,
            firstLaunchCompleted = true,
            permissionGuidanceShown = true,
        )

        assertEquals(original, SettingsCodec.decode(SettingsCodec.encode(original)))
    }

    @Test
    fun `every documented key is written`() {
        // A key that is never written can never be read back, and the symptom --
        // "my setting does not save" -- is invisible in a diff.
        val encoded = SettingsCodec.encode(AppSettings.DEFAULT)
        assertEquals(SettingsKeys.ALL.toSet(), encoded.keys)
    }

    // --- Corruption must degrade, never throw --------------------------------

    @Test
    fun `a value of the wrong type falls back to its default`() {
        // A half-written or downgraded store can hold anything. Coercing would
        // persist the damage on the next write, so a wrong type is treated as
        // absent.
        val corrupt = mapOf<String, Any?>(
            SettingsKeys.DEVICE_ID to true,
            SettingsKeys.LOCAL_USERS to 42,
            SettingsKeys.APP_LANGUAGE to false,
            SettingsKeys.ALLOW_INTERRUPT to "yes",
            SettingsKeys.HISTORY_RETENTION to 7,
            SettingsKeys.ASR_ENABLED to "true",
        )

        assertEquals(AppSettings.DEFAULT, SettingsCodec.decode(corrupt))
    }

    @Test
    fun `an unknown language tag falls back to Japanese`() {
        // Survives a downgrade that removed a language.
        val settings = SettingsCodec.decode(mapOf(SettingsKeys.APP_LANGUAGE to "kl"))
        assertEquals(AppLanguage.JAPANESE, settings.language)
    }

    @Test
    fun `an unknown retention value falls back to seven days`() {
        val settings = SettingsCodec.decode(mapOf(SettingsKeys.HISTORY_RETENTION to "NINE_YEARS"))
        assertEquals(HistoryRetention.SEVEN_DAYS, settings.historyRetention)
    }

    @Test
    fun `a corrupt user list yields no users rather than partial ones`() {
        val settings = SettingsCodec.decode(mapOf(SettingsKeys.LOCAL_USERS to "999:truncated"))
        assertEquals(emptyList<LocalUser>(), settings.localUsers)
    }

    @Test
    fun `a blank device id reads as absent`() {
        // Blank is not a usable identity; treating it as absent lets Task08
        // generate a real one instead of transmitting an empty sender.
        assertNull(SettingsCodec.decode(mapOf(SettingsKeys.DEVICE_ID to "  ")).deviceId)
        assertNull(SettingsCodec.decode(mapOf(SettingsKeys.DEVICE_ID to "")).deviceId)
    }

    // --- Active user resolution ---------------------------------------------

    @Test
    fun `the active user resolves from the stored id`() {
        val tanaka = LocalUser("u1", "田中", 1, 2)
        val settings = AppSettings(localUsers = listOf(tanaka), activeUserId = "u1")
        assertEquals(tanaka, settings.activeUser)
    }

    @Test
    fun `an active id pointing at a deleted user resolves to null`() {
        // docs/05_DataModel.md section 6 requires repair, not a crash. Reporting
        // null here is what lets Task09 repair it.
        val settings = AppSettings(
            localUsers = listOf(LocalUser("u1", "田中", 1, 2)),
            activeUserId = "deleted",
        )
        assertNull(settings.activeUser)
    }
}

class AppLanguageTest {

    @Test
    fun `every language except system has a valid BCP-47 tag`() {
        AppLanguage.entries.filterNot { it == AppLanguage.SYSTEM }.forEach { language ->
            assertEquals(
                "The storage tag doubles as the platform tag",
                language.tag,
                language.languageTag,
            )
        }
    }

    @Test
    fun `system has no language tag`() {
        // "system" is not a language tag. Letting it reach
        // LocaleList.forLanguageTags would produce an empty list and look like
        // a silent no-op rather than "follow the device".
        assertNull(AppLanguage.SYSTEM.languageTag)
    }

    @Test
    fun `the five product languages are all present`() {
        // docs/01_PRD.md section 26.
        assertEquals(
            setOf("ja", "zh-CN", "en", "my", "bn"),
            AppLanguage.entries.mapNotNull { it.languageTag }.toSet(),
        )
    }
}
