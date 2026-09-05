package com.saikai.ptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke test for the build configuration itself.
 *
 * This is deliberately not an `assertEquals(4, 2 + 2)` placeholder. At this
 * stage there is no business logic to test, but the build configuration is
 * real and worth guarding: the application id is the app's identity on the
 * device, and a silent change to it would orphan every installed instance's
 * DataStore, Room database and recordings.
 */
class BuildConfigurationTest {

    @Test
    fun `application id matches the documented namespace`() {
        // docs/08_ReleaseChecklist.md section 3
        assertEquals("com.saikai.ptt", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun `version name is set`() {
        assertTrue(
            "versionName must not be blank",
            BuildConfig.VERSION_NAME.isNotBlank(),
        )
    }

    @Test
    fun `version code is positive`() {
        assertTrue(
            "versionCode must be a positive integer",
            BuildConfig.VERSION_CODE > 0,
        )
    }
}
