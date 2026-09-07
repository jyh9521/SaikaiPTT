package com.saikai.ptt.core.domain

import com.saikai.ptt.core.common.Outcome

/** Why audio capture could not start. */
enum class AudioError {
    /** RECORD_AUDIO has not been granted. */
    PERMISSION_DENIED,

    /**
     * The microphone could not be opened.
     *
     * Another app has it, or a phone call does. An ordinary outcome on a shared
     * device rather than a fault (`docs/ADR/ADR-004` section 5), and the reason
     * the session machine has a MIC_UNAVAILABLE failure instead of an exception.
     */
    MICROPHONE_UNAVAILABLE,

    /** The device will not give 16 kHz mono 16-bit PCM. */
    UNSUPPORTED_FORMAT,

    /** Capture is already running. */
    ALREADY_RECORDING,
}

/**
 * Where captured audio goes.
 *
 * Called on the capture thread, once every 20 ms, and must return before the
 * next frame is due. Anything slower belongs on another thread -- a sink that
 * blocks here does not delay one frame, it drops every frame after it.
 *
 * **[pcm] is a reused buffer.** It holds the next frame as soon as this call
 * returns. Anything that outlives the call must copy. That contract is what
 * keeps a path that runs fifty times a second free of allocation, and it is the
 * same one the receive path uses for voice payloads.
 */
fun interface AudioFrameSink {
    fun onFrame(pcm: ByteArray, offset: Int, length: Int, capturedAtMillis: Long)
}

/**
 * The microphone, as the rest of the app needs it.
 *
 * One 20 ms frame of 16 kHz mono PCM at a time (`docs/ADR/ADR-004` section 1),
 * because that is exactly what the encoder consumes. Nothing above this
 * interface knows what an `AudioRecord` is.
 */
interface AudioRecorder {

    /** True between a successful [start] and [stop]. */
    val isRecording: Boolean

    /**
     * Opens the microphone and starts delivering frames to [sink].
     *
     * Returns once capture is actually running, so a caller that gets a success
     * knows the first frame is on its way rather than merely requested.
     */
    suspend fun start(sink: AudioFrameSink): Outcome<Unit, AudioError>

    /** Stops capture and releases the microphone. Safe to call when not running. */
    suspend fun stop()
}

/**
 * Cuts a stream of arbitrary reads into fixed frames.
 *
 * `AudioRecord.read` may return fewer bytes than asked for, and an encoder fed a
 * short frame produces a packet the far end cannot decode. The bug it causes is
 * particularly unpleasant: audio that is *mostly* fine, with a click wherever a
 * read happened to be short, which does not reproduce on the developer's phone.
 *
 * Holds one frame buffer for its lifetime and allocates nothing per frame.
 * Not thread safe: one assembler belongs to one capture thread.
 */
class PcmFrameAssembler(val frameSizeBytes: Int) {

    init {
        require(frameSizeBytes > 0) { "A frame is at least one byte" }
    }

    private val frame = ByteArray(frameSizeBytes)
    private var filled = 0

    /** Bytes held back, waiting for the rest of their frame. */
    val pending: Int get() = filled

    /**
     * Feeds raw bytes in, and calls [onFrame] once for each complete frame.
     *
     * @return how many frames were emitted.
     */
    fun accept(
        source: ByteArray,
        offset: Int,
        length: Int,
        onFrame: (ByteArray, Int) -> Unit,
    ): Int {
        require(offset >= 0 && length >= 0 && offset + length <= source.size) {
            "Range $offset..${offset + length} is outside a ${source.size}-byte buffer"
        }

        var read = offset
        val end = offset + length
        var emitted = 0

        while (read < end) {
            val take = minOf(end - read, frameSizeBytes - filled)
            source.copyInto(frame, filled, read, read + take)
            filled += take
            read += take
            if (filled == frameSizeBytes) {
                filled = 0
                emitted++
                onFrame(frame, frameSizeBytes)
            }
        }
        return emitted
    }

    /** Discards a partial frame. Called between sessions, never mid-stream. */
    fun reset() {
        filled = 0
    }
}
