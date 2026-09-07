package com.saikai.ptt.network

import com.saikai.ptt.core.config.LoggingConfig
import com.saikai.ptt.core.config.NetworkConfig
import com.saikai.ptt.core.config.RateLimitConfig
import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.LogSink
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.EmptyPayload
import com.saikai.ptt.core.protocol.Packet
import com.saikai.ptt.core.protocol.PacketRateLimiter
import com.saikai.ptt.core.protocol.PacketType
import com.saikai.ptt.core.protocol.PacketValidator
import com.saikai.ptt.core.protocol.PeerState
import com.saikai.ptt.core.protocol.PresencePayload
import com.saikai.ptt.core.protocol.ReceivingSession
import com.saikai.ptt.core.protocol.SessionId
import com.saikai.ptt.core.protocol.VoiceDataPayload
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Exercises the transport against real UDP on the loopback interface.
 *
 * Deliberately not against a mock socket. Everything this class can get wrong is
 * in the parts a mock would stand in for: whether the loop survives a malformed
 * datagram, whether the reused buffer keeps its length, and whether closing the
 * sockets is really what lets a thread parked in `receive` come home. A test
 * that mocked those would pass while the app hung on shutdown.
 */
class UdpTransportTest {

    private class Sink : LogSink {
        val entries = mutableListOf<String>()
        override fun write(
            level: LogLevel,
            category: LogCategory,
            message: String,
            throwable: Throwable?,
        ) {
            synchronized(entries) { entries += "$level/$category: $message" }
        }

        fun snapshot(): List<String> = synchronized(entries) { entries.toList() }
    }

    // Well away from the real 45820/45821 so that a running instance of the app,
    // or another test, cannot make this suite flap.
    private val controlPort = 47_820
    private val voicePort = 47_821

    private val local = DeviceId.random()
    private val peer = DeviceId.random()
    private val session = SessionId.random()

    private val sink = Sink()
    private val logger = Logger(LoggingConfig.debug(), sink)

    private val received = LinkedBlockingQueue<Received>()
    @Volatile
    private var listenerThrows = false

    private data class Received(
        val type: PacketType,
        val channel: TransportChannel,
        val sourceAddress: String,
        val frame: ByteArray?,
    )

    private val config = SaikaiConfig(
        network = NetworkConfig(controlPort = controlPort, voicePort = voicePort),
    )

    private val transport = UdpTransport(
        config = config,
        logger = logger,
        validator = PacketValidator(
            localDeviceId = local,
            logger = logger,
            receivingSession = { ReceivingSession(session, peer) },
        ),
        rateLimiter = PacketRateLimiter(RateLimitConfig(), logger),
        listener = { inbound ->
            if (listenerThrows) throw IllegalStateException("listener blew up")
            // The voice payload is a view over the receive buffer, so it has to
            // be copied here, inside the callback. This is the contract.
            val frame = (inbound.packet.payload as? VoiceDataPayload)?.copyFrame()
            received += Received(
                type = inbound.packet.type!!,
                channel = inbound.channel,
                sourceAddress = inbound.sourceAddress,
                frame = frame,
            )
        },
    )

    private val sender = DatagramSocket()
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    @After
    fun tearDown() {
        runBlocking { transport.stop() }
        sender.close()
    }

    private fun sendTo(port: Int, bytes: ByteArray, length: Int = bytes.size) {
        sender.send(DatagramPacket(bytes, length, loopback, port))
    }

    private fun heartbeat(name: String = "Kenji") = Packet.of(
        type = PacketType.HEARTBEAT,
        senderDeviceId = peer,
        payload = PresencePayload(PeerState.IDLE, voicePort, name),
        timestampMillis = 1_700_000_000_000L,
    ).toBytes()

    private fun ping() = Packet.of(
        type = PacketType.PING,
        senderDeviceId = peer,
        payload = EmptyPayload,
        timestampMillis = 1_700_000_000_000L,
        targetDeviceId = local,
    ).toBytes()

    private fun voice(frame: ByteArray) = Packet.of(
        type = PacketType.VOICE_DATA,
        senderDeviceId = peer,
        payload = VoiceDataPayload(frame),
        timestampMillis = 1_700_000_000_000L,
        targetDeviceId = local,
        sessionId = session,
        sequenceNumber = 1,
    ).toBytes()

    private fun awaitPacket(): Received? = received.poll(3, TimeUnit.SECONDS)

    // --- Lifecycle ---------------------------------------------------------------

