package com.saikai.ptt.di

import android.app.Service
import com.saikai.ptt.audio.AndroidAudioFocus
import com.saikai.ptt.audio.AndroidAudioPlayer
import com.saikai.ptt.audio.AndroidAudioRecorder
import com.saikai.ptt.audio.AndroidVoiceAudio
import com.saikai.ptt.audio.OpusVoiceCodec
import com.saikai.ptt.core.common.LifecycleStep
import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.domain.LocalPresence
import com.saikai.ptt.core.domain.PeerRegistry
import com.saikai.ptt.core.domain.VoiceCodec
import com.saikai.ptt.core.protocol.PacketRateLimiter
import com.saikai.ptt.core.protocol.PacketValidator
import com.saikai.ptt.core.protocol.ReceivingSession
import com.saikai.ptt.core.protocol.TerminationReason
import com.saikai.ptt.core.session.SessionManager
import com.saikai.ptt.core.session.SessionState
import com.saikai.ptt.core.session.VoiceReceiver
import com.saikai.ptt.core.session.VoiceTransmitter
import com.saikai.ptt.discovery.UdpPeerDiscovery
import com.saikai.ptt.network.BoundPorts
import com.saikai.ptt.network.InboundPacketRouter
import com.saikai.ptt.network.NetworkMonitor
import com.saikai.ptt.network.UdpDatagramSink
import com.saikai.ptt.network.UdpTransport
import com.saikai.ptt.presence.UdpPresenceAnnouncer
import com.saikai.ptt.service.ForegroundStep
import com.saikai.ptt.service.MulticastLockStep
import com.saikai.ptt.service.PttController
import com.saikai.ptt.service.ServiceNotifications
import com.saikai.ptt.service.TransportStep
import com.saikai.ptt.service.VoiceSessionPowerLocks
import com.saikai.ptt.session.SessionPacketListener
import com.saikai.ptt.session.VoiceSessionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Service-scope dependencies: the objects that own a real resource.
 *
 * Created when the communication service starts and thrown away when it stops.
 * That is the whole point of a second scope: sockets, receive threads, power
 * locks and the audio devices must stop existing when the service stops.
 * Hanging them off [AppContainer] would make "stop" mean "keep everything open
 * but stop using it", which is how an app ends up in the battery dialog.
 *
 * The container also owns the step list. It is the only object that knows every
 * component, so it is the only one that can state the start order -- and stating
 * it in one place is what makes the reverse-order shutdown of
 * `docs/02_Architecture.md` section 26 mechanical rather than remembered.
 *
 * **The graph has one cycle and it is broken with a lambda.** The transport
 * needs a validator, the validator needs to know which session is being
 * received, the session machine needs the transmitter, and the transmitter
 * needs the transport. Nothing can be constructed first, so the validator is
 * given a function instead of a value and calls it when a packet arrives --
 * which is always after construction, because the sockets do not open until the
 * transport step runs.
 *
 * @param localDeviceId resolved before construction. Every validation decision
 *   depends on it -- loopback filtering and target matching both -- so a
 *   container that had to wait for it would have a window where every packet was
 *   judged against the wrong identity.
 * @param scope the service's own scope. Everything launched from here dies with
 *   the service, which is what makes the second scope worth having.
 */
