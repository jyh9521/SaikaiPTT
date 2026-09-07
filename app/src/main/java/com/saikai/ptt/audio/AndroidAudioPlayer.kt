package com.saikai.ptt.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.domain.AudioError
import com.saikai.ptt.core.domain.AudioPlayer
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The speaker, on Android.
 *
 * `docs/ADR/ADR-004` section 3: USAGE_VOICE_COMMUNICATION with
 * CONTENT_TYPE_SPEECH, low-latency performance mode, and the system's own
 * routing -- speaker by default, and whatever the user plugs in or pairs after
 * that, because a radio that ignores a headset is a radio nobody can use
 * discreetly.
 *
 * **`AudioManager.mode` is left at MODE_NORMAL, deliberately.** The obvious move
 * for voice is MODE_IN_COMMUNICATION, and it is wrong here: PTT is half duplex,
 * so the microphone and the speaker are never open at the same time and there is
 * no echo path to cancel. What that mode would do instead is change the global
 * volume stream and disturb every other app on the device, for a problem this
 * product does not have.
 *
 * The volume stream follows from the attributes: USAGE_VOICE_COMMUNICATION is
 * carried on STREAM_VOICE_CALL, which is what the ADR asks for and what the
 * hardware volume keys will adjust during a transmission.
 *
 * **Writes never block.** This sits between the network and the speaker, and a
 * write that waited for room would turn one late frame into every later frame
 * being late too.
 */
class AndroidAudioPlayer(
    private val config: SaikaiConfig,
    private val logger: Logger,
) : AudioPlayer {

    private val lock = Any()
    private var track: AudioTrack? = null

    override val isPlaying: Boolean get() = synchronized(lock) { track != null }

    override suspend fun start(): Outcome<Unit, AudioError> {
        synchronized(lock) {
            if (track != null) return Outcome.success(Unit)
        }

        val audio = config.audio
        val minimum = AudioTrack.getMinBufferSize(
            audio.sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) {
            logger.e(LogCategory.AUDIO) {
                "device will not play ${audio.sampleRateHz}Hz mono 16-bit (code $minimum)"
            }
            return Outcome.failure(AudioError.UNSUPPORTED_FORMAT)
        }
        val bufferBytes = audio.playbackBufferBytes(minimum)

        val opened = withContext(Dispatchers.IO) { open(bufferBytes) }
            ?: return Outcome.failure(AudioError.MICROPHONE_UNAVAILABLE)

        synchronized(lock) {
            if (track != null) {
                releaseTrack(opened)
                return Outcome.success(Unit)
            }
            track = opened
        }
        logger.i(LogCategory.AUDIO) { "playing at ${audio.sampleRateHz}Hz, ${bufferBytes}B buffer" }
        return Outcome.success(Unit)
    }

    override fun write(pcm: ByteArray, offset: Int, length: Int): Int {
        val current = synchronized(lock) { track } ?: return 0
        val written = try {
            current.write(pcm, offset, length, AudioTrack.WRITE_NON_BLOCKING)
        } catch (error: IllegalStateException) {
            logger.throttled(LogLevel.WARN, LogCategory.AUDIO, "playback-write", error) {
                "write failed"
            }
            return 0
        }
        if (written < 0) {
            logger.throttled(LogLevel.WARN, LogCategory.AUDIO, "playback-error") {
                "write returned $written"
            }
            return 0
        }
        if (written < length) {
            // Dropping the tail rather than waiting for room. In a live
            // conversation the newest audio is the only audio worth having.
            logger.throttled(LogLevel.DEBUG, LogCategory.AUDIO, "playback-full") {
                "output full; dropped ${length - written} of $length bytes"
            }
        }
        return written
    }

    override suspend fun stop() {
        val current = synchronized(lock) { track.also { track = null } } ?: return
        withContext(Dispatchers.IO) { releaseTrack(current) }
        logger.i(LogCategory.AUDIO) { "playback stopped" }
    }

    private fun open(bufferBytes: Int): AudioTrack? {
        val audio = config.audio
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(audio.sampleRateHz)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val built = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (error: Throwable) {
            // UnsupportedOperationException when the device will not give a
            // low-latency track with these attributes, IllegalArgumentException
            // for a rejected format. Neither is worth propagating: the caller
            // has a typed failure and a session to refuse.
            logger.e(LogCategory.AUDIO, error) { "could not open the output device" }
            return null
        }

        if (built.state != AudioTrack.STATE_INITIALIZED) {
            logger.e(LogCategory.AUDIO) { "output device did not initialise" }
            built.release()
            return null
        }

        return try {
            built.play()
            built
        } catch (error: IllegalStateException) {
            logger.e(LogCategory.AUDIO, error) { "output device would not start" }
            built.release()
            null
        }
    }

    /**
     * Stops and frees a track.
     *
     * A named function rather than an extension called `release`: AudioTrack has
     * a member of that name, members win over extensions, and the extension
     * would have been silently dead code -- while looking exactly like the thing
     * that stops the device.
     */
    private fun releaseTrack(track: AudioTrack) {
        try {
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.pause()
                track.flush()
                track.stop()
            }
        } catch (_: IllegalStateException) {
            // Already stopped. Not worth a crash on the way out.
        }
        track.release()
    }
}
