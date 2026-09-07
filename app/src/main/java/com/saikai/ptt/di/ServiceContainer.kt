package com.saikai.ptt.di

import android.app.Service
import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.domain.LocalPresence
import com.saikai.ptt.core.domain.PeerRegistry
import com.saikai.ptt.discovery.UdpPeerDiscovery
import com.saikai.ptt.core.protocol.PacketRateLimiter
import com.saikai.ptt.core.protocol.PacketValidator
import com.saikai.ptt.network.BoundPorts
import com.saikai.ptt.network.InboundPacketRouter
import com.saikai.ptt.network.UdpTransport
import com.saikai.ptt.service.ForegroundStep
import com.saikai.ptt.service.LifecycleStep
import com.saikai.ptt.service.MulticastLockStep
import com.saikai.ptt.service.ServiceNotifications
import com.saikai.ptt.service.TransportStep
import kotlinx.coroutines.flow.first

/**
 * Service-scope dependencies: the objects that own a real resource.
 *
 * Created when the communication service starts and thrown away when it stops.
 * That is the whole point of a second scope: sockets, receive threads, power
 * locks and later the audio devices must stop existing when the service stops.
 * Hanging them off [AppContainer] would make "stop" mean "keep everything open
 * but stop using it", which is how an app ends up in the battery dialog.
 *
 * The container also owns the step list. It is the only object that knows every
 * component, so it is the only one that can state the start order -- and stating
 * it in one place is what makes the reverse-order shutdown of
 * `docs/02_Architecture.md` section 26 mechanical rather than remembered.
 *
 * @param localDeviceId resolved before construction. Every validation decision
 *   depends on it -- loopback filtering and target matching both -- so a
 *   container that had to wait for it would have a window where every packet was
 *   judged against the wrong identity.
 */
class ServiceContainer(
    service: Service,
    private val app: AppContainer,
    val localDeviceId: DeviceId,
    notifications: ServiceNotifications,
    onBound: (BoundPorts) -> Unit = {},
) {

    /** Where validated packets fan out. Components register as they start. */
    val router: InboundPacketRouter = InboundPacketRouter(app.logger)

    val validator: PacketValidator = PacketValidator(
        localDeviceId = localDeviceId,
        logger = app.logger,
        // No voice session can exist yet. Task19 supplies the session manager's
        // view of it; until then every VOICE_DATA is correctly a foreign frame.
        receivingSession = { null },
    )

    val rateLimiter: PacketRateLimiter = PacketRateLimiter(app.config.rateLimit, app.logger)

    val transport: UdpTransport = UdpTransport(
        config = app.config,
        logger = app.logger,
        validator = validator,
        rateLimiter = rateLimiter,
        listener = router,
    )

    /** The peer table. Service scope: it describes a network this device is on. */
    val peers: PeerRegistry = PeerRegistry(
        presence = app.config.presence,
        limits = app.config.rateLimit,
        logger = app.logger,
    )

    /**
     * Known only once the voice socket is bound, and announced to peers, so
     * nothing can be said about this device before the transport step runs.
     */
    @Volatile
    var boundPorts: BoundPorts? = null
        private set

    val discovery: UdpPeerDiscovery = UdpPeerDiscovery(
        config = app.config,
        logger = app.logger,
        transport = transport,
        peers = peers,
        router = router,
        presence = ::snapshotPresence,
        activeUserChanges = app.localUsers.activeUser,
    )

    /**
     * Start order. Shutdown is exactly this list, reversed.
     *
     * Later tasks insert their components here -- discovery and heartbeat after
     * the transport, audio and the session manager after those -- rather than
     * editing a start method and a stop method and getting one of them wrong.
     */
    val steps: List<LifecycleStep> = listOf(
        ForegroundStep(service, notifications),
        MulticastLockStep(service, app.logger),
        TransportStep(transport, app.logger) { ports ->
            boundPorts = ports
            onBound(ports)
        },
        discovery,
    )

    /**
     * What this device can currently say about itself, or null when it cannot
     * say anything yet.
     *
     * Null on a fresh install with no name chosen, and before the voice socket
     * is bound. Announcing either as a blank or a zero would put a row in every
     * peer list on the network that nobody can call.
     */
    private suspend fun snapshotPresence(): LocalPresence? {
        val voicePort = boundPorts?.voicePort ?: return null
        val user = app.localUsers.activeUser.first() ?: return null
        return LocalPresence(
            deviceId = localDeviceId,
            userName = user.displayName,
            voicePort = voicePort,
            // No session machine yet (Task19); this device is never busy.
            busy = false,
        )
    }

    /**
     * Releases what the container itself holds.
     *
     * The steps have already been rolled back by the time this runs; this is for
     * everything that was never a step, which today is the router's
     * registrations.
     */
    fun close() {
        router.clear()
    }
}
