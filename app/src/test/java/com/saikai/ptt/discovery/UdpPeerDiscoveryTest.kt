package com.saikai.ptt.discovery

import com.saikai.ptt.core.config.LoggingConfig
import com.saikai.ptt.core.config.NetworkConfig
import com.saikai.ptt.core.config.RateLimitConfig
import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.domain.AnnounceReason
import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.domain.LocalPresence
import com.saikai.ptt.core.domain.Peer
import com.saikai.ptt.core.domain.PeerRegistry
import com.saikai.ptt.core.domain.PresenceState
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.LogSink
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.PacketRateLimiter
import com.saikai.ptt.core.protocol.PacketValidator
import com.saikai.ptt.network.InboundPacketRouter
import com.saikai.ptt.network.UdpTransport
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Two devices finding each other, over real UDP.
 *
 * The two transports deliberately collide on one control port: the first binds
 * it and the second drifts to the next, exactly as ADR-002 says it may. That
 * gives the test a real asymmetry to work with -- B's broadcast reaches A, A's
 * unicast answer reaches B -- which is the whole discovery handshake in one
 * exchange, with nothing mocked in between.
 */
class UdpPeerDiscoveryTest {

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val sink = object : LogSink {
        override fun write(
            level: LogLevel,
            category: LogCategory,
            message: String,
            throwable: Throwable?,
        ) = Unit
    }
    private val logger = Logger(LoggingConfig.debug(), sink)

    private inner class Node(controlPort: Int, voicePort: Int, name: String) {
        val deviceId: DeviceId = DeviceId.random()

        @Volatile
        var userName: String = name

        val config = SaikaiConfig(
            network = NetworkConfig(controlPort = controlPort, voicePort = voicePort),
        )
        val peers = PeerRegistry(config.presence, config.rateLimit, logger)
        val router = InboundPacketRouter(logger)
        val transport = UdpTransport(
            config = config,
            logger = logger,
            validator = PacketValidator(deviceId, logger),
            rateLimiter = PacketRateLimiter(RateLimitConfig(), logger),
            listener = router,
        )
        val discovery = UdpPeerDiscovery(
            config = config,
            logger = logger,
            transport = transport,
            peers = peers,
            router = router,
            presence = {
                transport.boundPorts?.let {
                    LocalPresence(deviceId, userName, it.voicePort, busy = false)
                }
            },
            activeUserChanges = emptyFlow<Unit>(),
            // Loopback stands in for the subnet broadcast address; the
            // computation itself is covered by BroadcastAddressesTest.
            broadcastAddresses = { listOf(loopback) },
        )

        fun start() = runBlocking {
            transport.start()
            discovery.start()
        }

        fun stop() = runBlocking {
            discovery.stop()
            transport.stop()
        }
    }

    // Two nodes, one nominal control port. The second drifts to CONTROL + 1.
    private val alice = Node(controlPort = 47_830, voicePort = 47_840, name = "Alice")
    private val bob = Node(controlPort = 47_830, voicePort = 47_841, name = "Bob")

    @After
    fun tearDown() {
        bob.stop()
        alice.stop()
    }

    private fun await(registry: PeerRegistry, predicate: (List<Peer>) -> Boolean): List<Peer> {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            val snapshot = registry.snapshot()
            if (predicate(snapshot)) return snapshot
            Thread.sleep(20)
        }
        return registry.snapshot()
    }

    @Test
    fun `an announcement is answered, and both sides learn each other`() {
        alice.start()
        bob.start()

        // Alice took the nominal port, so Bob's broadcast reaches her.
        assertEquals(47_830, alice.transport.boundPorts!!.controlPort)
        assertEquals(47_831, bob.transport.boundPorts!!.controlPort)

        runBlocking { bob.discovery.announce(AnnounceReason.SERVICE_READY) }

        val seenByAlice = await(alice.peers) { it.isNotEmpty() }
        assertEquals(1, seenByAlice.size)
        assertEquals("Bob", seenByAlice.single().userName)
        assertEquals(bob.deviceId, seenByAlice.single().deviceId)
        assertEquals(47_841, seenByAlice.single().endpoint.voicePort)
        assertEquals("127.0.0.1", seenByAlice.single().endpoint.address)

        // And the unicast answer got back, without Alice broadcasting at all.
        val seenByBob = await(bob.peers) { it.isNotEmpty() }
        assertEquals(1, seenByBob.size)
        assertEquals("Alice", seenByBob.single().userName)
        assertEquals(alice.deviceId, seenByBob.single().deviceId)
    }

    @Test
    fun `neither an announcement nor its answer makes a device see itself`() {
        alice.start()
        bob.start()
        runBlocking { bob.discovery.announce(AnnounceReason.SERVICE_READY) }
        await(alice.peers) { it.isNotEmpty() }

        assertTrue(alice.peers.snapshot().none { it.deviceId == alice.deviceId })
        assertTrue(bob.peers.snapshot().none { it.deviceId == bob.deviceId })
    }

    @Test
    fun `repeated announcements do not duplicate a peer`() {
        alice.start()
        bob.start()

        runBlocking {
            repeat(5) { bob.discovery.announce(AnnounceReason.SERVICE_READY) }
        }
        await(alice.peers) { it.isNotEmpty() }
        Thread.sleep(300)

        assertEquals(1, alice.peers.snapshot().size)
    }

    @Test
    fun `a rename reaches the other device on the next announcement`() {
        alice.start()
        bob.start()
        runBlocking { bob.discovery.announce(AnnounceReason.SERVICE_READY) }
        await(alice.peers) { it.isNotEmpty() }

        bob.userName = "Bob Renamed"
        runBlocking { bob.discovery.announce(AnnounceReason.USER_CHANGED) }

        val renamed = await(alice.peers) { peers -> peers.any { it.userName == "Bob Renamed" } }
        assertEquals(1, renamed.size)
        assertEquals("Bob Renamed", renamed.single().userName)
        assertEquals(bob.deviceId, renamed.single().deviceId)
    }

    @Test
    fun `a discovered peer is not yet online, because no heartbeat has arrived`() {
        alice.start()
        bob.start()
        runBlocking { bob.discovery.announce(AnnounceReason.SERVICE_READY) }

        val peers = await(alice.peers) { it.isNotEmpty() }
        assertEquals(PresenceState.DISCOVERED, peers.single().state)
        assertTrue(peers.single().state.isReachable)
    }

    @Test
    fun `a device with no name announces nothing`() {
        // A fresh install with no name chosen would otherwise put a blank row in
        // every peer list on the network.
        val nameless = Node(controlPort = 47_850, voicePort = 47_851, name = "")
        try {
            alice.start()
            nameless.start()
            runBlocking { nameless.discovery.announce(AnnounceReason.SERVICE_READY) }
            Thread.sleep(300)

            assertTrue(alice.peers.snapshot().isEmpty())
        } finally {
            nameless.stop()
        }
    }

    @Test
    fun `stopping unregisters, so a later packet changes nothing`() {
        alice.start()
        runBlocking { alice.discovery.stop() }
        // Started after, so that nothing was delivered before the unregister.
        bob.start()

        runBlocking { bob.discovery.announce(AnnounceReason.SERVICE_READY) }
        Thread.sleep(300)

        assertTrue(alice.peers.snapshot().isEmpty())
        assertNotNull(alice.transport.boundPorts)
    }
}
