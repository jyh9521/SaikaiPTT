package com.saikai.ptt.core.session

import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.domain.PcmConversion
import com.saikai.ptt.core.domain.PeerEndpoint
import com.saikai.ptt.core.domain.VoiceCodec
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.EmptyPayload
import com.saikai.ptt.core.protocol.Packet
import com.saikai.ptt.core.protocol.PacketPayload
import com.saikai.ptt.core.protocol.PacketType
import com.saikai.ptt.core.protocol.SessionId
import com.saikai.ptt.core.protocol.SessionTerminatePayload
import com.saikai.ptt.core.protocol.TerminationReason
import com.saikai.ptt.core.protocol.VoicePacketizer
import com.saikai.ptt.core.protocol.WireFormat

/**
 * The send side of a PTT session: control packets out, and captured audio
 * turned into VOICE_DATA.
 *
 * Implements [SessionSignals], so the state machine drives the control packets
 * through the interface it already had, and adds the two things the machine
 * must not know about -- a codec and a buffer.
 *
 * ### Press and speak
 *
 * `docs/01_PRD.md` section 10.1 starts capture on the button press and lets the
 * handshake take up to half a second. Frames captured in that window are
 * encoded immediately and held in [VoiceFrameBuffer]; the moment the peer
 * accepts they go out in order, ahead of the live ones. That is the whole
 * mechanism behind "press and speak" instead of "press, wait, speak", and it is
 * why the pre-roll flush and the switch to live sending happen inside one
 * critical section: a frame that slipped between them would be transmitted
 * ahead of older buffered audio, and the receiver would play the words out of
 * order.
 *
 * ### A fresh codec for every transmission
 *
 * [VoiceCodec] carries state between frames -- that is what makes 20 kbps
 * intelligible -- and the peer creates a new decoder for every session it
 * accepts. An encoder reused from a previous transmission is predicting against
 * history its decoder has never seen, and the frames that decode wrong are the
 * first ones. Those are exactly the frames the pre-roll buffer exists to
 * protect, so the codec is created per session and released with it.
 *
 * ### Threading
 *
 * Two callers: the capture thread delivers frames, and the session machine's
 * coroutine sends control packets and reports state changes. One lock covers
 * both, and nothing suspends while holding it -- the sink is a socket write, not
 * a suspending call. The lock is uncontended fifty times a second and contended
 * about twice a session.
 */
