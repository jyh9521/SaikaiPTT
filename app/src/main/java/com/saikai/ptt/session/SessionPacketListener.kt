package com.saikai.ptt.session

import com.saikai.ptt.core.domain.PeerEndpoint
import com.saikai.ptt.core.domain.PeerRegistry
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.PacketType
import com.saikai.ptt.core.protocol.SessionTerminatePayload
import com.saikai.ptt.core.protocol.VoiceDataPayload
import com.saikai.ptt.core.protocol.VoiceEndPayload
import com.saikai.ptt.core.protocol.VoiceStartPayload
import com.saikai.ptt.core.session.SessionManager
import com.saikai.ptt.core.session.VoiceReceiver
import com.saikai.ptt.network.InboundPacket
import com.saikai.ptt.network.InboundPacketListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Turns the session packets that arrive into calls on the state machine.
 *
 * The machine's inbound methods suspend, because every one of them takes the
 * lock that arbitrates session ownership, and the receive threads must never
 * block: the control thread is the only thing keeping this device on the
 * network, and the voice thread is holding up the next frame. So each packet is
 * handed to a coroutine and the thread goes back to `receive()`.
 *
 * That does mean two VOICE_STARTs arriving together can be *processed* in either
 * order. It does not mean they can both succeed -- the machine decides and
 * replies inside one critical section, which is the whole point of
 * `docs/03_Protocol.md` section 34 -- and which of two simultaneous callers wins
 * was never defined anyway.
 *
 * VOICE_DATA is the exception and is handled inline. It carries no decision,
 * only "this session is still alive", and launching a coroutine fifty times a
 * second to record a timestamp would cost more than the work.
 *
 * The endpoint comes from where the datagram actually arrived, never from
 * anything inside it (`docs/03_Protocol.md` section 11). The voice port is the
 * one the peer announced, which is why the peer table is consulted rather than
 * the source port of the control packet.
 */
class SessionPacketListener(
    private val sessions: SessionManager,
    private val receiver: VoiceReceiver,
    private val peers: PeerRegistry,
    private val logger: Logger,
    private val scope: CoroutineScope,
) : InboundPacketListener {

    override fun onPacket(inbound: InboundPacket) {
        val header = inbound.packet.header
        val sender = header.senderDeviceId

        if (inbound.packet.type == PacketType.VOICE_DATA) {
            sessions.onVoiceFrame(header.sessionId)
            val payload = inbound.packet.payload as? VoiceDataPayload ?: return
            // The payload is a view over the receive buffer, which is refilled
            // as soon as this returns. The jitter buffer copies it in.
            receiver.onFrame(
                sessionId = header.sessionId,
                sequence = header.sequenceNumber,
                frame = payload.frame,
                offset = payload.frameOffset,
                length = payload.frameLength,
            )
            return
        }

        when (inbound.packet.type) {
            PacketType.VOICE_START -> {
                val payload = inbound.packet.payload as? VoiceStartPayload ?: return
                val endpoint = endpointOf(inbound) ?: run {
                    // A VOICE_START from a device that is not in the peer table
                    // has no announced voice port, so there is nowhere to send
                    // the audio even if this device accepted.
                    logger.w(LogCategory.SESSION) { "VOICE_START from an unknown peer; ignored" }
                    return
                }
                scope.launch {
                    sessions.onVoiceStart(sender, header.sessionId, payload, endpoint)
                }
            }

            PacketType.VOICE_ACCEPT -> scope.launch {
                sessions.onVoiceAccept(sender, header.sessionId)
            }

            PacketType.BUSY -> scope.launch { sessions.onBusy(sender, header.sessionId) }

            PacketType.VOICE_END -> {
                // Play out what is held *before* handing the machine the end of
                // the session: its first act is to take the speaker away, and
                // the frames still in the buffer are the last of the sentence.
                val payload = inbound.packet.payload as? VoiceEndPayload
                if (payload != null) {
                    receiver.flush(
                        sessionId = header.sessionId,
                        finalDataSequence = payload.finalDataSequence,
                        frameCount = payload.frameCount,
                    )
                }
                scope.launch { sessions.onVoiceEnd(sender, header.sessionId) }
            }

            PacketType.SESSION_TERMINATE -> {
                val payload = inbound.packet.payload as? SessionTerminatePayload ?: return
                scope.launch {
                    sessions.onSessionTerminate(sender, header.sessionId, payload.reason)
                }
            }

            else -> Unit
        }
    }

    private fun endpointOf(inbound: InboundPacket): PeerEndpoint? {
        val known = peers.peer(inbound.packet.header.senderDeviceId) ?: return null
        return PeerEndpoint(inbound.sourceAddress, known.endpoint.voicePort)
    }
}
