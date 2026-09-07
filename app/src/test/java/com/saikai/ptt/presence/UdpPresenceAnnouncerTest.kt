package com.saikai.ptt.presence

import com.saikai.ptt.core.config.LoggingConfig
import com.saikai.ptt.core.config.NetworkConfig
import com.saikai.ptt.core.config.PresenceConfig
import com.saikai.ptt.core.config.RateLimitConfig
import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.domain.LocalPresence
import com.saikai.ptt.core.domain.Peer
import com.saikai.ptt.core.domain.PeerRegistry
import com.saikai.ptt.core.domain.PresenceState
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.LogSink
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.EmptyPayload
import com.saikai.ptt.core.protocol.Packet
import com.saikai.ptt.core.protocol.PacketRateLimiter
import com.saikai.ptt.core.protocol.PacketType
import com.saikai.ptt.core.protocol.PacketValidator
import com.saikai.ptt.discovery.UdpPeerDiscovery
import com.saikai.ptt.network.InboundPacketRouter
import com.saikai.ptt.network.TransportChannel
import com.saikai.ptt.network.UdpTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import kotlin.time.Duration.Companion.milliseconds

/**
 * Liveness and the busy flag, over real UDP.
 *
 * The timings are compressed -- a 200 ms heartbeat against the product's five
 * seconds -- but the relationships are the product's: three missed periods plus
 * tolerance before offline, a sweep several times per timeout window, and an
 * immediate extra broadcast when the busy flag flips. Those relationships are
 * what the tests are about; the absolute numbers are config.
 */
class UdpPresenceAnnouncerTest {

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val logger = Logger(
        LoggingConfig.debug(),
        object : LogSink {
            override fun write(
                level: LogLevel,
                category: LogCategory,
                message: String,
                throwable: Throwable?,
            ) = Unit
        },
    )

    private val nodes = mutableListOf<Node>()

    private inner class Node(
        controlPort: Int,
        voicePort: Int,
        name: String,
        presenceConfig: PresenceConfig,
    ) {
        val deviceId: DeviceId = DeviceId.random()
        val busy = MutableStateFlow(false)

        val config = SaikaiConfig(
            network = NetworkConfig(controlPort = controlPort, voicePort = voicePort),
            presence = presenceConfig,
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

        private val snapshot: suspend () -> LocalPresence? = {
            transport.boundPorts?.let {
                LocalPresence(deviceId, name, it.voicePort, busy.value)
            }
        }

        val discovery = UdpPeerDiscovery(
            config = config,
            logger = logger,
            transport = transport,
            peers = peers,
            router = router,
            presence = snapshot,
            activeUserChanges = emptyFlow<Unit>(),
            broadcastAddresses = { listOf(loopback) },
        )

        val announcer = UdpPresenceAnnouncer(
            config = config,
            logger = logger,
            transport = transport,
            peers = peers,
            router = router,
            presence = snapshot,
            busy = busy,
            broadcastAddresses = { listOf(loopback) },
        )

        fun startDiscovery() = runBlocking {
            transport.start()
            discovery.start()
        }

        fun startAnnouncing() = runBlocking { announcer.start() }

        fun start() {
            startDiscovery()
            startAnnouncing()
        }

        fun stopAnnouncing() = runBlocking { announcer.stop() }

        fun close() = runBlocking {
            announcer.stop()
            discovery.stop()
            transport.stop()
        }
    }

    private fun node(
        controlPort: Int,
        voicePort: Int,
        name: String,
        heartbeat: Long = 200,
    ): Node {
        val presence = PresenceConfig(
            heartbeatInterval = heartbeat.milliseconds,
            missedIntervalsBeforeOffline = 3,
            timeoutTolerance = (heartbeat / 2).milliseconds,
            evaluationInterval = (heartbeat / 2).milliseconds,
        )
        return Node(controlPort, voicePort, name, presence).also { nodes += it }
    }

    @After
    fun tearDown() {
        nodes.asReversed().forEach { it.close() }
    }

    private fun await(
        registry: PeerRegistry,
        timeoutMillis: Long = 3_000,
        predicate: (List<Peer>) -> Boolean,
    ): List<Peer> {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val snapshot = registry.snapshot()
            if (predicate(snapshot)) return snapshot
            Thread.sleep(10)
        }
        return registry.snapshot()
    }

    private fun stateOf(node: Node, peer: Node): PresenceState? =
        node.peers.peer(peer.deviceId)?.state

    // --- Online -------------------------------------------------------------------

    @Test
    fun `a heartbeat is what turns a discovered peer online`() {
        // Alice takes the nominal port, so Bob's broadcasts reach her.
        val alice = node(47_860, 47_870, "Alice")
        val bob = node(47_860, 47_871, "Bob")
        alice.start()
        // Discovery only, so the two halves can be told apart: an announcement
        // proves a device exists, only a heartbeat proves its periodic channel
        // reaches us.
        bob.startDiscovery()

        await(alice.peers) { it.isNotEmpty() }
        assertEquals(PresenceState.DISCOVERED, stateOf(alice, bob))

        bob.startAnnouncing()

        await(alice.peers) { peers -> peers.any { it.state == PresenceState.ONLINE } }
        assertEquals(PresenceState.ONLINE, stateOf(alice, bob))
        assertEquals(1, alice.peers.snapshot().size)
    }

