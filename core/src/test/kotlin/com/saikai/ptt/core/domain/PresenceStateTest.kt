package com.saikai.ptt.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The declared transition table.
 *
 * This is the specification; [PeerRegistryTest] checks that the registry's
 * derivation only ever produces transitions this table permits. Neither test is
 * worth much alone -- together they mean the machine cannot drift from its
 * description without something going red.
 */
class PresenceStateTest {

    @Test
    fun `the five states of the protocol are the five states here`() {
        assertEquals(
            listOf(
                PresenceState.DISCOVERED,
                PresenceState.ONLINE,
                PresenceState.BUSY,
                PresenceState.COMMUNICATING,
                PresenceState.OFFLINE,
            ),
            PresenceState.entries,
        )
    }

    @Test
    fun `a state is never a transition to itself`() {
        for (state in PresenceState.entries) {
            assertFalse("$state", state.canTransitionTo(state))
        }
    }

    @Test
    fun `every state can go offline and come back`() {
        for (state in PresenceState.entries - PresenceState.OFFLINE) {
            assertTrue("$state -> OFFLINE", state.canTransitionTo(PresenceState.OFFLINE))
            assertTrue("OFFLINE -> $state", PresenceState.OFFLINE.canTransitionTo(state))
        }
    }

    @Test
    fun `only ONLINE can never fall back to DISCOVERED`() {
        // Having once received a HEARTBEAT is permanent. A peer that is BUSY or
        // COMMUNICATING may never have sent one -- both facts come from elsewhere
        // -- so those two can land on DISCOVERED when the other fact clears.
        assertFalse(PresenceState.ONLINE.canTransitionTo(PresenceState.DISCOVERED))
        assertTrue(PresenceState.BUSY.canTransitionTo(PresenceState.DISCOVERED))
        assertTrue(PresenceState.COMMUNICATING.canTransitionTo(PresenceState.DISCOVERED))
        assertTrue(PresenceState.OFFLINE.canTransitionTo(PresenceState.DISCOVERED))
    }

    @Test
    fun `only OFFLINE is unreachable`() {
        for (state in PresenceState.entries) {
            assertEquals("$state", state != PresenceState.OFFLINE, state.isReachable)
        }
    }

    @Test
    fun `a busy peer may still be asked, an absent or engaged one may not`() {
        // 04_UI_UX section 38: the busy flag is advisory and up to a heartbeat
        // old, so the request is made and answered rather than pre-empted.
        assertTrue(PresenceState.BUSY.acceptsRequest)
        assertTrue(PresenceState.ONLINE.acceptsRequest)
        assertTrue(PresenceState.DISCOVERED.acceptsRequest)
        assertFalse(PresenceState.OFFLINE.acceptsRequest)
        assertFalse(PresenceState.COMMUNICATING.acceptsRequest)
    }
}
