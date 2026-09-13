package com.saikai.ptt.core.session

import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.config.LoggingConfig
import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.domain.AudioError
import com.saikai.ptt.core.domain.AudioPlayer
import com.saikai.ptt.core.domain.PcmConversion
import com.saikai.ptt.core.domain.VoiceCodec
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.LogSink
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding, gap filling and the decoder's lifetime.
 *
 * The ordering rules are [JitterBufferTest]'s. What is checked here is what
 * happens to a frame once the buffer has decided it is next -- and in
 * particular that a lost frame is asked for three different ways in the right
 * order, because the first of them is the only thing that makes ADR-004's FEC
 * bitrate worth paying for.
 */
class VoiceReceiverTest {

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

    private val session = SessionId.parse("12345678-9abc-def0-1234-56789abcdef0")!!
    private val other = SessionId.random()

    private val player = RecordingPlayer()
    private var codec = MarkerCodec()
    private var codecsCreated = 0

    private val subject = VoiceReceiver(
        config = config,
        logger = logger,
        player = player,
        codecs = { codecsCreated++; codec },
    )

    private fun offer(sequence: Int, sessionId: SessionId = session) {
        val frame = ByteArray(40) { sequence.toByte() }
        subject.onFrame(sessionId, sequence, frame, 0, frame.size)
    }

    @Test
    fun `frames are decoded and played once the buffer releases them`() {
        assertTrue(subject.open(session))
        offer(1)
        offer(2)
        offer(3)

        assertEquals(listOf("d1", "d2", "d3"), player.played)
        // Every frame is a whole 20 ms of PCM, whatever produced it.
        assertTrue(player.lengths.all { it == config.audio.frameSizeBytes })
    }

    @Test
    fun `a lost frame is recovered from the copy in the next packet`() {
        subject.open(session)
        offer(1)
        offer(2)
        offer(3)
        player.clear()

        // 4 never arrives; three newer frames give up on it.
        offer(5)
        offer(6)
        offer(7)

        assertEquals(listOf("fec5", "d5", "d6", "d7"), player.played)
    }

    @Test
    fun `with no next packet the codec conceals`() {
        subject.open(session)
        offer(1)
        offer(3)
        player.clear()

        // Flushing at 3 has to fill 2, and the frame after 2 is 3 -- which is
        // held, so this is still FEC. Losing the tail instead:
        subject.flush(session, finalDataSequence = 3)
        player.clear()

        subject.close()
        subject.open(session)
        offer(2)
        subject.flush(session, finalDataSequence = 2)

        // Frame 1 was never received and 2 is the follower, so FEC again --
        // the concealment path needs a gap with nothing behind it.
        assertEquals(listOf("fec2", "d2"), player.played)
    }

    @Test
    fun `concealment is used when even the follower is gone`() {
        subject.open(session)
        offer(1)
        offer(2)
        offer(3)
        player.clear()

        // A long outage: the buffer gives up on 4 onwards with nothing held
        // behind the gap it is filling.
        offer(30)

        assertTrue("expected concealment frames, got ${player.played}",
            player.played.count { it == "plc" } > 0)
    }

    @Test
    fun `silence is the last resort`() {
        codec.canConceal = false
        subject.open(session)
        offer(1)
        offer(2)
        offer(3)
        player.clear()

        offer(30)

        assertTrue(player.played.contains("silence"))
        assertTrue(player.lengths.all { it == config.audio.frameSizeBytes })
    }

    @Test
    fun `a frame that will not decode becomes a gap, not a hole`() {
        // Dropping it would shorten the audio by 20 ms with nothing to show for
        // it, and desynchronise nothing that anyone could hear.
        subject.open(session)
        offer(1)
        offer(2)
        codec.failDecodeOf = 3
        offer(3)

        assertEquals(listOf("d1", "d2", "plc"), player.played)
    }

    @Test
    fun `frames for another session, or for none, are counted and dropped`() {
        offer(1)
        assertEquals(1L, subject.strayFrames)

        subject.open(session)
        offer(1, sessionId = other)

        assertEquals(2L, subject.strayFrames)
        assertEquals(emptyList<String>(), player.played)
    }

    @Test
    fun `each transmission gets its own decoder, and gives it back`() {
        subject.open(session)
        val first = codec
        codec = MarkerCodec()

        subject.open(other)

        assertEquals(2, codecsCreated)
        assertTrue("the previous decoder was leaked", first.released)

        subject.close()
        assertTrue(codec.released)
        assertFalse(subject.isOpen)
    }

    @Test
    fun `a session with no decoder is refused rather than accepted silently`() {
        val refusing = VoiceReceiver(config, logger, player, codecs = { null })

        assertFalse(refusing.open(session))
        assertFalse(refusing.isOpen)
    }

    // --- Doubles -------------------------------------------------------------------

    private class RecordingPlayer : AudioPlayer {
        val played = mutableListOf<String>()
        val lengths = mutableListOf<Int>()

        override val isPlaying: Boolean get() = true
        override suspend fun start(): Outcome<Unit, AudioError> = Outcome.success(Unit)
        override suspend fun stop() = Unit

        override fun write(pcm: ByteArray, offset: Int, length: Int): Int {
            val samples = ShortArray(1)
            PcmConversion.bytesToShorts(pcm, offset, 2, samples, 0)
            played += MarkerCodec.nameOf(samples[0])
            lengths += length
            return length
        }

        fun clear() {
            played.clear()
            lengths.clear()
        }
    }

    /**
     * A codec that says, in its output, which path produced it.
     *
     * The first sample encodes both the source -- decode, FEC recovery,
     * concealment, or the receiver's own silence -- and the frame it came from.
     */
    private class MarkerCodec(override val frameSizeSamples: Int = 320) : VoiceCodec {
        override val maxEncodedBytes: Int get() = 400
        var released: Boolean = false
            private set
        var canConceal: Boolean = true
        var failDecodeOf: Int = -1

        override fun encode(pcm: ShortArray, out: ByteArray): Int = -1

        override fun decode(
            encoded: ByteArray,
            offset: Int,
            length: Int,
            out: ShortArray,
        ): Int {
            val marker = encoded[offset].toInt() and 0xFF
            if (marker == failDecodeOf) return -1
            out[0] = mark(DECODED, marker)
            return frameSizeSamples
        }

        override fun decodeLost(
            encoded: ByteArray,
            offset: Int,
            length: Int,
            out: ShortArray,
        ): Int {
            out[0] = mark(RECOVERED, encoded[offset].toInt() and 0xFF)
            return frameSizeSamples
        }

        override fun conceal(out: ShortArray): Int {
            if (!canConceal) return 0
            out[0] = mark(CONCEALED, 0)
            return frameSizeSamples
        }

        override fun release() {
            released = true
        }

        private fun mark(kind: Int, value: Int): Short = ((kind shl 8) or value).toShort()

        companion object {
            const val DECODED = 1
            const val RECOVERED = 2
            const val CONCEALED = 3

            fun nameOf(sample: Short): String {
                val raw = sample.toInt()
                val kind = (raw shr 8) and 0xFF
                val value = raw and 0xFF
                return when (kind) {
                    DECODED -> "d$value"
                    RECOVERED -> "fec$value"
                    CONCEALED -> "plc"
                    else -> "silence"
                }
            }
        }
    }
}
