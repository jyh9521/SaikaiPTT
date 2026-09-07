package com.saikai.ptt.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.config.LoggingConfig
import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.LogSink
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.WireFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Opus, on a real device.
 *
 * An instrumented test rather than a JVM one because the whole subject is a
 * native library: what is being checked is that `libsaikaiopus.so` loaded, that
 * the settings in ADR-004 were accepted by this device's build of libopus, and
 * that the frames it produces fit the wire.
 *
 * **The quality assertions align for the codec's delay first, and that is not a
 * detail.** Opus at 16 kHz introduces about 6.3 ms of algorithmic lookahead, so
 * comparing the decoded samples against the input position by position gives a
 * negative signal-to-noise ratio for a codec that is working perfectly. A
 * round-trip test written the obvious way fails, and the obvious next move --
 * loosening the threshold until it passes -- throws away the only assertion that
 * would have caught a genuinely broken codec.
 */
@RunWith(AndroidJUnit4::class)
class OpusVoiceCodecTest {

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

    private lateinit var codec: OpusVoiceCodec

    private val frame get() = config.audio.frameSizeSamples

    @Before
    fun setUp() {
        codec = when (val outcome = OpusVoiceCodec.create(config, logger)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("codec unavailable: ${outcome.error}")
        }
    }

    @After
    fun tearDown() {
        codec.release()
    }

    /** Something speech-shaped: a moving pitch with a moving envelope. */
    private fun speechLike(frames: Int): ShortArray {
        val samples = ShortArray(frames * frame)
        var phase = 0.0
        for (index in 0 until frames) {
            val hz = 300 + 40 * sin(index / 8.0)
            for (offset in 0 until frame) {
                phase += 2 * PI * hz / config.audio.sampleRateHz
                val envelope = 0.6 + 0.4 * sin(index / 20.0)
                samples[index * frame + offset] = (sin(phase) * 9_000 * envelope).toInt().toShort()
            }
        }
        return samples
    }

    @Test
    fun aFrameSurvivesTheRoundTrip() {
        val pcm = speechLike(1)
        val encoded = ByteArray(codec.maxEncodedBytes)
        val decoded = ShortArray(frame)

        val bytes = codec.encode(pcm, encoded)
        assertTrue("encode failed with $bytes", bytes > 0)
        assertEquals(frame, codec.decode(encoded, 0, bytes, decoded))
    }

    @Test
    fun everyPacketFitsTheWireWithRoomToSpare() {
        // ADR-003 caps a VOICE_DATA payload at 400 bytes. At 20 kbps CBR a 20 ms
        // frame is 50, so this is not a close-run thing -- but the cap is what
        // the whole datagram budget is built on, and a bitrate change that broke
        // it would show up as every receiver silently dropping audio.
        val pcm = speechLike(FRAMES)
        val encoded = ByteArray(codec.maxEncodedBytes)
        var smallest = Int.MAX_VALUE
        var largest = 0

        for (index in 0 until FRAMES) {
            val bytes = codec.encode(pcm.copyOfRange(index * frame, (index + 1) * frame), encoded)
            assertTrue("encode failed with $bytes", bytes > 0)
            smallest = minOf(smallest, bytes)
            largest = maxOf(largest, bytes)
        }

        assertTrue("largest packet was $largest", largest <= WireFormat.MAX_VOICE_PAYLOAD_BYTES)
        assertEquals("constant bitrate should give a constant size", smallest, largest)
    }

    @Test
    fun theDecodedAudioIsTheAudioThatWentIn() {
        val pcm = speechLike(FRAMES)
        val decoded = ShortArray(pcm.size)
        val encoded = ByteArray(codec.maxEncodedBytes)
        val one = ShortArray(frame)

        for (index in 0 until FRAMES) {
            val bytes = codec.encode(pcm.copyOfRange(index * frame, (index + 1) * frame), encoded)
            codec.decode(encoded, 0, bytes, one)
            one.copyInto(decoded, index * frame)
        }

        // Find the codec's delay, then judge the audio with it removed. Opus is
        // not waveform preserving and never claimed to be; what it preserves is
        // the signal, delayed.
        val window = config.audio.sampleRateHz
        var bestLag = 0
        var bestCorrelation = -1.0
        for (lag in 0..MAX_LAG) {
            val correlation = correlate(pcm, decoded, window, window, lag)
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation
                bestLag = lag
            }
        }

        var error = 0.0
        var signal = 0.0
        for (index in window until window * 2) {
            val difference = (pcm[index] - decoded[index + bestLag]).toDouble()
            error += difference * difference
            signal += pcm[index].toDouble() * pcm[index]
        }
        val snr = 10 * log10(signal / error)

        assertTrue("correlation was only $bestCorrelation at lag $bestLag", bestCorrelation > 0.9)
        assertTrue("aligned SNR was only $snr dB (lag $bestLag)", snr > 10.0)
        assertTrue("delay of $bestLag samples is implausible", bestLag in 1..MAX_LAG - 1)
    }

    @Test
    fun aLostFrameIsRecoveredFromTheNextPacket() {
        // This is the only thing that makes ADR-004's in-band FEC worth its
        // bitrate: the redundant copy rides in the *following* packet and only
        // exists if the decoder asks for it.
        val pcm = speechLike(2)
        val first = ByteArray(codec.maxEncodedBytes)
        val second = ByteArray(codec.maxEncodedBytes)
        val recovered = ShortArray(frame)

        codec.encode(pcm.copyOfRange(0, frame), first)
        val secondBytes = codec.encode(pcm.copyOfRange(frame, frame * 2), second)

        assertEquals(frame, codec.decodeLost(second, 0, secondBytes, recovered))
    }

    @Test
    fun aGapIsConcealedRatherThanLeftEmpty() {
        val pcm = speechLike(1)
        val encoded = ByteArray(codec.maxEncodedBytes)
        val out = ShortArray(frame)
        codec.decode(encoded, 0, codec.encode(pcm, encoded), out)

        assertEquals(frame, codec.conceal(out))
    }

    @Test
    fun aReleasedCodecRefusesRatherThanCrashing() {
        // release() frees memory outside the JVM heap. A use after it has to be
        // a return value; the alternative is a native crash with no stack trace
        // in a bug report.
        codec.release()
        codec.release()

        assertTrue(codec.encode(ShortArray(frame), ByteArray(400)) < 0)
        assertTrue(codec.decode(ByteArray(50), 0, 50, ShortArray(frame)) < 0)
        assertEquals(0, codec.decodeLost(ByteArray(50), 0, 50, ShortArray(frame)))
    }

    private fun correlate(
        a: ShortArray,
        b: ShortArray,
        from: Int,
        count: Int,
        lag: Int,
    ): Double {
        var dot = 0.0
        var energyA = 0.0
        var energyB = 0.0
        for (index in from until from + count) {
            val shifted = index + lag
            if (shifted >= b.size) break
            val x = a[index].toDouble()
            val y = b[shifted].toDouble()
            dot += x * y
            energyA += x * x
            energyB += y * y
        }
        if (energyA == 0.0 || energyB == 0.0) return -1.0
        return dot / sqrt(energyA * energyB)
    }

    private companion object {
        const val FRAMES = 100
        const val MAX_LAG = 400
    }
}