class VoiceTransmitter(
    private val config: SaikaiConfig,
    private val logger: Logger,
    private val selfDeviceId: DeviceId,
    private val sink: DatagramSink,
    private val codecs: () -> VoiceCodec?,
    private val recording: VoiceRecording = NoVoiceRecording,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : SessionSignals {

    private val lock = Any()

    /** The outgoing session, or null when this device is not speaking. */
    private var outgoing: Outgoing? = null

    /** Shared by the packets that are not part of an outgoing stream. Guarded by [lock]. */
    private val controlBuffer: ByteArray = WireFormat.newDatagramBuffer()

    /** Frames captured with no session to put them in. Diagnostics only. */
    var orphanFrames: Long = 0L
        private set

    // --- SessionSignals -----------------------------------------------------------

    /**
     * Opens the stream and sends VOICE_START.
     *
     * Called again for each retransmission (ADR-003 section 6 allows two), and
     * the second call must produce the same datagram rather than a second
     * request, so the session is set up once and the packet re-encoded from it.
     */
    override suspend fun voiceStart(
        sessionId: SessionId,
        target: DeviceId,
        endpoint: PeerEndpoint,
        localName: String,
    ): Boolean {
        val session = synchronized(lock) {
            val current = outgoing
            when {
                current != null && current.sessionId == sessionId -> current
                else -> {
                    if (current != null) close(current, keep = false)
                    open(sessionId, target, endpoint) ?: return false
                }
            }
        }
        val length = session.packetizer.writeVoiceStart(localName, nowMillis())
        return sink.sendControl(session.packetizer.controlDatagram, length, endpoint.address)
    }

    override suspend fun voiceAccept(
        sessionId: SessionId,
        target: DeviceId,
        endpoint: PeerEndpoint,
    ): Boolean {
        // A packetizer of its own rather than the shared buffer: this is the
        // receiving side of a session and it runs on the control receive thread,
        // which must not wait behind a capture thread mid-frame. One allocation
        // per accepted transmission is not a rate worth optimising.
        val packetizer = VoicePacketizer(selfDeviceId, target, sessionId, config.audio)
        val length = packetizer.writeVoiceAccept(nowMillis())
        return sink.sendControl(packetizer.controlDatagram, length, endpoint.address)
    }

    /** Carries no session id: the request was refused, so none was created. */
    override suspend fun busy(target: DeviceId, endpoint: PeerEndpoint): Boolean =
        sendControlPacket(PacketType.BUSY, target, SessionId.ZERO, EmptyPayload, endpoint)

    /**
     * Closes the stream and sends VOICE_END, carrying the counts the packetizer
     * accumulated.
     *
     * The numbers are not parameters. They describe the frames this object
     * actually put on the wire, and nothing above it can know them: the state
     * machine counts no frames, and a frame the codec refused or the socket
     * dropped never became a sequence number.
     */
    override suspend fun voiceEnd(
        sessionId: SessionId,
        target: DeviceId,
        endpoint: PeerEndpoint,
    ): Boolean {
        val session = synchronized(lock) {
            val current = outgoing
            if (current == null || current.sessionId != sessionId) {
                logger.w(LogCategory.SESSION) { "VOICE_END for a stream that is not open" }
                return false
            }
            current
        }

        val length = session.packetizer.writeVoiceEnd(nowMillis())
        val sent = sink.sendControl(session.packetizer.controlDatagram, length, endpoint.address)

        logger.i(LogCategory.SESSION) {
            "transmitted ${session.packetizer.framesSent} frames" +
                if (session.preRoll.overflowed > 0) {
                    ", ${session.preRoll.overflowed} lost to a full pre-roll buffer"
                } else {
                    ""
                }
        }
        synchronized(lock) { if (outgoing === session) close(session, keep = true) }
        return sent
    }

    override suspend fun sessionTerminate(
        sessionId: SessionId,
        target: DeviceId,
        endpoint: PeerEndpoint,
        reason: TerminationReason,
    ): Boolean = sendControlPacket(
        type = PacketType.SESSION_TERMINATE,
        target = target,
        sessionId = sessionId,
        payload = SessionTerminatePayload(reason),
        endpoint = endpoint,
    )

    // --- The audio path -----------------------------------------------------------

    /**
     * One frame of captured PCM, on the capture thread.
     *
     * Encoded here whether or not the peer has accepted yet, because the codec
     * must see the frames in the order they were captured and because doing it
     * later would put the whole backlog's worth of encoding at the one instant
     * the pipeline is busiest.
     *
     * A frame that arrives with no session open is counted and dropped. That
     * window is real but microscopic: the state machine opens the microphone,
     * and only then generates a session id and calls [voiceStart], so the gap is
     * the few microseconds between two statements while the first frame is still
     * 20 ms from being full.
     */
    fun onPcmFrame(pcm: ByteArray, offset: Int, length: Int, capturedAtMillis: Long) {
        synchronized(lock) {
            val session = outgoing
            if (session == null) {
                orphanFrames++
                return
            }
            if (length != config.audio.frameSizeBytes) {
                logger.throttled(LogLevel.WARN, LogCategory.AUDIO, "short-frame") {
                    "capture produced $length bytes, expected ${config.audio.frameSizeBytes}"
                }
                return
            }

            // Before the codec: the recording is of what was said, not of what
            // the network accepted.
            recording.frame(pcm, offset, length, capturedAtMillis)

            PcmConversion.bytesToShorts(pcm, offset, length, session.samples, 0)
            val encoded = session.codec.encode(session.samples, session.encoded)
            if (encoded <= 0) {
                logger.throttled(LogLevel.WARN, LogCategory.AUDIO, "encode-failed") {
                    "the encoder refused a frame ($encoded)"
                }
                return
            }

            if (session.live) {
                transmit(session, encoded, capturedAtMillis)
            } else if (!session.preRoll.add(session.encoded, 0, encoded)) {
                logger.throttled(LogLevel.WARN, LogCategory.AUDIO, "preroll-refused") {
                    "a ${encoded}B frame does not fit the wire; dropped"
                }
            }
        }
    }

    /**
     * The session machine changed state.
     *
     * One entry point rather than an accepted callback and a cancelled callback,
     * because there is one thing being tracked -- whether this device is
     * currently speaking into an accepted session -- and two callbacks would be
     * two chances for a path through the machine to forget one of them. Every
     * way a transmission can end (BUSY, no response, the button coming up, a
     * force interrupt, WiFi going away) leaves the state machine somewhere other
     * than Transmitting, and all of them arrive here.
     */
    fun onSessionState(state: SessionState) {
        synchronized(lock) {
            val session = outgoing ?: return
            when {
                state is SessionState.Transmitting && state.sessionId == session.sessionId ->
                    goLive(session)

                state is SessionState.Requesting && state.sessionId == session.sessionId -> Unit

                // Anything else means this stream is over. voiceEnd has already
                // closed it on the normal path, so reaching here with it still
                // open means the transmission did not finish: nothing to keep.
                else -> close(session, keep = false)
            }
        }
    }

    /** Caller holds the lock. Sends the backlog, then switches to live. */
    private fun goLive(session: Outgoing) {
        if (session.live) return
        val buffered = session.preRoll.size
        session.preRoll.drainTo { frame, length ->
            transmit(session, length, nowMillis(), frame)
        }
        session.live = true
        if (buffered > 0) {
            logger.i(LogCategory.SESSION) { "accepted; flushed $buffered buffered frames" }
        }
    }

    /** Caller holds the lock. */
    private fun transmit(
        session: Outgoing,
        length: Int,
        timestampMillis: Long,
        frame: ByteArray = session.encoded,
    ) {
        val datagram = session.packetizer.writeVoiceData(frame, 0, length, timestampMillis)
        if (datagram == VoicePacketizer.REJECTED) {
            logger.throttled(LogLevel.WARN, LogCategory.SESSION, "packetize-refused") {
                "a ${length}B frame was refused by the packetizer"
            }
            return
        }
        sink.sendVoice(
            session.packetizer.voiceDatagram,
            datagram,
            session.endpoint.address,
            session.endpoint.voicePort,
        )
    }

    /** Caller holds the lock. */
    private fun open(
        sessionId: SessionId,
        target: DeviceId,
        endpoint: PeerEndpoint,
    ): Outgoing? {
        val codec = codecs()
        if (codec == null) {
            logger.e(LogCategory.SESSION) { "no encoder; cannot transmit" }
            return null
        }
        val audio = config.audio
        val session = Outgoing(
            sessionId = sessionId,
            endpoint = endpoint,
            packetizer = VoicePacketizer(selfDeviceId, target, sessionId, audio),
            codec = codec,
            preRoll = VoiceFrameBuffer(
                capacityFrames = preRollFrames(),
                maxFrameBytes = minOf(codec.maxEncodedBytes, WireFormat.MAX_VOICE_PAYLOAD_BYTES),
            ),
            samples = ShortArray(audio.frameSizeSamples),
            encoded = ByteArray(codec.maxEncodedBytes),
        )
        outgoing = session
        recording.begin(sessionId)
        return session
    }

    /** Caller holds the lock. */
    private fun close(session: Outgoing, keep: Boolean) {
        if (keep) recording.finish(session.sessionId) else recording.discard(session.sessionId)
        session.codec.release()
        session.preRoll.clear()
        if (outgoing === session) outgoing = null
    }

    private fun preRollFrames(): Int {
        val frame = config.audio.frameDuration.inWholeMilliseconds
        val window = config.session.preRollBuffer.inWholeMilliseconds
        return ((window + frame - 1) / frame).toInt().coerceAtLeast(1)
    }

    /**
     * Encodes and sends one packet that is not part of an outgoing stream.
     *
     * The send happens inside the lock, not after it. [controlBuffer] is shared,
     * and releasing the lock between filling it and handing it to the socket is
     * how two of these overwrite each other -- which on this path means a device
     * answering BUSY with the bytes of somebody else's SESSION_TERMINATE.
     */
    private fun sendControlPacket(
        type: PacketType,
        target: DeviceId,
        sessionId: SessionId,
        payload: PacketPayload,
        endpoint: PeerEndpoint,
    ): Boolean = synchronized(lock) {
        val length = Packet.of(
            type = type,
            senderDeviceId = selfDeviceId,
            payload = payload,
            timestampMillis = nowMillis(),
            targetDeviceId = target,
            sessionId = sessionId,
        ).encodeTo(controlBuffer)
        sink.sendControl(controlBuffer, length, endpoint.address)
    }

    private class Outgoing(
        val sessionId: SessionId,
        val endpoint: PeerEndpoint,
        val packetizer: VoicePacketizer,
        val codec: VoiceCodec,
        val preRoll: VoiceFrameBuffer,
        val samples: ShortArray,
        val encoded: ByteArray,
    ) {
        /** False while the peer has not accepted and frames are being held back. */
        var live: Boolean = false
    }
}