    @Test
    fun `repeated heartbeats do not duplicate a peer`() {
        val alice = node(47_860, 47_870, "Alice")
        val bob = node(47_860, 47_871, "Bob")
        alice.start()
        bob.start()

        await(alice.peers) { peers -> peers.any { it.state == PresenceState.ONLINE } }
        Thread.sleep(800) // several more periods

        assertEquals(1, alice.peers.snapshot().size)
    }

    // --- Offline and back ------------------------------------------------------------

    @Test
    fun `one missed period is not an offline peer, three are`() {
        val heartbeat = 200L
        val alice = node(47_860, 47_870, "Alice", heartbeat)
        val bob = node(47_860, 47_871, "Bob", heartbeat)
        alice.start()
        bob.start()
        await(alice.peers) { peers -> peers.any { it.state == PresenceState.ONLINE } }

        bob.stopAnnouncing()

        // One and a half periods of silence. WiFi drops broadcasts routinely;
        // a peer list that flickers is worse than one that lags.
        Thread.sleep(heartbeat + heartbeat / 2)
        assertEquals(PresenceState.ONLINE, stateOf(alice, bob))

        // Past three periods plus tolerance, it really is gone.
        await(alice.peers, timeoutMillis = 2_000) { peers ->
            peers.all { it.state == PresenceState.OFFLINE }
        }
        assertEquals(PresenceState.OFFLINE, stateOf(alice, bob))
    }

    @Test
    fun `an offline peer comes straight back on the next heartbeat`() {
        val alice = node(47_860, 47_870, "Alice")
        val bob = node(47_860, 47_871, "Bob")
        alice.start()
        bob.start()
        await(alice.peers) { peers -> peers.any { it.state == PresenceState.ONLINE } }

        bob.stopAnnouncing()
        await(alice.peers, timeoutMillis = 2_000) { peers ->
            peers.all { it.state == PresenceState.OFFLINE }
        }

        runBlocking { bob.announcer.start() }

        await(alice.peers, timeoutMillis = 2_000) { peers ->
            peers.any { it.state == PresenceState.ONLINE }
        }
        assertEquals(PresenceState.ONLINE, stateOf(alice, bob))
        assertEquals(1, alice.peers.snapshot().size)
    }

    @Test
    fun `any valid packet keeps a peer alive, heartbeat or not`() {
        // Section 13: a device that is sending audio is self-evidently there.
        val heartbeat = 200L
        val alice = node(47_860, 47_870, "Alice", heartbeat)
        val bob = node(47_860, 47_871, "Bob", heartbeat)
        alice.start()
        bob.start()
        await(alice.peers) { peers -> peers.any { it.state == PresenceState.ONLINE } }

        bob.stopAnnouncing()

        val ping = Packet.of(
            type = PacketType.PING,
            senderDeviceId = bob.deviceId,
            payload = EmptyPayload,
            timestampMillis = 1_700_000_000_000L,
            targetDeviceId = alice.deviceId,
        ).toBytes()

        // Well past the timeout, but never silent.
        repeat(15) {
            bob.transport.send(
                ping, ping.size, loopback,
                alice.transport.boundPorts!!.controlPort, TransportChannel.CONTROL,
            )
            Thread.sleep(heartbeat / 2)
        }

        assertEquals(PresenceState.ONLINE, stateOf(alice, bob))
    }

    // --- Busy ---------------------------------------------------------------------------

    @Test
    fun `the busy flag is announced immediately, not at the next period`() {
        // Three seconds between heartbeats, so anything that arrives in under a
        // second can only be the extra broadcast the busy change triggered.
        val heartbeat = 3_000L
        val alice = node(47_860, 47_870, "Alice", heartbeat)
        val bob = node(47_860, 47_871, "Bob", heartbeat)
        alice.start()
        bob.start()

        // The service's opening heartbeat, then three seconds of silence: so
        // anything arriving in under a second can only be the extra broadcast
        // the busy change triggered.
        await(alice.peers) { peers -> peers.any { it.state == PresenceState.ONLINE } }

        val startedAt = System.currentTimeMillis()
        bob.busy.value = true

        await(alice.peers, timeoutMillis = 1_000) { peers ->
            peers.any { it.state == PresenceState.BUSY }
        }
        val elapsed = System.currentTimeMillis() - startedAt

        assertEquals(PresenceState.BUSY, stateOf(alice, bob))
        assertTrue("took ${elapsed}ms, which is a whole period", elapsed < heartbeat / 2)
    }

    @Test
    fun `clearing the busy flag is announced just as quickly`() {
        val heartbeat = 3_000L
        val alice = node(47_860, 47_870, "Alice", heartbeat)
        val bob = node(47_860, 47_871, "Bob", heartbeat)
        alice.start()
        bob.start()
        await(alice.peers) { peers -> peers.any { it.state == PresenceState.ONLINE } }

        bob.busy.value = true
        await(alice.peers, timeoutMillis = 1_000) { peers ->
            peers.any { it.state == PresenceState.BUSY }
        }

        bob.busy.value = false
        await(alice.peers, timeoutMillis = 1_000) { peers ->
            peers.none { it.state == PresenceState.BUSY }
        }

        // Back to ONLINE, not DISCOVERED: a heartbeat has been seen by now.
        assertEquals(PresenceState.ONLINE, stateOf(alice, bob))
    }

    @Test
    fun `a device does not see itself as a peer`() {
        val alice = node(47_860, 47_870, "Alice")
        alice.start()
        Thread.sleep(600)

        assertTrue(alice.peers.snapshot().isEmpty())
        assertNotNull(alice.transport.boundPorts)
    }
}
