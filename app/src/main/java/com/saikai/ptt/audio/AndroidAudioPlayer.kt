package com.saikai.ptt.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.domain.AudioError
import com.saikai.ptt.core.domain.AudioPlayer
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The speaker, on Android.
 *
 * `docs/ADR/ADR-004` section 3 and `docs/ADR/ADR-008`: USAGE_MEDIA with
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
 * **USAGE_MEDIA, and that is the fix for the earpiece.** ADR-004 originally
 * asked for USAGE_VOICE_COMMUNICATION, which is carried on STREAM_VOICE_CALL --
 * the phone call stream, which every device with an earpiece routes to the
 * earpiece by default. Correct for a call, wrong for a walkie-talkie, which is
 * held in front of you. On real hardware it played out of the earpiece on the
 * phone and out of the speaker on the tablet, and the tablet was only right
 * because it has no earpiece to choose.
 *
 * A track-level `preferredDevice` does not override it: routing for that stream
 * belongs to the audio policy. The platform's own answer,
 * `setCommunicationDevice`, arrived in API 31, and the reference low-end device
 * is Android 11; its predecessor only works in MODE_IN_COMMUNICATION, which
 * this class deliberately does not enter. So the usage changes instead, and
 * ADR-008 records why. Media routes to the speaker on every API level with no
 * forcing at all, and the volume keys then adjust the media volume -- which is
 * the one a user can actually set, rather than the call volume, which most ROMs
 * only expose while a call is in progress.
 *
 * The built-in speaker is still set as the track's preferred device, as a
 * second line, and only when nothing the user attached is present: a headset,
 * USB or Bluetooth output means they have said where they want it. The choice
 * is made as the track opens, and a track is opened per transmission, so
 * unplugging a headset between transmissions is picked up; doing it in the
 * middle of one is not, which costs a few seconds at most.
 *
 * **Writes never block.** This sits between the network and the speaker, and a
 * write that waited for room would turn one late frame into every later frame
 * being late too.
 *
 * **Stopping waits for what has already been queued.** `AudioTrack.stop()` in
 * streaming mode plays out what it holds and then stops; `pause()` followed by
 * `flush()` throws it away. Between the jitter buffer's cushion and the device's
 * own buffer there is always a fraction of a second in flight when a
 * transmission ends, and discarding it clips the last word off every single
 * one -- the kind of fault that ships and is then blamed on the network. The
 * wait is bounded by how much the device could possibly be holding.
 */
class AndroidAudioPlayer(
    context: Context,
    private val config: SaikaiConfig,
    private val logger: Logger,
) : AudioPlayer {

    private val appContext = context.applicationContext

    private val lock = Any()
    private var track: AudioTrack? = null

    /** Frames handed to the device since [start]. Guarded by [lock]. */
    private var framesWritten: Long = 0L

    /** The device's buffer, in frames, which bounds how long a drain can take. */
    private var trackBufferFrames: Int = 0

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
            framesWritten = 0L
            trackBufferFrames = bufferBytes / audio.bytesPerSample
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
        synchronized(lock) { framesWritten += written / config.audio.bytesPerSample }
        return written
    }

    override suspend fun stop() {
        val current: AudioTrack
        val written: Long
        val bufferFrames: Int
        synchronized(lock) {
            current = track ?: return
            track = null
            written = framesWritten
            bufferFrames = trackBufferFrames
            framesWritten = 0L
            trackBufferFrames = 0
        }

        val waitMillis = withContext(Dispatchers.IO) { stopAndMeasure(current, written, bufferFrames) }
        if (waitMillis > 0L) delay(waitMillis)
        withContext(Dispatchers.IO) { releaseTrack(current) }

        logger.i(LogCategory.AUDIO) { "playback stopped after ${waitMillis}ms of playout" }
    }

    /**
     * Stops the device and reports how long what is queued needs to be heard.
     *
     * The remainder is the difference between what was written and what the
     * playback head has reached. Bounded by the device's own buffer, because
     * that is the most it can be holding, and because an unbounded wait here
     * would be a wait in the middle of the service shutting down.
     */
    private fun stopAndMeasure(track: AudioTrack, written: Long, bufferFrames: Int): Long = try {
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            0L
        } else {
            // The head position is an unsigned frame counter; a session would
            // have to run for days to wrap it at 16 kHz.
            val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            val queued = (written - head).coerceAtLeast(0L).coerceAtMost(bufferFrames.toLong())
            track.stop()
            queued * 1_000L / config.audio.sampleRateHz
        }
    } catch (_: IllegalStateException) {
        0L
    }

    private fun open(bufferBytes: Int): AudioTrack? {
        val audio = config.audio
        val attributes = AudioAttributes.Builder()
            // Not USAGE_VOICE_COMMUNICATION: see the class comment and ADR-008.
            .setUsage(AudioAttributes.USAGE_MEDIA)
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

        // Before play(), so the first frame comes out of the right place.
        preferredOutput()?.let { built.preferredDevice = it }

        return try {
            built.play()
            logger.i(LogCategory.AUDIO) {
                "routed to ${describe(built.routedDevice)}"
            }
            built
        } catch (error: IllegalStateException) {
            logger.e(LogCategory.AUDIO, error) { "output device would not start" }
            built.release()
            null
        }
    }

    /**
     * The built-in speaker, unless the user has attached something.
     *
     * Null means "no preference", which leaves the platform's own routing in
     * place -- the right answer whenever a headset, a USB output or a Bluetooth
     * device is connected, because connecting it is how the user says where the
     * audio should go.
     */
    private fun preferredOutput(): AudioDeviceInfo? {
        val manager = appContext.getSystemService(AudioManager::class.java) ?: return null
        val outputs = try {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (error: RuntimeException) {
            logger.w(LogCategory.AUDIO, error) { "could not enumerate output devices" }
            return null
        }

        if (outputs.any { it.type in attachedOutputTypes }) return null
        return outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    /**
     * Outputs whose presence means the user has chosen where the audio goes.
     *
     * Built at run time because two of them did not exist at this project's
     * minimum API level.
     */
    private val attachedOutputTypes: Set<Int> = buildSet {
        add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        add(AudioDeviceInfo.TYPE_USB_HEADSET)
        add(AudioDeviceInfo.TYPE_USB_DEVICE)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        add(AudioDeviceInfo.TYPE_HEARING_AID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
    }

    /** Names a routed device for the log, which is how a routing surprise is found. */
    private fun describe(device: AudioDeviceInfo?): String = when (device?.type) {
        null -> "nothing"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "the speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "the earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "a headset"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        else -> "device type ${device.type}"
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
                // Discarding, not draining: by the time this runs either
                // stopAndMeasure has already played the tail out, or the track
                // is one that never became the live one.
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
