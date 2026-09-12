package com.saikai.ptt.network

import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.session.DatagramSink
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * [DatagramSink] over the real sockets.
 *
 * The whole of the adapter: the send pipeline in `core.session` decides what
 * bytes to send and to whom, and this turns a peer's address string into an
 * `InetAddress` and picks the channel. Everything worth testing is on the other
 * side of the interface.
 *
 * Addresses arrive as the literal a datagram was received from, so resolving one
 * never touches DNS. It is still wrapped: a peer record can outlive the network
 * it came from, and a lookup that throws on the capture thread would end the
 * transmission rather than one frame of it.
 */
class UdpDatagramSink(
    private val config: SaikaiConfig,
    private val logger: Logger,
    private val transport: UdpTransport,
) : DatagramSink {

    override fun sendControl(bytes: ByteArray, length: Int, address: String): Boolean {
        val target = resolve(address) ?: return false
        return transport.send(
            bytes = bytes,
            length = length,
            address = target,
            // Control always goes to the fixed port of ADR-002. Only the voice
            // port floats, and only because a device announces the one it got.
            port = config.network.controlPort,
            channel = TransportChannel.CONTROL,
        )
    }

    override fun sendVoice(
        bytes: ByteArray,
        length: Int,
        address: String,
        port: Int,
    ): Boolean {
        val target = resolve(address) ?: return false
        return transport.send(bytes, length, target, port, TransportChannel.VOICE)
    }

    private fun resolve(address: String): InetAddress? = try {
        InetAddress.getByName(address)
    } catch (_: UnknownHostException) {
        logger.throttled(LogLevel.WARN, LogCategory.NETWORK, "unresolvable-peer") {
            "cannot resolve $address"
        }
        null
    }
}
