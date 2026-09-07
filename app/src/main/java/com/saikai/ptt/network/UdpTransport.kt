package com.saikai.ptt.network

import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.PacketRateLimiter
import com.saikai.ptt.core.protocol.PacketType
import com.saikai.ptt.core.protocol.PacketValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.Executors

/** The ports the transport actually managed to bind. */
data class BoundPorts(
    val controlPort: Int,
    val voicePort: Int,
    val configuredControlPort: Int,
) {
    /**
     * False when the control port drifted.
     *
     * Peers broadcast discovery to the configured port and nowhere else, so a
     * device listening anywhere else is invisible to them. The transport still
     * starts -- a second instance on one machine is useful during development --
     * but this must never be a silent condition.
     */
    val isDiscoverable: Boolean get() = controlPort == configuredControlPort
}

/** Why the transport would not start. */
enum class TransportError {
    /** Every candidate control port was taken. */
    CONTROL_PORT_UNAVAILABLE,

    /** Every candidate voice port was taken. */
    VOICE_PORT_UNAVAILABLE,

    /** [UdpTransport.start] called twice without a [UdpTransport.stop] between. */
    ALREADY_RUNNING,
}

/**
 * The two UDP sockets and the two receive loops behind them.
 *
 * `docs/ADR/ADR-002-Transport-And-Ports.md`: control and voice are separate
 * sockets on separate threads. Voice arrives 50 times a second and control about
 * once; sharing a thread would make every peer-table update and state-machine
 * transition a source of jitter in somebody's audio.
 *
 * Both loops run the same pipeline -- decode, validate, dispatch -- because there
 * is exactly one definition of a valid packet and it is [PacketValidator]. The
 * only difference between the channels is the rate limiter: control traffic is
 * limited per source (`docs/03_Protocol.md` section 45), voice is not, because
 * its rate is already bounded by the session checks inside validation and a limit
 * there would risk cutting off a real call under packet loss.
 *
 * Each loop owns one thread and one 2048-byte buffer for the life of the
 * transport, and allocates nothing per packet.
 *
 * **Stopping closes the sockets before it cancels.** A thread parked in
 * `DatagramSocket.receive` is not interruptible by coroutine cancellation, or by
 * anything else; closing the socket is what makes the call return. Cancelling
 * first and closing afterwards would hang [stop] until the next datagram arrived,
 * which on a quiet network is never.
 *
 * On Android, receiving broadcasts also needs a `MulticastLock` held by the
 * service (`docs/ADR/ADR-005`). That is deliberately not this class's business:
 * the lock is a power resource with a lifecycle tied to the service, and the
 * transport is plain JVM code so that it can be tested without a device.
 */
