package com.saikai.ptt.core.asr

import com.saikai.ptt.core.audio.OggError
import com.saikai.ptt.core.audio.OggOpusWriter
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.domain.PassthroughVoiceCodec
import com.saikai.ptt.core.domain.PcmConversion
import com.saikai.ptt.core.domain.VoiceCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The whole path from a file on disk to the samples a recogniser is handed.
 *
 * `PassthroughVoiceCodec` is what makes this provable rather than plausible:
 * with a real Opus decoder the output only resembles the input, so a test could
 * only assert that something came out. Here the samples that come back are the
 * samples that went in, so an off-by-one in the frame loop or a wrong scale
 * factor is visible.
 */
class RecordingDecoderTest {

    private val sampleRate = 16_000
    private val frameSamples = 320

    /** A codec whose encoded frames are just the PCM, so the trip is lossless. */
    private fun codec(): VoiceCodec = PassthroughVoiceCodec(frameSamples)

    /** A recognisable ramp, so a misordered or dropped frame shows up. */
    private fun pcm(frameCount: Int): ShortArray =
        ShortArray(frameCount * frameSamples) { index ->
            (((index * 7) % 60_000) - 30_000).toShort()
        }

    /** Writes [pcm] through the codec and the Ogg writer, as a recording would be. */
    private fun recordingOf(pcm: ShortArray): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = OggOpusWriter(out, sampleRate, frameSamples, serialNumber = 7)
        val encoder = codec()
        val encoded = ByteArray(encoder.maxEncodedBytes)
        var at = 0
        while (at + frameSamples <= pcm.size) {
            val frame = pcm.copyOfRange(at, at + frameSamples)
            val length = encoder.encode(frame, encoded)
            check(length > 0)
            writer.write(encoded, 0, length)
            at += frameSamples
        }
        writer.finish()
        encoder.release()
        return out.toByteArray()
    }

    private fun decodeOk(bytes: ByteArray): DecodedRecording {
        val codec = codec()
        val outcome = RecordingDecoder.decode(bytes, codec, sampleRate)
        codec.release()
        assertTrue("decode failed: $outcome", outcome is Outcome.Success)
        return (outcome as Outcome.Success).value
    }

    // --- the round trip ----------------------------------------------------------------

    @Test
    fun `the samples that went in are the samples that come out`() {
        val original = pcm(frameCount = 25)

        val decoded = decodeOk(recordingOf(original))

        assertEquals(original.size, decoded.samples.size)
        original.forEachIndexed { index, sample ->
            // The scale is exact in binary, so this is an equality, not a
            // tolerance. A tolerance here would hide a wrong divisor.
            assertEquals("sample $index", sample / 32768f, decoded.samples[index], 0f)
        }
        assertTrue(decoded.intact)
    }

    @Test
    fun `samples land inside the range a recogniser expects`() {
        // Includes Short.MIN_VALUE, which is where a 32767 divisor would push
        // the result past -1.0.
        val extremes = ShortArray(frameSamples) { index ->
            when (index % 4) {
                0 -> Short.MIN_VALUE
                1 -> Short.MAX_VALUE
                2 -> 0
                else -> -1
            }
        }

        val decoded = decodeOk(recordingOf(extremes))

        decoded.samples.forEach { sample ->
            assertTrue("$sample is outside -1.0..1.0", sample >= -1.0f && sample <= 1.0f)
        }
        assertEquals(-1.0f, decoded.samples[0], 0f)
    }

    @Test
    fun `a long recording keeps its frames in order across pages`() {
        val original = pcm(frameCount = OggOpusWriter.FRAMES_PER_PAGE * 4 + 7)

        val decoded = decodeOk(recordingOf(original))

        assertEquals(original.size, decoded.samples.size)
        assertEquals(original.first() / 32768f, decoded.samples.first(), 0f)
        assertEquals(original.last() / 32768f, decoded.samples.last(), 0f)
    }

    @Test
    fun `duration is derived from the samples actually produced`() {
        val decoded = decodeOk(recordingOf(pcm(frameCount = 150)))

        // 150 frames of 320 samples at 16 kHz is exactly three seconds. Unlike
        // the container's granule, this figure has no pre-skip in it -- it is
        // what the recogniser is being given.
        assertEquals(3_000L, decoded.durationMillis)
    }

    // --- damage ------------------------------------------------------------------------

    @Test
    fun `a corrupt page costs its own frames and nothing else`() {
        val original = pcm(frameCount = OggOpusWriter.FRAMES_PER_PAGE * 3)
        val bytes = recordingOf(original)
        bytes[bytes.size - 30] = (bytes[bytes.size - 30] + 1).toByte()

        val decoded = decodeOk(bytes)

        assertFalse(decoded.intact)
        assertTrue(decoded.pagesSkipped > 0)
        assertTrue("most of the audio survives", decoded.samples.size >= original.size / 2)
        // What survived is still correct, not shifted.
        assertEquals(original.first() / 32768f, decoded.samples.first(), 0f)
    }

    @Test
    fun `a frame the codec refuses is skipped and counted`() {
        val decoded = run {
            val bytes = recordingOf(pcm(frameCount = 10))
            // A codec that rejects every third frame, standing in for a real
            // decoder meeting a frame it cannot make sense of.
            val picky = object : VoiceCodec {
                private val real = PassthroughVoiceCodec(frameSamples)
                private var seen = 0
                override val frameSizeSamples get() = real.frameSizeSamples
                override val maxEncodedBytes get() = real.maxEncodedBytes
                override fun encode(pcm: ShortArray, out: ByteArray) = real.encode(pcm, out)
                override fun decode(
                    encoded: ByteArray,
                    offset: Int,
                    length: Int,
                    out: ShortArray,
                ): Int {
                    seen++
                    return if (seen % 3 == 0) -1 else real.decode(encoded, offset, length, out)
                }
                override fun release() = real.release()
            }
            val outcome = RecordingDecoder.decode(bytes, picky, sampleRate)
            assertTrue(outcome is Outcome.Success)
            (outcome as Outcome.Success).value
        }

        assertEquals(3, decoded.framesDropped)
        assertEquals(7 * frameSamples, decoded.samples.size)
        assertFalse(decoded.intact)
    }

    @Test
    fun `a truncated recording decodes what reached the disk`() {
        val whole = recordingOf(pcm(frameCount = OggOpusWriter.FRAMES_PER_PAGE * 2))
        val cut = whole.copyOfRange(0, whole.size - 50)

        val decoded = decodeOk(cut)

        assertTrue(decoded.truncated)
        assertTrue(decoded.samples.isNotEmpty())
    }

    // --- nothing to decode -------------------------------------------------------------

    @Test
    fun `a recording with no audio packets is reported as such`() {
        val out = ByteArrayOutputStream()
        OggOpusWriter(out, sampleRate, frameSamples, serialNumber = 7).finish()

        val codec = codec()
        val outcome = RecordingDecoder.decode(out.toByteArray(), codec, sampleRate)
        codec.release()

        assertEquals(Outcome.failure(DecodeError.NoAudio), outcome)
    }

    @Test
    fun `a file that is not a recording is reported as a container problem`() {
        val codec = codec()
        val outcome = RecordingDecoder.decode(ByteArray(64), codec, sampleRate)
        codec.release()

        assertTrue(outcome is Outcome.Failure)
        val error = (outcome as Outcome.Failure).error
        assertTrue("got $error", error is DecodeError.Container)
        assertEquals(OggError.NotOgg, (error as DecodeError.Container).reason)
    }

    @Test
    fun `a recording whose every frame is refused is not silently empty`() {
        val bytes = recordingOf(pcm(frameCount = 5))
        val broken = object : VoiceCodec {
            override val frameSizeSamples = frameSamples
            override val maxEncodedBytes = frameSamples * 2
            override fun encode(pcm: ShortArray, out: ByteArray) = -1
            override fun decode(encoded: ByteArray, offset: Int, length: Int, out: ShortArray) = -1
            override fun release() = Unit
        }

        assertEquals(
            Outcome.failure(DecodeError.NoAudio),
            RecordingDecoder.decode(bytes, broken, sampleRate),
        )
    }

    @Test
    fun `PcmConversion and the decoder agree about byte order`() {
        // Belt and braces: the writer path uses PcmConversion, the read path
        // goes through the codec. If those ever disagreed the audio would be
        // noise, and a ramp would still "look like data".
        val samples = shortArrayOf(0, 1, -1, 256, -256, Short.MAX_VALUE, Short.MIN_VALUE)
        val bytes = ByteArray(samples.size * 2)
        PcmConversion.shortsToBytes(samples, 0, samples.size, bytes, 0)
        val back = ShortArray(samples.size)
        PcmConversion.bytesToShorts(bytes, 0, bytes.size, back, 0)

        assertTrue(samples.contentEquals(back))
    }
}