    @Test
    fun `start binds both ports and reports them`() {
        val ports = transport.start().valueOrNull()!!

        assertEquals(controlPort, ports.controlPort)
        assertEquals(voicePort, ports.voicePort)
        assertTrue(ports.isDiscoverable)
        assertTrue(transport.isRunning)
    }

    @Test
    fun `starting twice is refused`() {
        transport.start()
        assertEquals(TransportError.ALREADY_RUNNING, transport.start().errorOrNull())
    }

    @Test
    fun `stopping closes the sockets and releases the ports`() = runBlocking {
        transport.start()
        transport.stop()

        assertFalse(transport.isRunning)
        assertNull(transport.boundPorts)

        // If a receive thread were still holding a socket, this would throw.
        DatagramSocket(null).use {
            it.bind(InetSocketAddress(controlPort))
        }
        DatagramSocket(null).use {
            it.bind(InetSocketAddress(voicePort))
        }
    }

    @Test
    fun `stopping is safe when the transport never started, and twice over`() = runBlocking {
        transport.stop()
        transport.start()
        transport.stop()
        transport.stop()
        assertFalse(transport.isRunning)
    }

    @Test
    fun `a taken control port drifts, loudly`() {
        DatagramSocket(null).use { occupier ->
            occupier.bind(InetSocketAddress(controlPort))

            val ports = transport.start().valueOrNull()!!

            assertEquals(controlPort + 1, ports.controlPort)
            assertFalse(
                "a device on a non-default control port cannot be discovered",
                ports.isDiscoverable,
            )
            assertTrue(sink.snapshot().any { it.startsWith("WARN/NETWORK") })
        }
    }

    @Test
    fun `every candidate port taken is an error, not a crash`() {
        val occupiers = (0 until config.network.portProbeAttempts).map { offset ->
            DatagramSocket(null).apply { bind(InetSocketAddress(controlPort + offset)) }
        }
        try {
            assertEquals(
                TransportError.CONTROL_PORT_UNAVAILABLE,
                transport.start().errorOrNull(),
            )
            assertFalse(transport.isRunning)
        } finally {
            occupiers.forEach { it.close() }
        }
    }

    // --- Receiving -----------------------------------------------------------------

    @Test
    fun `a control packet reaches the listener`() {
        transport.start()
        sendTo(controlPort, heartbeat())

        val received = awaitPacket()
        assertNotNull(received)
        assertEquals(PacketType.HEARTBEAT, received!!.type)
        assertEquals(TransportChannel.CONTROL, received.channel)
        assertEquals("127.0.0.1", received.sourceAddress)
    }

    @Test
    fun `a voice packet reaches the listener on the voice channel`() {
        transport.start()
        val frame = ByteArray(53) { (it * 3).toByte() }
        sendTo(voicePort, voice(frame))

        val received = awaitPacket()
        assertNotNull(received)
        assertEquals(PacketType.VOICE_DATA, received!!.type)
        assertEquals(TransportChannel.VOICE, received.channel)
        assertArrayEquals(frame, received.frame)
    }

    @Test
    fun `the two channels are independent`() {
        transport.start()
        sendTo(voicePort, voice(ByteArray(20) { 1 }))
        sendTo(controlPort, ping())

        val channels = listOf(awaitPacket(), awaitPacket()).map { it?.channel }.toSet()
        assertEquals(setOf(TransportChannel.CONTROL, TransportChannel.VOICE), channels)
    }

    // --- Isolation --------------------------------------------------------------------

    @Test
    fun `a malformed datagram is dropped and the loop carries on`() {
        transport.start()

        sendTo(controlPort, ByteArray(10))
        sendTo(controlPort, ByteArray(200) { 0xFF.toByte() })
        sendTo(controlPort, heartbeat().also { it[3] = 0x00 }) // broken magic
        sendTo(controlPort, heartbeat())

        val received = awaitPacket()
        assertNotNull("the loop stopped after a bad packet", received)
        assertEquals(PacketType.HEARTBEAT, received!!.type)
    }

    @Test
    fun `a datagram larger than the receive buffer does not end the loop`() {
        transport.start()

        // Truncated to the 2048-byte buffer on arrival, so the declared payload
        // length cannot match and validation drops it.
        sendTo(controlPort, ByteArray(3_000) { 0x7F })
        sendTo(controlPort, heartbeat())

        assertEquals(PacketType.HEARTBEAT, awaitPacket()?.type)
    }

