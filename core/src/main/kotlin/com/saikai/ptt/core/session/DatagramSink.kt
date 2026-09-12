package com.saikai.ptt.core.session

/**
 * Somewhere to put a finished datagram.
 *
 * The one seam between the send pipeline and a socket. Everything above it --
 * sequence numbers, the pre-roll buffer, when a frame goes out and in what
 * order -- is ordinary Kotlin and is tested on the JVM, which is the same
 * reason `SessionSignals` exists for the state machine. A pipeline that could
 * only be exercised by two phones in a room would be a pipeline whose ordering
 * bugs are found by users.
 *
 * Both methods take a caller-owned buffer and a length, and both must copy or
 * transmit before returning: the array is reused for the next packet.
 *
 * The control port is not a parameter. Every device listens for control traffic
 * on the one port ADR-002 fixes, and a peer that had to be *told* which port to
 * answer on would be a peer that could be told the wrong one.
 *
 * @return false when the datagram could not be handed to the network. Never
 *   throws: a send failing while WiFi drops is expected, and the caller has a
 *   session to keep consistent rather than a stack to unwind.
 */
interface DatagramSink {

    /** Sends on the control channel, to the peer's fixed control port. */
    fun sendControl(bytes: ByteArray, length: Int, address: String): Boolean

    /** Sends on the voice channel, to the port the peer announced. */
    fun sendVoice(bytes: ByteArray, length: Int, address: String, port: Int): Boolean
}

/** A sink that drops everything and says it worked. For tests and for a disconnected build. */
object NoDatagramSink : DatagramSink {
    override fun sendControl(bytes: ByteArray, length: Int, address: String): Boolean = true
    override fun sendVoice(bytes: ByteArray, length: Int, address: String, port: Int): Boolean =
        true
}