class UdpTransport(
    private val config: SaikaiConfig,
    private val logger: Logger,
    private val validator: PacketValidator,
    private val rateLimiter: PacketRateLimiter,
    private val listener: InboundPacketListener,
) {

    private val lock = Any()
    private var running: Running? = null

    /** The bound ports, or null when the transport is not running. */
    val boundPorts: BoundPorts? get() = synchronized(lock) { running?.ports }

    val isRunning: Boolean get() = synchronized(lock) { running != null }

    /**
     * Binds both sockets and starts both receive loops.
     *
     * Never throws: a port that will not bind is an ordinary condition on a
     * device where another app, or a previous instance of this one, got there
     * first.
     */
    fun start(): Outcome<BoundPorts, TransportError> = synchronized(lock) {
        if (running != null) return Outcome.failure(TransportError.ALREADY_RUNNING)

        val network = config.network
        val control = bind(network.controlPort, broadcast = true)
            ?: return Outcome.failure(TransportError.CONTROL_PORT_UNAVAILABLE)
        val voice = bind(network.voicePort, broadcast = false)
        if (voice == null) {
            control.close()
            return Outcome.failure(TransportError.VOICE_PORT_UNAVAILABLE)
        }

        val ports = BoundPorts(
            controlPort = control.localPort,
            voicePort = voice.localPort,
            configuredControlPort = network.controlPort,
        )
        if (!ports.isDiscoverable) {
            logger.w(LogCategory.NETWORK) {
                "control port ${network.controlPort} was taken; bound ${ports.controlPort} " +
                    "instead. Peers broadcast to ${network.controlPort}, so this device will " +
                    "not be discovered."
            }
        }

        val job = SupervisorJob()
        val scope = CoroutineScope(job)
        val controlDispatcher = dispatcherNamed(CONTROL_THREAD)
        val voiceDispatcher = dispatcherNamed(VOICE_THREAD)

        scope.launch(controlDispatcher) { receiveLoop(control, TransportChannel.CONTROL) }
        scope.launch(voiceDispatcher) { receiveLoop(voice, TransportChannel.VOICE) }

        running = Running(control, voice, ports, job, controlDispatcher, voiceDispatcher)
        logger.i(LogCategory.NETWORK) {
            "transport up: control ${ports.controlPort}, voice ${ports.voicePort}"
        }
        return Outcome.success(ports)
    }

    /**
     * Closes both sockets and waits for both loops to finish.
     *
     * Safe to call when not running, and safe to call twice.
     */
    suspend fun stop() {
        val current = synchronized(lock) { running.also { running = null } } ?: return

        // Order matters; see the class comment.
        current.controlSocket.close()
        current.voiceSocket.close()
        current.job.cancelAndJoin()
        current.controlDispatcher.close()
        current.voiceDispatcher.close()

        logger.i(LogCategory.NETWORK) { "transport down" }
    }

    /**
     * Sends one datagram.
     *
     * Lives here because the sockets do. Discovery, heartbeat and the session
     * packets all need to send, and the alternative -- letting each of them open
     * its own socket -- would mean replies arriving on a port no peer was told
     * about.
     *
     * The [DatagramPacket] is reused rather than rebuilt, which
     * `docs/03_Protocol.md` section 13 requires of the heartbeat path
     * specifically. Sends on one channel serialise against each other; the two
     * channels do not contend.
     *
     * @return false when the transport is not running or the datagram could not
     *   be handed to the network. Never throws: a send that fails while WiFi is
     *   dropping is expected, and the recovery path is the caller's.
     */
    fun send(
        bytes: ByteArray,
        length: Int,
        address: InetAddress,
        port: Int,
        channel: TransportChannel,
    ): Boolean {
        require(length in 0..bytes.size) { "length $length is outside a ${bytes.size}-byte buffer" }
        val current = synchronized(lock) { running } ?: return false
        val socket = when (channel) {
            TransportChannel.CONTROL -> current.controlSocket
            TransportChannel.VOICE -> current.voiceSocket
        }
        val outgoing = when (channel) {
            TransportChannel.CONTROL -> current.controlOutgoing
            TransportChannel.VOICE -> current.voiceOutgoing
        }
        return try {
            synchronized(outgoing) {
                outgoing.setData(bytes, 0, length)
                outgoing.address = address
                outgoing.port = port
                socket.send(outgoing)
            }
            true
        } catch (_: IOException) {
            logger.throttled(LogLevel.WARN, LogCategory.NETWORK, "send-failed") {
                "could not send ${length}B to $address:$port on $channel"
            }
            false
        }
    }

    private fun bind(preferredPort: Int, broadcast: Boolean): DatagramSocket? {
        for (offset in 0 until config.network.portProbeAttempts) {
            val port = preferredPort + offset
            if (port > MAX_PORT) break
            val socket = DatagramSocket(null)
            try {
                socket.broadcast = broadcast
                socket.bind(InetSocketAddress(port))
                return socket
            } catch (_: IOException) {
                socket.close()
            }
        }
        return null
    }

    /**
     * One thread per loop, named for the log.
     *
     * Not `Dispatchers.IO`: these two loops are permanently parked in a blocking
     * read, and handing them shared pool threads would take two workers out of
     * the pool for the life of the app while giving the thread dump nothing to
     * identify them by. ADR-002 names the threads; this is where they get named.
     */
    private fun dispatcherNamed(name: String): ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private fun CoroutineScope.receiveLoop(socket: DatagramSocket, channel: TransportChannel) {
        val buffer = ByteArray(config.network.receiveBufferBytes)
        val datagram = DatagramPacket(buffer, buffer.size)

        while (isActive) {
            try {
                // receive() sets the length to what arrived, so it has to be put
                // back before the next call or the buffer silently shrinks to the
                // size of the smallest datagram ever seen.
                datagram.setData(buffer, 0, buffer.size)
                socket.receive(datagram)
            } catch (_: SocketException) {
                return // Closed by stop(). The expected way out.
            } catch (e: IOException) {
                logger.throttled(LogLevel.WARN, LogCategory.NETWORK, "receive-io", e) {
                    "$channel receive failed"
                }
                continue
            }
            handle(datagram, channel)
        }
    }

    /**
     * Decode, validate, dispatch -- for one datagram, on the receive thread.
     *
     * Everything is inside one try. A single malformed packet, or a listener that
     * throws, must not end the loop: the loop is the only thing keeping this
     * device on the network, and the packet that killed it would have come from
     * some other device entirely.
     */
    private fun handle(datagram: DatagramPacket, channel: TransportChannel) {
        try {
            val address = datagram.address?.hostAddress ?: return
            val isControl = channel == TransportChannel.CONTROL

            if (isControl && !rateLimiter.admit(address)) return

            val outcome = validator.validate(datagram.data, datagram.offset, datagram.length)
            if (isControl) rateLimiter.record(address, outcome)

            val packet = outcome.valueOrNull() ?: return

            // A packet has to arrive on the socket ADR-002 assigns it. This is
            // not tidiness: the rate limiter only meters the control channel,
            // because voice is bounded by the session checks instead. Accepting
            // control packets on the voice port would be a way around the limiter
            // entirely, so a misdirected packet is charged to its source -- where
            // it will silence them on the channel that can actually be flooded.
            val belongsHere = (packet.type == PacketType.VOICE_DATA) == !isControl
            if (!belongsHere) {
                rateLimiter.recordInvalid(address)
                logger.throttled(LogLevel.DEBUG, LogCategory.NETWORK, "wrong-channel") {
                    "${packet.type} arrived on $channel from $address"
                }
                return
            }

            listener.onPacket(InboundPacket(packet, address, datagram.port, channel))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            logger.throttled(LogLevel.ERROR, LogCategory.NETWORK, "receive-dispatch", t) {
                "$channel packet handling failed"
            }
        }
    }

    private class Running(
        val controlSocket: DatagramSocket,
        val voiceSocket: DatagramSocket,
        val ports: BoundPorts,
        val job: Job,
        val controlDispatcher: ExecutorCoroutineDispatcher,
        val voiceDispatcher: ExecutorCoroutineDispatcher,
    ) {
        val controlOutgoing: DatagramPacket = DatagramPacket(ByteArray(0), 0)
        val voiceOutgoing: DatagramPacket = DatagramPacket(ByteArray(0), 0)
    }

    private companion object {
        const val CONTROL_THREAD = "control-rx"
        const val VOICE_THREAD = "voice-rx"
        const val MAX_PORT = 65_535
    }
}