class ServiceContainer(
    service: Service,
    private val app: AppContainer,
    val localDeviceId: DeviceId,
    notifications: ServiceNotifications,
    private val scope: CoroutineScope,
    onBound: (BoundPorts) -> Unit = {},
) {

    /** Where validated packets fan out. Components register as they start. */
    val router: InboundPacketRouter = InboundPacketRouter(app.logger)

    val validator: PacketValidator = PacketValidator(
        localDeviceId = localDeviceId,
        logger = app.logger,
        receivingSession = ::currentReceivingSession,
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

    // --- Voice -----------------------------------------------------------------------

    /**
     * The send pipeline, which is also the state machine's [SessionSignals].
     *
     * One object because they are one thing: VOICE_END has to carry the number
     * of frames that actually went out, and only whatever sent them knows it.
     */
    val transmitter: VoiceTransmitter = VoiceTransmitter(
        config = app.config,
        logger = app.logger,
        selfDeviceId = localDeviceId,
        sink = UdpDatagramSink(app.config, app.logger, transport),
        codecs = ::newCodec,
    )

    private val player = AndroidAudioPlayer(service, app.config, app.logger)

    /**
     * The receive pipeline: jitter buffer, decoder, speaker.
     *
     * Holds the player rather than being held by it, because everything between
     * the network and the device -- ordering, gap filling, decoding -- happens
     * before a byte reaches `AudioTrack`.
     */
    val receiver: VoiceReceiver = VoiceReceiver(
        config = app.config,
        logger = app.logger,
        player = player,
        codecs = ::newCodec,
    )

    /**
     * The microphone, the speaker and the audio focus around both.
     *
     * Captured frames go straight to the transmitter: it is the sink, so there
     * is no queue between the capture thread and the encoder and nothing to
     * tune. The transmitter decides whether a frame is buffered or transmitted,
     * because that is a property of the session, not of the microphone.
     */
    val audio: AndroidVoiceAudio = AndroidVoiceAudio(
        recorder = AndroidAudioRecorder(service, app.config, app.logger),
        player = player,
        focus = AndroidAudioFocus(service, app.logger),
        logger = app.logger,
        frames = transmitter::onPcmFrame,
        onFocusLost = { endSessionForAudioFocus() },
        receiver = receiver,
    )

    /** The state machine. One per service, and the only arbiter of who is talking. */
    val sessions: SessionManager = SessionManager(
        config = app.config,
        logger = app.logger,
        signals = transmitter,
        audio = audio,
        localName = { app.localUsers.activeUser.first()?.displayName },
        allowInterrupt = { app.settingsRepository.current().allowInterrupt },
        scope = scope,
    )

    val powerLocks: VoiceSessionPowerLocks =
        VoiceSessionPowerLocks(service, app.config, app.logger)

    // --- Discovery and presence --------------------------------------------------------

    val discovery: UdpPeerDiscovery = UdpPeerDiscovery(
        config = app.config,
        logger = app.logger,
        transport = transport,
        peers = peers,
        router = router,
        presence = ::snapshotPresence,
        activeUserChanges = app.localUsers.activeUser,
    )

    val presence: UdpPresenceAnnouncer = UdpPresenceAnnouncer(
        config = app.config,
        logger = app.logger,
        transport = transport,
        peers = peers,
        router = router,
        presence = ::snapshotPresence,
        busy = sessions.busy,
    )

    /** Watches for WiFi appearing, disappearing and being renumbered. */
    val networkMonitor: NetworkMonitor = NetworkMonitor(service, app.logger)

    /** The talk button, for whatever UI is in front of it. */
    val ptt: PttController = PttController(
        service = service,
        notifications = notifications,
        sessions = sessions,
        visibility = { app.visibility.isForeground },
        logger = app.logger,
    )

    private val coordinator = VoiceSessionCoordinator(
        sessions = sessions,
        router = router,
        listener = SessionPacketListener(sessions, receiver, peers, app.logger, scope),
        transmitter = transmitter,
        receiver = receiver,
        powerLocks = powerLocks,
        onTransmittingEnded = { notifications.demoteFromMicrophone(service) },
        publishSession = app.serviceStatus::publishSession,
        publishReception = app.serviceStatus::publishReception,
        logger = app.logger,
        scope = scope,
    )

    private val transportStep = TransportStep(transport, app.logger) { ports ->
        boundPorts = ports
        onBound(ports)
    }

    /**
     * The first step that cannot work without a network.
     *
     * Everything from here on is released when WiFi drops and started again when
     * it returns; the foreground notification and the multicast lock in front of
     * it stay (`docs/03_Protocol.md` section 41).
     */
    val firstNetworkStep: String = transportStep.name

    /**
     * Start order. Shutdown is exactly this list, reversed.
     *
     * The session coordinator is last, and therefore released first. That is the
     * point: a session in progress has to be ended, and its last packet actually
     * sent, while the sockets are still open.
     */
    val steps: List<LifecycleStep> = listOf(
        ForegroundStep(service, notifications),
        MulticastLockStep(service, app.logger),
        transportStep,
        discovery,
        presence,
        coordinator,
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
            busy = sessions.busy.value,
        )
    }

    /**
     * The session VOICE_DATA is allowed to belong to, for validation step 11.
     *
     * Called from the voice receive thread on every frame, so it reads the
     * machine's current state rather than asking it anything.
     */
    private fun currentReceivingSession(): ReceivingSession? =
        (sessions.state.value as? SessionState.Receiving)
            ?.let { ReceivingSession(it.sessionId, it.peer) }

    /**
     * A phone call, or anything else, took the audio away.
     *
     * The session cannot continue, and ending it is not the same as letting it
     * stop: VOICE_END goes out, the peer stops waiting, and the recording is
     * finalised instead of trailing off.
     *
     * v1 has no termination reason for "the platform took the audio" -- ADR-003
     * section 4 defines four. It never reaches the wire on the sending side,
     * which is what this task delivers: a sender sends VOICE_END, and the reason
     * only labels the local history entry. A receiver does put it on the wire,
     * so whether a fifth code is needed is Task26's to settle.
     *
     * A method rather than a lambda in the constructor call, because the session
     * machine is built after the audio devices and this is what makes the order
     * a detail rather than a constraint.
     */
    private fun endSessionForAudioFocus() {
        scope.launch { sessions.terminate(TerminationReason.SERVICE_SHUTDOWN) }
    }

    /**
     * A fresh encoder and decoder for one transmission, in either direction.
     *
     * Per session, not per service. Codec state is carried between frames, and
     * both sides start clean for every transmission: an encoder reused from the
     * last one predicts against history the far end's new decoder has never
     * seen, and a decoder reused would be doing the same in reverse. Either way
     * the frames that come out wrong are the first ones.
     */
    private fun newCodec(): VoiceCodec? =
        OpusVoiceCodec.create(app.config, app.logger).valueOrNull()

    /**
     * Releases what the container itself holds.
     *
     * The steps have already been rolled back by the time this runs; this is for
     * everything that was never a step.
     */
    fun close() {
        router.clear()
        powerLocks.release()
    }
}
