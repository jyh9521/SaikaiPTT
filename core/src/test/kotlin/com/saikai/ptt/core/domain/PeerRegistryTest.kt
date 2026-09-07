package com.saikai.ptt.core.domain

import com.saikai.ptt.core.FakeClock
import com.saikai.ptt.core.RecordingSink
import com.saikai.ptt.core.config.LoggingConfig
import com.saikai.ptt.core.config.PresenceConfig
import com.saikai.ptt.core.config.RateLimitConfig
import com.saikai.ptt.core.logger.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerRegistryTest {

    private val presence = PresenceConfig()
    private val limits = RateLimitConfig()
    private val clock = FakeClock(millis = 1_000_000L)
    private val sink = RecordingSink()
    private val registry =
        PeerRegistry(presence, limits, Logger(LoggingConfig.debug(), sink, clock), clock)

    private val timeout = presence.peerTimeout.inWholeMilliseconds

    private val alice = DeviceId.random()
    private val bob = DeviceId.random()

    private fun observation(
        deviceId: DeviceId,
        kind: PresenceKind = PresenceKind.HEARTBEAT,
        remoteBusy: Boolean = false,
        address: String = "192.168.1.10",
        voicePort: Int = 45_821,
        userName: String = "Kenji",
        protocolVersion: Int = 1,
    ) = PeerObservation(
        deviceId = deviceId,
        userName = userName,
        endpoint = PeerEndpoint(address, voicePort),
        protocolVersion = protocolVersion,
        remoteBusy = remoteBusy,
        kind = kind,
    )

    private fun observe(
        deviceId: DeviceId,
        kind: PresenceKind = PresenceKind.HEARTBEAT,
        remoteBusy: Boolean = false,
        address: String = "192.168.1.10",
        voicePort: Int = 45_821,
        userName: String = "Kenji",
    ) = registry.onPresence(
        observation(deviceId, kind, remoteBusy, address, voicePort, userName)
    )

    private fun stateOf(deviceId: DeviceId): PresenceState? = registry.peer(deviceId)?.state

    // --- Creation and the DISCOVERED / ONLINE distinction -----------------------

    @Test
    fun `an announcement creates a peer that is discovered but not yet online`() {
        val change = observe(alice, PresenceKind.DISCOVERY).valueOrNull()!!

        assertTrue(change.isNew)
        assertNull(change.previousState)
        assertEquals(PresenceState.DISCOVERED, change.peer.state)
        assertEquals(alice, change.peer.deviceId)
        assertEquals(1, registry.snapshot().size)
    }

    @Test
    fun `a heartbeat is what makes a peer online`() {
        observe(alice, PresenceKind.DISCOVERY)
        assertEquals(PresenceState.DISCOVERED, stateOf(alice))

        val change = observe(alice, PresenceKind.HEARTBEAT).valueOrNull()!!

        assertEquals(PresenceState.DISCOVERED, change.previousState)
        assertEquals(PresenceState.ONLINE, change.peer.state)
    }

    @Test
    fun `a peer first seen by heartbeat is online immediately and reports one arrival`() {
        val change = observe(alice, PresenceKind.HEARTBEAT).valueOrNull()!!

        assertTrue(change.isNew)
        assertEquals(PresenceState.ONLINE, change.peer.state)
        // Not "added, then DISCOVERED -> ONLINE": it was never DISCOVERED.
        assertFalse(sink.entries.any { it.contains("DISCOVERED -> ONLINE") })
    }

    @Test
    fun `a unicast response also only discovers`() {
        observe(alice, PresenceKind.DISCOVERY_RESPONSE)
        assertEquals(PresenceState.DISCOVERED, stateOf(alice))
    }

    // --- Busy -------------------------------------------------------------------

    @Test
    fun `the peer's own busy flag drives BUSY`() {
        observe(alice, remoteBusy = true)
        assertEquals(PresenceState.BUSY, stateOf(alice))

        observe(alice, remoteBusy = false)
        assertEquals(PresenceState.ONLINE, stateOf(alice))
    }

    @Test
    fun `a busy peer that never sent a heartbeat falls back to DISCOVERED`() {
        observe(alice, PresenceKind.DISCOVERY, remoteBusy = true)
        assertEquals(PresenceState.BUSY, stateOf(alice))

        observe(alice, PresenceKind.DISCOVERY, remoteBusy = false)
        assertEquals(PresenceState.DISCOVERED, stateOf(alice))
    }

    // --- Communicating ----------------------------------------------------------

    @Test
    fun `the local session drives COMMUNICATING`() {
        observe(alice)
        assertTrue(registry.setSessionPeer(alice))
        assertEquals(PresenceState.COMMUNICATING, stateOf(alice))

        registry.setSessionPeer(null)
        assertEquals(PresenceState.ONLINE, stateOf(alice))
    }

    @Test
    fun `our own session outranks the peer's stale busy flag`() {
        // The peer broadcasts BUSY because it is talking to us. Showing that as
        // "in a call with someone else" would be exactly wrong.
        observe(alice, remoteBusy = true)
        registry.setSessionPeer(alice)
        assertEquals(PresenceState.COMMUNICATING, stateOf(alice))
    }

    @Test
    fun `moving the session to another peer releases the first`() {
        observe(alice)
        observe(bob, address = "192.168.1.11")
        registry.setSessionPeer(alice)
        registry.setSessionPeer(bob)

        assertEquals(PresenceState.ONLINE, stateOf(alice))
        assertEquals(PresenceState.COMMUNICATING, stateOf(bob))
    }

    @Test
    fun `a session cannot be opened with a peer that is not in the table`() {
        assertFalse(registry.setSessionPeer(alice))
        assertNull(registry.peer(alice))
    }

    // --- Timeout and recovery ---------------------------------------------------

    @Test
    fun `a peer goes offline only after the full timeout`() {
        observe(alice)

        clock.advance(timeout - 1)
        assertTrue(registry.evaluate().isEmpty())
        assertEquals(PresenceState.ONLINE, stateOf(alice))

        clock.advance(1)
        val changes = registry.evaluate()
        assertEquals(1, changes.size)
        assertEquals(PresenceState.OFFLINE, changes.single().peer.state)
        assertEquals(PresenceState.ONLINE, changes.single().previousState)
    }

    @Test
    fun `a single missed heartbeat is not an offline peer`() {
        // 16s timeout against a 5s interval: two missed beats must not be enough.
        observe(alice)
        clock.advance(presence.heartbeatInterval.inWholeMilliseconds * 2)
        registry.evaluate()
        assertEquals(PresenceState.ONLINE, stateOf(alice))
    }

    @Test
    fun `any presence packet brings an offline peer straight back`() {
        observe(alice)
        clock.advance(timeout)
        registry.evaluate()
        assertEquals(PresenceState.OFFLINE, stateOf(alice))

        observe(alice)
        assertEquals(PresenceState.ONLINE, stateOf(alice))
    }

    @Test
    fun `silence outranks an open session`() {
        // A call whose peer has stopped answering is not a call. The session
        // layer learns it from here, not the other way round.
        observe(alice)
        registry.setSessionPeer(alice)
        clock.advance(timeout)

        assertEquals(PresenceState.OFFLINE, registry.evaluate().single().peer.state)
    }

    // --- Activity from non-presence packets --------------------------------------

    @Test
    fun `voice and pong refresh liveness`() {
        observe(alice)
        clock.advance(timeout - 1)
        assertTrue(registry.onActivity(alice))

        clock.advance(timeout - 1)
        registry.evaluate()
        assertEquals(PresenceState.ONLINE, stateOf(alice))
    }

    @Test
    fun `activity cannot promote a discovered peer to online`() {
        // Section 12.3: only presence packets decide what a peer is.
        observe(alice, PresenceKind.DISCOVERY)
        registry.onActivity(alice)
        assertEquals(PresenceState.DISCOVERED, stateOf(alice))
    }

    @Test
    fun `activity from an unknown device creates nothing`() {
        assertFalse(registry.onActivity(alice))
        assertTrue(registry.snapshot().isEmpty())
    }

    // --- No network at all -----------------------------------------------------

    @Test
    fun `with no network every peer is offline, however recently it was heard`() {
        observe(alice)
        observe(bob, address = "192.168.1.11")
        assertEquals(PresenceState.ONLINE, stateOf(alice))

        assertTrue(registry.setNetworkAvailable(false))

        assertTrue(registry.snapshot().all { it.state == PresenceState.OFFLINE })
        // The records survive: a list that empties and refills is a worse answer
        // to "did my radio just disappear" than one that greys out.
        assertEquals(2, registry.snapshot().size)
        assertEquals("Kenji", registry.peer(alice)!!.userName)
    }

    @Test
    fun `the network coming back does not need a new packet if nothing expired`() {
        observe(alice)
        registry.setNetworkAvailable(false)
        clock.advance(1_000)

        registry.setNetworkAvailable(true)

        assertEquals(PresenceState.ONLINE, stateOf(alice))
    }

    @Test
    fun `a peer that timed out while the network was down stays offline`() {
        observe(alice)
        registry.setNetworkAvailable(false)
        clock.advance(timeout + 1)

        registry.setNetworkAvailable(true)

        assertEquals(PresenceState.OFFLINE, stateOf(alice))
    }

    @Test
    fun `setting the network state to what it already was changes nothing`() {
        observe(alice)
        assertFalse(registry.setNetworkAvailable(true))
        assertTrue(registry.setNetworkAvailable(false))
        assertFalse(registry.setNetworkAvailable(false))
    }

    @Test
    fun `a session with an unreachable peer is still no session`() {
        observe(alice)
        registry.setSessionPeer(alice)
        registry.setNetworkAvailable(false)

        assertEquals(PresenceState.OFFLINE, stateOf(alice))
    }

    // --- Identity ----------------------------------------------------------------

    @Test
    fun `a peer that changes address is still the same peer`() {
        observe(alice, address = "192.168.1.10")
        val change = observe(alice, address = "192.168.1.25").valueOrNull()!!

        assertEquals(1, registry.snapshot().size)
        assertTrue(change.endpointChanged)
        assertFalse(change.isNew)
        assertEquals("192.168.1.25", registry.peer(alice)!!.endpoint.address)
    }

    @Test
    fun `a peer that changes voice port is still the same peer`() {
        observe(alice, voicePort = 45_821)
        val change = observe(alice, voicePort = 45_830).valueOrNull()!!

        assertEquals(1, registry.snapshot().size)
        assertTrue(change.endpointChanged)
        assertEquals(45_830, registry.peer(alice)!!.endpoint.voicePort)
    }

    @Test
    fun `a rename updates the peer instead of adding one`() {
        observe(alice, userName = "Kenji")
        val change = observe(alice, userName = "Kenji Sato").valueOrNull()!!

        assertEquals(1, registry.snapshot().size)
        assertTrue(change.userNameChanged)
        assertFalse(change.endpointChanged)
        assertEquals("Kenji Sato", registry.peer(alice)!!.userName)
    }

    @Test
    fun `two devices at the same address are two peers`() {
        observe(alice, address = "192.168.1.10")
        observe(bob, address = "192.168.1.10")
        assertEquals(2, registry.snapshot().size)
    }

    // --- Refusals ------------------------------------------------------------------

    @Test
    fun `the all-zero device id is not a peer`() {
        val outcome = registry.onPresence(observation(DeviceId.ZERO))
        assertEquals(PeerRegistryError.INVALID_IDENTITY, outcome.errorOrNull())
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `an unusable name is refused rather than stored`() {
        assertEquals(
            PeerRegistryError.INVALID_NAME,
            registry.onPresence(observation(alice, userName = "   ")).errorOrNull(),
        )
        assertEquals(
            PeerRegistryError.INVALID_NAME,
            registry.onPresence(observation(alice, userName = "a".repeat(65))).errorOrNull(),
        )
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `a name longer than this app's own input limit is still accepted`() {
        // 24 code points is this app's UI rule, not the protocol's. A peer built
        // differently must not become invisible because of it.
        val long = "a".repeat(40)
        assertTrue(registry.onPresence(observation(alice, userName = long)).isSuccess)
        assertEquals(long, registry.peer(alice)!!.userName)
    }

    // --- Capacity --------------------------------------------------------------------

    @Test
    fun `the table holds sixty-four live peers and refuses the sixty-fifth`() {
        repeat(limits.maxPeers) {
            assertTrue(observe(DeviceId.random(), address = "10.0.0.$it").isSuccess)
        }
        assertEquals(limits.maxPeers, registry.snapshot().size)

        val outcome = observe(DeviceId.random(), address = "10.0.1.1")
        assertEquals(PeerRegistryError.TABLE_FULL, outcome.errorOrNull())
        assertEquals(limits.maxPeers, registry.snapshot().size)
    }

    @Test
    fun `a full table gives up an offline peer before refusing a live one`() {
        // A room that has seen 64 devices over a week must still be able to
        // discover the 65th; a table of 64 live peers must still refuse.
        val first = DeviceId.random()
        observe(first, address = "10.0.0.1")
        clock.advance(timeout)
        repeat(limits.maxPeers - 1) {
            observe(DeviceId.random(), address = "10.0.2.$it")
        }
        registry.evaluate()
        assertEquals(PresenceState.OFFLINE, stateOf(first))
        assertEquals(limits.maxPeers, registry.snapshot().size)

        assertTrue(observe(DeviceId.random(), address = "10.0.3.1").isSuccess)
        assertEquals(limits.maxPeers, registry.snapshot().size)
        assertNull("the offline peer was the one given up", registry.peer(first))
    }

    // --- Observation ------------------------------------------------------------------

    @Test
    fun `the published table reflects every change`() {
        observe(alice)
        assertEquals(1, registry.peers.value.size)

        observe(bob, address = "192.168.1.11")
        assertEquals(2, registry.peers.value.size)

        registry.setSessionPeer(alice)
        assertEquals(
            PresenceState.COMMUNICATING,
            registry.peers.value.single { it.deviceId == alice }.state,
        )

        registry.forget(bob)
        assertEquals(1, registry.peers.value.size)

        registry.clear()
        assertTrue(registry.peers.value.isEmpty())
    }

    @Test
    fun `forgetting the peer we are talking to closes the session too`() {
        observe(alice)
        registry.setSessionPeer(alice)
        assertTrue(registry.forget(alice))

        observe(alice)
        assertEquals(PresenceState.ONLINE, stateOf(alice))
    }

    @Test
    fun `the table keeps the order peers were first seen`() {
        observe(alice, address = "192.168.1.10")
        observe(bob, address = "192.168.1.11")
        observe(alice, address = "192.168.1.12")

        assertEquals(listOf(alice, bob), registry.peers.value.map { it.deviceId })
    }

    // --- The machine as a whole ---------------------------------------------------------

    @Test
    fun `no sequence of events produces a transition the table forbids`() {
        // The registry derives state; PresenceState declares which transitions
        // are legal. Nothing checks that the two agree unless something drives
        // the machine hard and compares.
        val random = java.util.Random(20260907)
        val devices = List(6) { DeviceId.random() }
        val seen = mutableMapOf<DeviceId, PresenceState>()
        val observedTransitions = mutableSetOf<Pair<PresenceState, PresenceState>>()

        fun record() {
            for (peer in registry.snapshot()) {
                val previous = seen.put(peer.deviceId, peer.state)
                if (previous != null && previous != peer.state) {
                    assertTrue(
                        "$previous -> ${peer.state} is not a declared transition",
                        previous.canTransitionTo(peer.state),
                    )
                    observedTransitions += previous to peer.state
                }
            }
        }

        repeat(4_000) {
            val device = devices[random.nextInt(devices.size)]
            when (random.nextInt(7)) {
                0 -> observe(device, PresenceKind.DISCOVERY, remoteBusy = random.nextBoolean())
                1 -> observe(device, PresenceKind.DISCOVERY_RESPONSE, remoteBusy = random.nextBoolean())
                2 -> observe(device, PresenceKind.HEARTBEAT, remoteBusy = random.nextBoolean())
                3 -> registry.onActivity(device)
                4 -> registry.setSessionPeer(if (random.nextBoolean()) device else null)
                5 -> registry.setNetworkAvailable(random.nextInt(4) != 0)
                else -> registry.evaluate()
            }
            record()
            clock.advance(random.nextInt(9_000).toLong())
            registry.evaluate()
            record()
        }

        // A drive that never left one state would satisfy the assertion above
        // vacuously, so require that it actually explored the machine.
        assertTrue(
            "only explored $observedTransitions",
            observedTransitions.size >= 8,
        )
    }
}
