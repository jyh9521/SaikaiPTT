package com.saikai.ptt.service

import com.saikai.ptt.core.common.LifecycleStep
import com.saikai.ptt.core.config.LoggingConfig
import com.saikai.ptt.core.config.PresenceConfig
import com.saikai.ptt.core.config.RateLimitConfig
import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.domain.PeerEndpoint
import com.saikai.ptt.core.domain.PeerObservation
import com.saikai.ptt.core.domain.PeerRegistry
import com.saikai.ptt.core.domain.PresenceKind
import com.saikai.ptt.core.domain.PresenceState
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.LogSink
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.network.NetworkLink
import com.saikai.ptt.network.NetworkLinkSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WiFi going away and coming back.
 *
 * Driven by a fake link source rather than by unplugging a phone. Everything
 * that matters here is ordering -- what is released, in what order, what is
 * started again, and what is deliberately left alone -- and none of it is
 * reachable from a JVM test through ConnectivityManager.
 */
class NetworkRecoveryTest {

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

    private val log = mutableListOf<String>()

    private inner class FakeStep(override val name: String) : LifecycleStep {
        var startCount = 0
        var stopCount = 0
        override suspend fun start() {
            startCount++
            log += "start:$name"
        }

        override suspend fun stop() {
            stopCount++
            log += "stop:$name"
        }
    }

    private class FakeLinks : NetworkLinkSource {
        private val _link = MutableStateFlow(NetworkLink.NONE)
        override val link: StateFlow<NetworkLink> = _link.asStateFlow()
        var started = false
            private set

        override fun start() {
            started = true
        }

        override fun stop() {
            started = false
        }

        fun emit(value: NetworkLink) {
            _link.value = value
        }
    }

    private val notification = FakeStep("foreground")
    private val transport = FakeStep("udp-transport")
    private val discovery = FakeStep("discovery")

    private val lifecycle = ServiceLifecycle(
        listOf(notification, transport, discovery),
        logger,
    )
    private val peers = PeerRegistry(PresenceConfig(), RateLimitConfig(), logger)
    private val links = FakeLinks()
    private val scope = CoroutineScope(SupervisorJob())

    private var sessionsEnded = 0

    private val recovery = NetworkRecovery(
        monitor = links,
        lifecycle = lifecycle,
        peers = peers,
        logger = logger,
        firstNetworkStep = "udp-transport",
        onNetworkLost = { sessionsEnded++ },
    )

    private val peerId = DeviceId.random()

    @After
    fun tearDown() {
        runBlocking { recovery.stop() }
        scope.cancel()
    }

    private fun connected(vararg addresses: String) =
        NetworkLink(available = true, addresses = addresses.toList())

    private fun bringUp() = runBlocking {
        lifecycle.start()
        recovery.start(scope)
        links.emit(connected("192.168.1.10"))
        settle()
        seePeer()
        log.clear()
    }

    private fun seePeer() {
        peers.onPresence(
            PeerObservation(
                deviceId = peerId,
                userName = "Kenji",
                endpoint = PeerEndpoint("192.168.1.20", 45_821),
                protocolVersion = 1,
                remoteBusy = false,
                kind = PresenceKind.HEARTBEAT,
            )
        )
    }

    /** The collector runs on another coroutine; give it a moment to catch up. */
    private fun settle(millis: Long = 200) = Thread.sleep(millis)

    // --- Losing the network -------------------------------------------------------

    @Test
    fun `losing the network releases the network half and nothing else`() {
        bringUp()

        links.emit(NetworkLink.NONE)
        settle()

        assertEquals(listOf("stop:discovery", "stop:udp-transport"), log)
        assertEquals(0, notification.stopCount)
        assertEquals(ServiceState.DEGRADED, lifecycle.state.value)
    }

