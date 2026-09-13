package com.saikai.ptt.core.session

import com.saikai.ptt.core.config.LoggingConfig
import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.domain.PeerEndpoint
import com.saikai.ptt.core.domain.VoiceCodec
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.LogSink
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.EmptyPayload
import com.saikai.ptt.core.protocol.Packet
import com.saikai.ptt.core.protocol.PacketCodec
import com.saikai.ptt.core.protocol.PacketType
import com.saikai.ptt.core.protocol.SessionId
import com.saikai.ptt.core.protocol.SessionTerminatePayload
import com.saikai.ptt.core.protocol.TerminationReason
import com.saikai.ptt.core.protocol.VoiceDataPayload
import com.saikai.ptt.core.protocol.VoiceEndPayload
import com.saikai.ptt.core.protocol.VoiceStartPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * The send pipeline: what goes on the wire, in what order, and when.
 *
 * The ordering assertions are the point. A pipeline that sends every frame
 * eventually but flushes the pre-roll buffer in the wrong place produces audio
 * that is present, complete and unintelligible, and it does it only when the
 * handshake happens to be slow -- which is to say, only on the networks this
 * product is for.
 */
class VoiceTransmitterTest {

    private val config = SaikaiConfig()
    private val logger = Logger(
        LoggingConfig.debug(),
        object : LogSink {
            override fun write(
                level: LogLevel,
                category: LogCategory,
                message: String,
                throwable: Throwable?,
            ) = Unit
        },
    )

    private val self = DeviceId.parse("00112233-4455-6677-8899-aabbccddeeff")!!
    private val peer = DeviceId.parse("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0")!!
    private val endpoint = PeerEndpoint("192.168.1.20", 45_821)
    private val session = SessionId.parse("12345678-9abc-def0-1234-56789abcdef0")!!

    private val sink = CapturingSink()
    private val recording = CountingRecording()
    private var codec = MarkerCodec()
    private var codecsCreated = 0

    private val subject = VoiceTransmitter(
        config = config,
        logger = logger,
        selfDeviceId = self,
        sink = sink,
        codecs = { codecsCreated++; codec },
        recording = recording,
        nowMillis = { 1_000L },
    )

    /** 500 ms of pre-roll at 20 ms a frame. */
    private val preRollCapacity = 25

    // --- Helpers -------------------------------------------------------------------

    /**
     * Runs a suspending call and requires it to finish without suspending.
     *
     * Not a shortcut for `runBlocking`. Every [SessionSignals] method is invoked
     * from inside `SessionManager`'s mutex -- the lock that arbitrates session
     * ownership -- so an implementation that really suspended would hold that
     * lock across a network operation and every concurrent VOICE_START would
     * queue behind it. This turns that into a test failure instead of a latent
     * one.
     */
    private fun <T> completing(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        return checkNotNull(outcome) {
            "the call suspended, and it is invoked while the session mutex is held"
        }.getOrThrow()
    }

    private fun start(name: String = "Kenji") =
        completing { subject.voiceStart(session, peer, endpoint, name) }

    private fun end() = completing { subject.voiceEnd(session, peer, endpoint) }

    /** One 20 ms PCM frame whose every sample is [marker], so order is visible. */
    private fun capture(marker: Int) {
        val pcm = ByteArray(config.audio.frameSizeBytes)
        for (index in pcm.indices step 2) {
            pcm[index] = (marker and 0xFF).toByte()
            pcm[index + 1] = ((marker shr 8) and 0xFF).toByte()
        }
        subject.onPcmFrame(pcm, 0, pcm.size, 2_000L)
    }

    private fun accept() = subject.onSessionState(
        SessionState.Transmitting(session, peer, "Kenji", endpoint, 0L)
    )

    // --- Tests ---------------------------------------------------------------------

    @Test
    fun `nothing is transmitted before the peer accepts`() {
        start()
        repeat(3) { capture(it) }

        assertEquals(0, sink.voice.size)
        assertEquals(1, sink.control.size)
        assertEquals(PacketType.VOICE_START, sink.control[0].type)
    }

