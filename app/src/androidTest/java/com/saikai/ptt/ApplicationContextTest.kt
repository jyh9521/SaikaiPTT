package com.saikai.ptt

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the instrumented test source set is wired up and the app installs
 * under the expected package. Real device behaviour (network, audio,
 * background) is covered from Task16 onward; see docs/07_TestPlan.md.
 */
@RunWith(AndroidJUnit4::class)
class ApplicationContextTest {

    @Test
    fun appContextHasExpectedPackageName() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.saikai.ptt", context.packageName)
    }
}