    @Test
    fun `every peer goes offline the moment the network does`() {
        // Not sixteen seconds later, after a sweep that could not have heard
        // anything anyway.
        bringUp()
        assertEquals(PresenceState.ONLINE, peers.peer(peerId)!!.state)

        links.emit(NetworkLink.NONE)
        settle()

        assertEquals(PresenceState.OFFLINE, peers.peer(peerId)!!.state)
        // The record survives: a list that empties and refills is a worse answer
        // to "did my radio just disappear" than one that greys out.
        assertEquals("Kenji", peers.peer(peerId)!!.userName)
    }

    @Test
    fun `a session is ended before the sockets are taken away`() {
        bringUp()

        links.emit(NetworkLink.NONE)
        settle()

        assertEquals(1, sessionsEnded)
    }

    // --- Getting it back ------------------------------------------------------------

    @Test
    fun `the network coming back restores the same steps, in order`() {
        bringUp()
        links.emit(NetworkLink.NONE)
        settle()
        log.clear()

        links.emit(connected("192.168.1.10"))
        settle()

        assertEquals(listOf("start:udp-transport", "start:discovery"), log)
        assertEquals(ServiceState.READY, lifecycle.state.value)
        assertEquals(1, notification.startCount)
    }

    @Test
    fun `a peer heard from again comes back online`() {
        bringUp()
        links.emit(NetworkLink.NONE)
        settle()
        assertEquals(PresenceState.OFFLINE, peers.peer(peerId)!!.state)

        links.emit(connected("192.168.1.10"))
        settle()
        seePeer()

        assertEquals(PresenceState.ONLINE, peers.peer(peerId)!!.state)
    }

    @Test
    fun `the device identity survives the whole cycle`() {
        // An address is not an identity. A phone that moves between access
        // points is the same radio to everyone who was talking to it.
        bringUp()
        val before = peers.peer(peerId)!!.deviceId

        links.emit(NetworkLink.NONE)
        settle()
        links.emit(connected("10.0.0.5"))
        settle()
        seePeer()

        assertEquals(before, peers.peer(peerId)!!.deviceId)
        assertEquals(1, peers.snapshot().size)
    }

    // --- Renumbering ------------------------------------------------------------------

    @Test
    fun `being renumbered is treated as a recovery even though nothing was lost`() {
        // A DHCP change can move this device to another subnet with the network
        // never reporting a loss, and every peer's idea of where to send voice
        // is then wrong.
        bringUp()

        links.emit(connected("10.0.0.5"))
        settle()

        assertEquals(
            listOf(
                "stop:discovery",
                "stop:udp-transport",
                "start:udp-transport",
                "start:discovery",
            ),
            log,
        )
        assertEquals(ServiceState.READY, lifecycle.state.value)
    }

    @Test
    fun `the same reading twice does nothing`() {
        bringUp()

        links.emit(connected("192.168.1.10"))
        links.emit(connected("192.168.1.10"))
        settle()

        assertTrue("nothing should have been cycled, log was $log", log.isEmpty())
    }

    // --- Repetition and shutdown --------------------------------------------------------

    @Test
    fun `repeated drops and recoveries leave nothing stranded`() {
        bringUp()

        repeat(5) {
            links.emit(NetworkLink.NONE)
            settle(60)
            links.emit(connected("192.168.1.10"))
            settle(60)
        }

        assertEquals(6, transport.startCount)
        assertEquals(5, transport.stopCount)
        assertEquals(1, notification.startCount)
        assertEquals(0, notification.stopCount)
        assertEquals(ServiceState.READY, lifecycle.state.value)
    }

    @Test
    fun `stopping unhooks the watcher and the source`() {
        bringUp()
        assertTrue(links.started)

        runBlocking { recovery.stop() }
        log.clear()

        links.emit(NetworkLink.NONE)
        settle()

        assertTrue("recovery kept reacting after stop, log was $log", log.isEmpty())
        assertEquals(ServiceState.READY, lifecycle.state.value)
    }
}
