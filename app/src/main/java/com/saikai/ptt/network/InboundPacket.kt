package com.saikai.ptt.network

import com.saikai.ptt.core.protocol.Packet
import com.saikai.ptt.core.protocol.VoiceDataPayload

/** Which socket a packet arrived on (`docs/ADR/ADR-002-Transport-And-Ports.md`). */
enum class TransportChannel {
    /** UDP 45820. Discovery, heartbeat, and everything that sets up a session. */
    CONTROL,

    /** The floating voice port. VOICE_DATA and nothing else. */
    VOICE,
}

/**
 * A validated packet, with where it came from.
 *
 * The source address is kept separate from anything in the packet on purpose. A
 * device announces its own voice port but never its address, and the address a
 * datagram actually arrived from is the only trustworthy one -- it is what
 * `docs/03_Protocol.md` section 11 updates a peer's endpoint from.
 *
 * **The payload of a VOICE_DATA packet is a view over the receive buffer**, which
 * the loop reuses for the next datagram the moment the listener returns. Anything
 * that outlives the callback must call [VoiceDataPayload.copyFrame] first. This
 * is what keeps the receive path free of a 50-times-a-second allocation, and it
 * is the one sharp edge in the whole transport.
 */
data class InboundPacket(
    val packet: Packet,
    val sourceAddress: String,
    val sourcePort: Int,
    val channel: TransportChannel,
)

/**
 * Where validated packets go.
 *
 * Called on the receive thread, so an implementation must return quickly and must
 * not block: on the voice channel it is holding up the next frame. Throwing is
 * survivable -- the loop logs and carries on -- but it means the packet is lost.
 */
fun interface InboundPacketListener {
    fun onPacket(inbound: InboundPacket)
}
