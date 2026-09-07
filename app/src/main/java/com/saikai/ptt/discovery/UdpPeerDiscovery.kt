package com.saikai.ptt.discovery

import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.domain.AnnounceReason
import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.domain.LocalPresence
import com.saikai.ptt.core.domain.PeerDiscovery
import com.saikai.ptt.core.domain.PeerEndpoint
import com.saikai.ptt.core.domain.PeerObservation
import com.saikai.ptt.core.domain.PeerRegistry
import com.saikai.ptt.core.domain.PresenceKind
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogFormat
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.Packet
import com.saikai.ptt.core.protocol.PacketType
import com.saikai.ptt.core.protocol.PeerState
import com.saikai.ptt.core.protocol.PresencePayload
import com.saikai.ptt.core.protocol.WireFormat
import com.saikai.ptt.network.InboundPacket
import com.saikai.ptt.network.InboundPacketListener
import com.saikai.ptt.network.InboundPacketRouter
import com.saikai.ptt.network.TransportChannel
import com.saikai.ptt.network.UdpTransport
import com.saikai.ptt.service.LifecycleStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.net.InetAddress
import kotlin.time.Duration

/**
 * Pure UDP broadcast discovery (`docs/ADR/ADR-001`).
 *
 * Two halves that share one payload layout:
 *
 * - **Announcing.** On becoming ready, on network recovery and on a rename, this
 *   device broadcasts DISCOVERY three times over 900 ms. Three, because one lost
 *   frame would otherwise leave two devices invisible to each other until the
 *   next heartbeat -- up to five seconds of a product whose entire promise is
 *   that it just works when you open it.
 * - **Answering.** A DISCOVERY from anyone is answered immediately with a
 *   unicast DISCOVERY_RESPONSE, so a device that has just joined sees the whole
 *   network without waiting for a heartbeat cycle.
 *
 * Both directions feed [PeerRegistry], which is what makes an address change
 * update a peer instead of duplicating it.
 *
 * The reply path runs on the control receive thread and cannot suspend, so the
 * announcement snapshot is cached in a volatile field and refreshed whenever the
 * name or the port changes. Announcing and answering therefore use separate
 * buffers -- they run on different threads and neither may wait for the other.
 *
 * @param presence the current snapshot, or null when this device has nothing to
 *   announce yet: a fresh install with no name chosen, or a socket not bound.
 *   Silence is correct there. A device that announced an empty name would appear
 *   in every peer list as a blank row.
 */
