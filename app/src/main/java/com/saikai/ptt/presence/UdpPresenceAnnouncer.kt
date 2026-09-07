package com.saikai.ptt.presence

import com.saikai.ptt.core.common.LifecycleStep
import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.domain.LocalPresence
import com.saikai.ptt.core.domain.PeerEndpoint
import com.saikai.ptt.core.domain.PeerObservation
import com.saikai.ptt.core.domain.PeerRegistry
import com.saikai.ptt.core.domain.PresenceKind
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.PacketType
import com.saikai.ptt.core.protocol.PeerState
import com.saikai.ptt.core.protocol.PresenceAnnouncement
import com.saikai.ptt.core.protocol.PresencePayload
import com.saikai.ptt.core.protocol.WireFormat
import com.saikai.ptt.discovery.BroadcastAddresses
import com.saikai.ptt.network.InboundPacket
import com.saikai.ptt.network.InboundPacketListener
import com.saikai.ptt.network.InboundPacketRouter
import com.saikai.ptt.network.TransportChannel
import com.saikai.ptt.network.UdpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * Keeps this device visible and works out who else still is.
 *
 * Three loops, one listener, and one payload shared with discovery
 * (`docs/03_Protocol.md` sections 12 and 13):
 *
 * - **Broadcast, every five seconds.** Broadcast rather than unicast per peer,
 *   because N devices unicasting produce N x (N-1) packets a period against N
 *   for a broadcast. On a shift with eight radios that is 56 packets every five
 *   seconds instead of 8, forever, for nothing.
 * - **Sweep, every two seconds.** A timeout is the one transition with no packet
 *   behind it, so something has to ask.
 * - **Announce again the moment the busy flag flips.** `peerState` is the only
 *   way a third device can learn that someone is in a call, and waiting for the
 *   next period would leave the peer list wrong for up to five seconds at
 *   exactly the moment a user is deciding whether to press talk.
 *
 * A peer is not declared offline for one lost packet: WiFi drops broadcasts
 * routinely, and a list that flickers is worse than one that lags. Three missed
 * periods plus a second of tolerance is the threshold, and any valid packet at
 * all -- voice, a pong -- refreshes liveness, because a device that is sending
 * audio is self-evidently there.
 */
class UdpPresenceAnnouncer(
    private val config: SaikaiConfig,
    private val logger: Logger,
    private val transport: UdpTransport,
    private val peers: PeerRegistry,
    private val router: InboundPacketRouter,
    private val presence: suspend () -> LocalPresence?,
    private val busy: Flow<Boolean>,
    private val broadcastAddresses: () -> List<InetAddress> = BroadcastAddresses::current,
    private val clock: () -> Long = System::currentTimeMillis,
) : InboundPacketListener, LifecycleStep {

    override val name: String = "presence"

    // Owned by the heartbeat coroutine alone, which is the only thing that
    // writes into it. Nothing is allocated per beat; ADR-002 forbids rebuilding
    // network objects per send and the transport reuses its DatagramPacket.
    private val buffer = ByteArray(WireFormat.MAX_DATAGRAM_BYTES)

    private var scope: CoroutineScope? = null

    /** The busy flag as last put on the wire; null until anything was sent. */
    @Volatile
    private var announcedBusy: Boolean? = null

    override suspend fun start() {
        router.register(this)
        val running = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = running

        running.launch {
            while (isActive) {
                // Delay first: discovery has just announced this device three
                // times over 900 ms, and a fourth packet in the same breath
                // tells nobody anything new.
                delay(config.presence.heartbeatInterval.inWholeMilliseconds)
                beat("periodic")
            }
        }

        running.launch {
            while (isActive) {
                delay(config.presence.evaluationInterval.inWholeMilliseconds)
                peers.evaluate()
            }
        }

        running.launch {
            // Compared against what was actually announced, not against the
            // previous emission. Dropping the first value would be the obvious
            // way to write this and would have a hole in it: if the busy flag
            // flipped between start() and this collector subscribing, the
            // conflated first emission would already be the new value and would
            // be dropped as if it were the old one. Tracking what went on the
            // wire is self-healing -- a beat that was missed for any reason is
            // corrected by the next emission.
            //
            // It also means the first collection sends one heartbeat straight
            // away, which is what makes a peer ONLINE within a second of the
            // service starting instead of at the first period five seconds later.
            busy.collect { value ->
                if (announcedBusy != value) beat("busy-changed")
            }
        }
    }

    override suspend fun stop() {
        router.unregister(this)
        scope?.cancel()
        scope = null
        announcedBusy = null
    }

    override fun onPacket(inbound: InboundPacket) {
        val type = inbound.packet.type ?: return
        val sender = inbound.packet.header.senderDeviceId

        when (type) {
            PacketType.HEARTBEAT -> record(inbound)

            // Discovery owns these two: it has to answer a DISCOVERY, and one
            // component recording a peer is one place for that rule to live.
            PacketType.DISCOVERY, PacketType.DISCOVERY_RESPONSE -> Unit

            // Section 13: any valid packet proves the peer is there. Section
            // 12.3: only presence packets decide what it is, which is exactly
            // what onActivity does and does not do.
            else -> peers.onActivity(sender)
        }
    }

    private fun record(inbound: InboundPacket) {
        val payload = inbound.packet.payload as? PresencePayload ?: return
        if (payload.voicePort !in 1..0xFFFF) {
            logger.throttled(LogLevel.DEBUG, LogCategory.PRESENCE, "bad-voice-port") {
                "ignoring heartbeat from ${inbound.sourceAddress}: voice port ${payload.voicePort}"
            }
            return
        }

        peers.onPresence(
            PeerObservation(
                deviceId = inbound.packet.header.senderDeviceId,
                userName = payload.userName,
                endpoint = PeerEndpoint(inbound.sourceAddress, payload.voicePort),
                protocolVersion = inbound.packet.header.protocolVersion,
                remoteBusy = payload.peerState == PeerState.BUSY,
                kind = PresenceKind.HEARTBEAT,
            )
        ).errorOrNull()?.let { error ->
            logger.throttled(LogLevel.DEBUG, LogCategory.PRESENCE, "peer-refused") {
                "ignoring heartbeat from ${inbound.sourceAddress}: $error"
            }
        }
    }

    private suspend fun beat(reason: String) {
        val snapshot = presence()?.takeIf(PresenceAnnouncement::canAnnounce) ?: return
        announcedBusy = snapshot.busy
        val length = PresenceAnnouncement.encode(
            type = PacketType.HEARTBEAT,
            presence = snapshot,
            timestampMillis = clock(),
            into = buffer,
        )
        for (target in broadcastAddresses()) {
            transport.send(
                buffer,
                length,
                target,
                config.network.controlPort,
                TransportChannel.CONTROL,
            )
        }
        logger.d(LogCategory.PRESENCE) {
            "heartbeat ($reason) busy=${snapshot.busy}"
        }
    }
}