    @Test
    fun `the audio captured while waiting goes out first, in order`() {
        // The whole point of the task: press and speak, not press, wait, speak.
        start()
        repeat(5) { capture(it) }
        accept()
        repeat(3) { capture(it + 5) }
        end()

        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), sink.voiceMarkers())
        assertEquals((1..8).toList(), sink.voice.map { it.header.sequenceNumber })
    }

    @Test
    fun `voice data goes to the port the peer announced and control to its address`() {
        start()
        accept()
        capture(1)

        assertEquals(listOf("192.168.1.20:45821"), sink.voiceTargets)
        assertEquals(listOf("192.168.1.20"), sink.controlTargets)
    }

    @Test
    fun `an overlong wait loses the oldest audio, never the newest`() {
        start()
        repeat(preRollCapacity + 4) { capture(it) }
        accept()

        assertEquals(preRollCapacity, sink.voice.size)
        assertEquals((4 until preRollCapacity + 4).toList(), sink.voiceMarkers())
    }

    @Test
    fun `voice end reports the frames that actually went out`() {
        start()
        accept()
        repeat(6) { capture(it) }
        end()

        val last = sink.control.last()
        assertEquals(PacketType.VOICE_END, last.type)
        assertEquals(VoiceEndPayload(finalDataSequence = 6, frameCount = 6), last.payload)
        assertEquals(7, last.header.sequenceNumber)
    }

    @Test
    fun `a frame the encoder refuses costs one frame, not a sequence number`() {
        // A gap would send the receiver looking for the FEC copy of a frame that
        // was never encoded, let alone sent.
        start()
        accept()
        capture(1)
        codec.failNext = true
        capture(2)
        capture(3)
        end()

        assertEquals(listOf(1, 3), sink.voiceMarkers())
        assertEquals(listOf(1, 2), sink.voice.map { it.header.sequenceNumber })
        assertEquals(VoiceEndPayload(2, 2), sink.control.last().payload)
    }

    @Test
    fun `a retransmitted voice start is the same request, not a second one`() {
        start()
        capture(1)
        start()
        accept()
        end()

        assertEquals(1, codecsCreated)
        assertEquals(1, recording.begun)
        assertArrayEquals(sink.controlBytes[0], sink.controlBytes[1])
        // The frame captured before the retransmission is still there.
        assertEquals(listOf(1), sink.voiceMarkers())
    }

    @Test
    fun `voice start carries this build's audio parameters and the name as given`() {
        start("さいかい")

        assertEquals(
            VoiceStartPayload(
                codec = com.saikai.ptt.core.protocol.AudioCodec.OPUS,
                sampleRateHz = config.audio.sampleRateHz,
                frameMillis = 20,
                userName = "さいかい",
            ),
            sink.control[0].payload,
        )
        assertEquals(session, sink.control[0].header.sessionId)
        assertEquals(peer, sink.control[0].header.targetDeviceId)
    }

    @Test
    fun `frames arriving with no session are counted, not sent`() {
        capture(1)
        capture(2)

        assertEquals(2L, subject.orphanFrames)
        assertEquals(0, sink.voice.size)
    }

    @Test
    fun `frames are refused after the transmission ends`() {
        start()
        accept()
        capture(1)
        end()
        capture(2)

        assertEquals(listOf(1), sink.voiceMarkers())
        assertEquals(1L, subject.orphanFrames)
    }

    @Test
    fun `a completed transmission keeps its recording and frees the codec`() {
        start()
        accept()
        capture(1)
        end()

        assertEquals(1, recording.finished)
        assertEquals(0, recording.discarded)
        assertTrue(codec.released)
        assertEquals(1, recording.frames)
    }

    @Test
    fun `a refused or unanswered call leaves no recording behind`() {
        // PRD 10.4: BUSY and a timeout produce no history record at all, and
        // this is the half of that which has to happen in the pipeline.
        start()
        capture(1)
        subject.onSessionState(SessionState.Idle)

        assertEquals(0, recording.finished)
        assertEquals(1, recording.discarded)
        assertTrue(codec.released)
    }

    @Test
    fun `a session that is interrupted mid-transmission also stops the stream`() {
        start()
        accept()
        capture(1)
        subject.onSessionState(
            SessionState.Receiving(SessionId.random(), peer, "Other", endpoint, 0L)
        )
        capture(2)

        assertEquals(listOf(1), sink.voiceMarkers())
        assertEquals(1, recording.discarded)
    }

    @Test
    fun `a new transmission gets a new codec`() {
        // A decoder is created fresh for every session it accepts, so an encoder
        // carrying state from the last transmission is predicting against
        // history the far end has never seen -- and the frames that decode wrong
        // are the first ones, which is exactly what the pre-roll protects.
        start()
        accept()
        end()

        codec = MarkerCodec()
        val second = SessionId.random()
        completing { subject.voiceStart(second, peer, endpoint, "Kenji") }

        assertEquals(2, codecsCreated)
        assertEquals(2, recording.begun)
    }

    @Test
    fun `busy echoes the caller's own session and discloses nothing else`() {
        // The caller's id, so it can tell this refusal from a stale one. Never
        // the session this device is actually in, and never in the payload:
        // that would tell whoever is listening who is talking to whom
        // (ADR-003 section 4).
        val theirs = SessionId.random()
        completing { subject.busy(theirs, peer, endpoint) }

        val packet = sink.control.single()
        assertEquals(PacketType.BUSY, packet.type)
        assertEquals(theirs, packet.header.sessionId)
        assertEquals(EmptyPayload, packet.payload)
        assertEquals(0, packet.header.sequenceNumber)
    }

    @Test
    fun `refusing a caller does not disturb this device's own transmission`() {
        // Busy mode has to be free: the packet that refuses somebody else goes
        // out on its own buffer, and the stream in progress carries on
        // numbering from where it was.
        start()
        accept()
        capture(1)
        completing { subject.busy(SessionId.random(), peer, endpoint) }
        capture(2)
        end()

        assertEquals(listOf(1, 2), sink.voice.map { it.header.sequenceNumber })
        assertEquals(VoiceEndPayload(2, 2), sink.control.last().payload)
    }

    @Test
    fun `voice accept names the session and takes sequence zero`() {
        completing { subject.voiceAccept(session, peer, endpoint) }

        val packet = sink.control.single()
        assertEquals(PacketType.VOICE_ACCEPT, packet.type)
        assertEquals(session, packet.header.sessionId)
        assertEquals(0, packet.header.sequenceNumber)
    }

    @Test
    fun `session terminate says why`() {
        completing {
            subject.sessionTerminate(session, peer, endpoint, TerminationReason.INTERRUPTED_BY_PEER)
        }

        val packet = sink.control.single()
        assertEquals(PacketType.SESSION_TERMINATE, packet.type)
        assertEquals(
            SessionTerminatePayload(TerminationReason.INTERRUPTED_BY_PEER),
            packet.payload,
        )
    }

    @Test
    fun `accepting a session the transmitter does not own changes nothing`() {
        start()
        capture(1)
        subject.onSessionState(
            SessionState.Transmitting(SessionId.random(), peer, "Kenji", endpoint, 0L)
        )

        // Not live, and the stream is closed rather than left half-open.
        assertEquals(0, sink.voice.size)
        assertEquals(1, recording.discarded)
    }

    @Test
    fun `voice end for a stream that is not open reports failure and sends nothing`() {
        assertEquals(false, end())
        assertEquals(0, sink.control.size)
    }

    // --- Doubles -------------------------------------------------------------------

    /** Every datagram, decoded, plus where it was addressed. */
    private class CapturingSink : DatagramSink {
        val control = mutableListOf<Packet>()
        val controlBytes = mutableListOf<ByteArray>()
        val controlTargets = mutableListOf<String>()
        val voice = mutableListOf<Packet>()
        val voiceFrames = mutableListOf<ByteArray>()
        val voiceTargets = mutableListOf<String>()

        override fun sendControl(bytes: ByteArray, length: Int, address: String): Boolean {
            control += PacketCodec.decode(bytes, 0, length)
                ?: error("the pipeline produced an undecodable control packet")
            controlBytes += bytes.copyOf(length)
            controlTargets += address
            return true
        }

        override fun sendVoice(
            bytes: ByteArray,
            length: Int,
            address: String,
            port: Int,
        ): Boolean {
            val packet = PacketCodec.decode(bytes, 0, length)
                ?: error("the pipeline produced an undecodable voice packet")
            voice += packet
            // The payload is a view over the sink's caller's buffer, which is
            // refilled by the next frame.
            voiceFrames += (packet.payload as VoiceDataPayload).copyFrame()
            voiceTargets += "$address:$port"
            return true
        }

        /** The marker each transmitted frame was built from, in send order. */
        fun voiceMarkers(): List<Int> = voiceFrames.map {
            (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8)
        }
    }

    /**
     * A codec whose output says which frame it came from.
     *
     * Two bytes: the first PCM sample, little-endian. Small enough to fit the
     * wire, unlike [com.saikai.ptt.core.domain.PassthroughVoiceCodec], whose
     * 640-byte frames are over the VOICE_DATA limit by design.
     */
    private class MarkerCodec(override val frameSizeSamples: Int = 320) : VoiceCodec {
        override val maxEncodedBytes: Int get() = 8
        var released: Boolean = false
            private set
        var failNext: Boolean = false

        override fun encode(pcm: ShortArray, out: ByteArray): Int {
            if (released) return -1
            if (failNext) {
                failNext = false
                return -1
            }
            val sample = pcm[0].toInt()
            out[0] = (sample and 0xFF).toByte()
            out[1] = ((sample shr 8) and 0xFF).toByte()
            return 2
        }

        override fun decode(encoded: ByteArray, offset: Int, length: Int, out: ShortArray): Int =
            -1

        override fun release() {
            released = true
        }
    }

    private class CountingRecording : VoiceRecording {
        var begun = 0
            private set
        var frames = 0
            private set
        var finished = 0
            private set
        var discarded = 0
            private set

        override fun begin(sessionId: SessionId) {
            begun++
        }

        override fun frame(pcm: ByteArray, offset: Int, length: Int, capturedAtMillis: Long) {
            frames++
        }

        override fun finish(sessionId: SessionId) {
            finished++
        }

        override fun discard(sessionId: SessionId) {
            discarded++
        }
    }
}