class UdpPeerDiscovery(
    private val config: SaikaiConfig,
    private val logger: Logger,
    private val transport: UdpTransport,
    private val peers: PeerRegistry,
    private val router: InboundPacketRouter,
    private val presence: suspend () -> LocalPresence?,
    private val activeUserChanges: Flow<*>,
    private val broadcastAddresses: () -> List<InetAddress> = BroadcastAddresses::current,
    private val clock: () -> Long = System::currentTimeMillis,
) : PeerDiscovery, InboundPacketListener, LifecycleStep {

    override val name: String = "discovery"

    // One buffer per direction. Announcing runs on the service scope, answering
    // runs on the control receive thread, and a shared buffer would need a lock
    // on the path that must never wait.
    private val announceBuffer = ByteArray(WireFormat.MAX_DATAGRAM_BYTES)
    private val replyBuffer = ByteArray(WireFormat.MAX_DATAGRAM_BYTES)

    @Volatile
    private var cached: LocalPresence? = null

    private var scope: CoroutineScope? = null
    private var announceJob: Job? = null

    override suspend fun start() {
        router.register(this)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        announce(AnnounceReason.SERVICE_READY)
        // The first emission is the state the announcement above already
        // carried; only later ones are a change worth telling the network about.
        scope?.launch {
            activeUserChanges.distinctUntilChanged().drop(1).collect {
                announce(AnnounceReason.USER_CHANGED)
            }
        }
    }

    override suspend fun stop() {
        router.unregister(this)
        announceJob?.cancelAndJoin()
        announceJob = null
        scope?.cancel()
        scope = null
        cached = null
    }

    override suspend fun announce(reason: AnnounceReason) {
        val snapshot = presence()?.takeIf { announceable(it) }
        cached = snapshot
        if (snapshot == null) {
            logger.d(LogCategory.DISCOVERY) {
                "not announcing ($reason): nothing to say about this device yet"
            }
            return
        }

        val running = scope ?: return
        // Replace rather than overlap: two quick renames should put the second
        // name on the network, not race the first one's remaining sends.
        announceJob?.cancel()
        announceJob = running.launch {
            config.discovery.announceDelays.forEachIndexed { index, delayBefore ->
                val previous = if (index == 0) Duration.ZERO
                else config.discovery.announceDelays[index - 1]
                delay((delayBefore - previous).inWholeMilliseconds)
                broadcast(snapshot, reason, index)
            }
        }
    }

    override fun onPacket(inbound: InboundPacket) {
        val type = inbound.packet.type ?: return
        val kind = when (type) {
            PacketType.DISCOVERY -> PresenceKind.DISCOVERY
            PacketType.DISCOVERY_RESPONSE -> PresenceKind.DISCOVERY_RESPONSE
            else -> return
        }
        val payload = inbound.packet.payload as? PresencePayload ?: return

        // The protocol allows any u16 here, including zero, which is not a port
        // anyone can be reached on. Refusing it keeps an unreachable peer out of
        // the list rather than letting a later send fail mysteriously.
        if (payload.voicePort !in 1..0xFFFF) {
            logger.throttled(LogLevel.DEBUG, LogCategory.DISCOVERY, "bad-voice-port") {
                "ignoring $type from ${inbound.sourceAddress}: voice port ${payload.voicePort}"
            }
            return
        }

        val observation = PeerObservation(
            deviceId = inbound.packet.header.senderDeviceId,
            userName = payload.userName,
            // The address a datagram actually arrived from, never one a peer
            // claims: it is the only value that cannot be wrong or stale
            // (`docs/03_Protocol.md` section 11).
            endpoint = PeerEndpoint(inbound.sourceAddress, payload.voicePort),
            protocolVersion = inbound.packet.header.protocolVersion,
            remoteBusy = payload.peerState == PeerState.BUSY,
            kind = kind,
        )

        peers.onPresence(observation).errorOrNull()?.let { error ->
            logger.throttled(LogLevel.DEBUG, LogCategory.DISCOVERY, "peer-refused") {
                "ignoring $type from ${inbound.sourceAddress}: $error"
            }
            return
        }

        if (kind == PresenceKind.DISCOVERY) reply(inbound)
    }

    /**
     * Answers a DISCOVERY with a unicast DISCOVERY_RESPONSE.
     *
     * Sent back to the port the request came from rather than to the configured
     * control port. They are normally the same, but a peer whose control port
     * drifted (ADR-002) is still worth answering -- it can hear us even if it
     * cannot be found by broadcast.
     */
    private fun reply(inbound: InboundPacket) {
        val snapshot = cached ?: return
        val target = try {
            InetAddress.getByName(inbound.sourceAddress)
        } catch (_: java.net.UnknownHostException) {
            return
        }

        val length = encode(
            type = PacketType.DISCOVERY_RESPONSE,
            snapshot = snapshot,
            target = inbound.packet.header.senderDeviceId,
            into = replyBuffer,
        )
        transport.send(replyBuffer, length, target, inbound.sourcePort, TransportChannel.CONTROL)
    }

    private fun broadcast(snapshot: LocalPresence, reason: AnnounceReason, attempt: Int) {
        val length = encode(
            type = PacketType.DISCOVERY,
            snapshot = snapshot,
            target = DeviceId.ZERO,
            into = announceBuffer,
        )
        val targets = broadcastAddresses()
        for (target in targets) {
            transport.send(
                announceBuffer,
                length,
                target,
                config.network.controlPort,
                TransportChannel.CONTROL,
            )
        }
        logger.d(LogCategory.DISCOVERY) {
            "announced ${LogFormat.userName(snapshot.userName)} ($reason, ${attempt + 1}/" +
                "${config.discovery.announceDelays.size}) to $targets"
        }
    }

    /**
     * Whether this device has something it can legally put on the wire.
     *
     * Checked here rather than trusted from the caller because the encoder
     * cannot be forgiving: an empty or over-long name fails the payload's own
     * requirement, and that would take out the announcement coroutine rather
     * than skipping one broadcast. Silence is the right answer for a device that
     * has no name yet.
     */
    private fun announceable(snapshot: LocalPresence): Boolean {
        if (snapshot.deviceId.isZero) return false
        if (snapshot.voicePort !in 1..0xFFFF) return false
        val name = snapshot.userName
        if (name.isBlank()) return false
        return name.toByteArray(Charsets.UTF_8).size <= WireFormat.MAX_USER_NAME_BYTES
    }

    private fun encode(
        type: PacketType,
        snapshot: LocalPresence,
        target: DeviceId,
        into: ByteArray,
    ): Int = Packet.of(
        type = type,
        senderDeviceId = snapshot.deviceId,
        payload = PresencePayload(
            peerState = if (snapshot.busy) PeerState.BUSY else PeerState.IDLE,
            voicePort = snapshot.voicePort,
            userName = snapshot.userName,
        ),
        timestampMillis = clock(),
        targetDeviceId = target,
    ).encodeTo(into)
}
