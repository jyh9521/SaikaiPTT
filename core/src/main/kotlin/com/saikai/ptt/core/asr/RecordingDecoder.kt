package com.saikai.ptt.core.asr

import com.saikai.ptt.core.audio.OggError
import com.saikai.ptt.core.audio.OggOpusReader
import com.saikai.ptt.core.audio.OggOpusStream
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.domain.VoiceCodec

/**
 * A recording on disk to the samples a recogniser accepts.
 *
 * Ogg container off ([OggOpusReader]), Opus frames through the decoder the
 * receive path already owns ([VoiceCodec]), 16-bit PCM scaled to `-1.0..1.0`.
 * Nothing is re-encoded at any point (ADR-004 section 6).
 *
 * In `:core` and not in `:app` because every step of it can be proved on the
 * JVM: with `PassthroughVoiceCodec` the samples that come out are the samples
 * that went in, which is the only way to tell "the pipeline works" from "the
 * pipeline produces something that sounds about right".
 *
 * ### A damaged recording still decodes
 *
 * [OggOpusReader] skips pages it cannot trust; a frame the codec refuses is
 * skipped here the same way. Both are counted and reported, because a
 * transcript made from audio with holes in it is worth marking as such --
 * `docs/05_DataModel.md` section 48 again: what can be salvaged, is.
 */
object RecordingDecoder {

    /**
     * @param bytes the whole `.opus` file.
     * @param codec a decoder for the frames. Caller owns it and releases it.
     * @param sampleRateHz the rate the file was encoded at. Taken from the
     *   caller rather than from `OpusHead`, because RFC 7845 makes that field
     *   informational and ADR-004 fixes the real value at 16 kHz.
     */
    fun decode(
        bytes: ByteArray,
        codec: VoiceCodec,
        sampleRateHz: Int,
    ): Outcome<DecodedRecording, DecodeError> {
        val stream: OggOpusStream = when (val parsed = OggOpusReader.read(bytes)) {
            is Outcome.Success -> parsed.value
            is Outcome.Failure -> return Outcome.failure(DecodeError.Container(parsed.error))
        }
        return decode(stream, codec, sampleRateHz)
    }

    fun decode(
        stream: OggOpusStream,
        codec: VoiceCodec,
        sampleRateHz: Int,
    ): Outcome<DecodedRecording, DecodeError> {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        if (stream.packets.isEmpty()) return Outcome.failure(DecodeError.NoAudio)

        // One frame's worth of headroom per packet. A decoder may return fewer
        // samples than the frame size but never more, so this cannot overflow.
        val frame = ShortArray(codec.frameSizeSamples)
        val samples = FloatArrayBuilder(stream.packets.size * codec.frameSizeSamples)
        var framesDropped = 0

        for (packet in stream.packets) {
            val produced = codec.decode(packet, 0, packet.size, frame)
            if (produced <= 0) {
                // A frame the decoder will not take. Dropping it leaves a gap
                // of at most 20 ms, which is worth far more than abandoning the
                // whole recording.
                framesDropped++
                continue
            }
            samples.add(frame, produced)
        }

        if (samples.size == 0) return Outcome.failure(DecodeError.NoAudio)

        return Outcome.success(
            DecodedRecording(
                samples = samples.toFloatArray(),
                sampleRateHz = sampleRateHz,
                framesDropped = framesDropped,
                pagesSkipped = stream.pagesSkipped,
                truncated = stream.truncated,
            )
        )
    }

    /**
     * Grows without copying on every append.
     *
     * An `ArrayList<Float>` would box every sample; a minute of audio is a
     * million of them.
     */
    private class FloatArrayBuilder(initial: Int) {
        private var buffer = FloatArray(initial.coerceAtLeast(1024))
        var size: Int = 0
            private set

        fun add(pcm: ShortArray, count: Int) {
            if (size + count > buffer.size) {
                buffer = buffer.copyOf(maxOf(buffer.size * 2, size + count))
            }
            for (index in 0 until count) {
                // Divide by 32768 rather than 32767: it makes the mapping exact
                // in binary and puts Short.MIN_VALUE at exactly -1.0, where
                // 32767 would push it past.
                buffer[size + index] = pcm[index] / 32768f
            }
            size += count
        }

        fun toFloatArray(): FloatArray = buffer.copyOf(size)
    }
}

/** Samples ready for a recogniser, and what was lost getting them. */
class DecodedRecording(
    /** Mono PCM in `-1.0..1.0`. */
    val samples: FloatArray,
    val sampleRateHz: Int,
    /** Frames the codec refused. */
    val framesDropped: Int,
    /** Container pages the reader could not trust. */
    val pagesSkipped: Int,
    /** True when the file ended mid-page. */
    val truncated: Boolean,
) {
    val durationMillis: Long get() = samples.size * 1000L / sampleRateHz

    /** True when nothing was lost. An ordinary recording is intact. */
    val intact: Boolean get() = framesDropped == 0 && pagesSkipped == 0 && !truncated
}

/** Why a recording produced no samples at all. */
sealed interface DecodeError {
    /** The file is not a readable Ogg Opus recording. */
    data class Container(val reason: OggError) : DecodeError

    /** Readable, but there was nothing in it the decoder would take. */
    data object NoAudio : DecodeError
}
