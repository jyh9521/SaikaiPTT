package com.saikai.ptt.core.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio-focus decision of `docs/ADR/ADR-004` section 5.
 *
 * Worth a test of its own because it is a deliberate departure from how a media
 * app behaves, and the two cases that differ are exactly the ones a future
 * change would "fix" back: a transient loss normally means pause, and a duckable
 * loss normally means turn down. Here both mean stop.
 */
class AudioFocusPolicyTest {

    @Test
    fun `every kind of loss ends the session`() {
        // Push-to-talk is live. Audio held back during a phone call and played
        // afterwards is not a delayed message, it is a confusing one -- and the
        // speaker stopped waiting for an answer long ago.
        assertTrue(AudioFocusPolicy.endsSession(AudioFocusChange.LOST))
        assertTrue(AudioFocusPolicy.endsSession(AudioFocusChange.LOST_TRANSIENT))
        assertTrue(AudioFocusPolicy.endsSession(AudioFocusChange.LOST_TRANSIENT_CAN_DUCK))
    }

    @Test
    fun `regaining focus does not restart anything`() {
        assertFalse(AudioFocusPolicy.endsSession(AudioFocusChange.GAINED))
    }

    @Test
    fun `ducking is never the answer`() {
        // The product exists to be understood in a noisy warehouse. Half-volume
        // speech there is not quieter speech, it is no speech.
        for (change in AudioFocusChange.entries) {
            assertFalse("$change", AudioFocusPolicy.shouldDuck(change))
        }
    }

    @Test
    fun `every change is decided, none is left to a default`() {
        // A focus change nobody classified would fall through to "keep playing",
        // which is how an app ends up talking over a phone call.
        for (change in AudioFocusChange.entries) {
            val ends = AudioFocusPolicy.endsSession(change)
            assertTrue("$change", ends == (change != AudioFocusChange.GAINED))
        }
    }
}