    @Test
    fun `a small datagram does not shrink the reused buffer`() {
        // DatagramPacket.receive sets the packet length to what arrived. Without
        // resetting it, the buffer would permanently shrink to the size of the
        // smallest datagram ever seen and every later packet would be truncated.
        transport.start()

        sendTo(controlPort, ByteArray(1))
        sendTo(controlPort, heartbeat(name = "a".repeat(60)))

        val received = awaitPacket()
        assertNotNull("the reused buffer was shrunk by the one-byte datagram", received)
        assertEquals(PacketType.HEARTBEAT, received!!.type)
    }

    @Test
    fun `a listener that throws does not end the loop`() {
        transport.start()

        listenerThrows = true
        sendTo(controlPort, heartbeat())
        Thread.sleep(200)

        listenerThrows = false
        sendTo(controlPort, heartbeat())

        assertEquals(PacketType.HEARTBEAT, awaitPacket()?.type)
        assertTrue(sink.snapshot().any { it.startsWith("ERROR/NETWORK") })
    }

    @Test
    fun `this device's own broadcast is dropped without reaching the listener`() {
        transport.start()

        val ownPacket = Packet.of(
            type = PacketType.HEARTBEAT,
            senderDeviceId = local,
            payload = PresencePayload(PeerState.IDLE, voicePort, "me"),
            timestampMillis = 1_700_000_000_000L,
        ).toBytes()
        sendTo(controlPort, ownPacket)
        sendTo(controlPort, heartbeat())

        // The echo is not delivered, so the first thing to arrive is the peer's.
        assertEquals(PacketType.HEARTBEAT, awaitPacket()?.type)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `a control packet sent to the voice port is refused`() {
        // The rate limiter only meters the control channel. Without this check,
        // sending control traffic to the voice port would be a way around it.
        transport.start()

        sendTo(voicePort, heartbeat())
        sendTo(controlPort, heartbeat())

        val received = awaitPacket()
        assertEquals(TransportChannel.CONTROL, received?.channel)
        assertTrue(this.received.isEmpty())
    }

    @Test
    fun `a voice frame sent to the control port is refused`() {
        transport.start()

        sendTo(controlPort, voice(ByteArray(20) { 5 }))
        sendTo(voicePort, voice(ByteArray(20) { 6 }))

        val received = awaitPacket()
        assertEquals(TransportChannel.VOICE, received?.channel)
        assertArrayEquals(ByteArray(20) { 6 }, received?.frame)
        assertTrue(this.received.isEmpty())
    }

    // --- Sending ------------------------------------------------------------------------

    @Test
    fun `send fails cleanly when the transport is not running`() {
        assertFalse(transport.send(ByteArray(1), 1, loopback, controlPort, TransportChannel.CONTROL))
    }

    @Test
    fun `a sent datagram arrives with the bytes it was given`() {
        val receiver = DatagramSocket(null).apply { bind(InetSocketAddress(0)) }
        receiver.soTimeout = 3_000
        try {
            transport.start()
            val payload = heartbeat()

            assertTrue(
                transport.send(
                    payload, payload.size, loopback, receiver.localPort, TransportChannel.CONTROL,
                )
            )

            val buffer = ByteArray(2048)
            val datagram = DatagramPacket(buffer, buffer.size)
            receiver.receive(datagram)

            assertEquals(payload.size, datagram.length)
            assertArrayEquals(payload, buffer.copyOf(datagram.length))
        } finally {
            receiver.close()
        }
    }

    @Test
    fun `the reused outgoing packet does not leak the previous datagram's length`() {
        // 03_Protocol section 13 forbids rebuilding the DatagramPacket per send,
        // so the same object carries a long datagram and then a short one.
        val receiver = DatagramSocket(null).apply { bind(InetSocketAddress(0)) }
        receiver.soTimeout = 3_000
        try {
            transport.start()
            val long = heartbeat(name = "a".repeat(60))
            val short = ping()

            transport.send(long, long.size, loopback, receiver.localPort, TransportChannel.CONTROL)
            transport.send(short, short.size, loopback, receiver.localPort, TransportChannel.CONTROL)

            val buffer = ByteArray(2048)
            receiver.receive(DatagramPacket(buffer, buffer.size))
            val second = DatagramPacket(buffer, buffer.size)
            receiver.receive(second)

            assertEquals(short.size, second.length)
            assertArrayEquals(short, buffer.copyOf(second.length))
        } finally {
            receiver.close()
        }
    }
}
