package com.saikai.ptt.di

import android.app.Service
import com.saikai.ptt.core.domain.DeviceId
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
        TransportStep(transport, app.logger, onBound),
    )

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
