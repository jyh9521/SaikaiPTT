package com.saikai.ptt.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.config.AudioCaptureSource
import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.domain.AudioError
import com.saikai.ptt.core.domain.AudioFrameSink
import com.saikai.ptt.core.domain.AudioRecorder
import com.saikai.ptt.core.domain.PcmFrameAssembler
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The microphone, on Android.
 *
 * `docs/ADR/ADR-004` section 2, in full: 16 kHz mono 16-bit PCM in 20 ms frames,
 * VOICE_COMMUNICATION first and MIC as the fallback, a buffer of at least four
 * frames, its own thread at urgent-audio priority, and not one allocation per
 * frame.
 *
 * **A dedicated thread, not a coroutine.** `AudioRecord.read` blocks, and this
 * loop blocks in it for the whole of a transmission. On a dispatcher that would
 * take a pool thread out of circulation while telling the runtime it was doing
 * something interruptible; it also could not be given
 * THREAD_PRIORITY_URGENT_AUDIO, which is what keeps a low-end device from
 * dropping frames whenever something else wakes up.
 *
 * **VOICE_COMMUNICATION first** because the platform's echo cancellation, noise
 * suppression and gain control are the difference between usable and useless in
 * the environments this product is for. Some ROMs refuse it, hence the fallback,
 * and the ordering lives in config so a device that behaves badly can be
 * corrected without a code change.
 */
class AndroidAudioRecorder(
    context: Context,
    private val config: SaikaiConfig,
    private val logger: Logger,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AudioRecorder {

    private val appContext = context.applicationContext
    private val lock = Any()
    private var running: Running? = null

    override val isRecording: Boolean get() = synchronized(lock) { running != null }

    override suspend fun start(sink: AudioFrameSink): Outcome<Unit, AudioError> {
        synchronized(lock) {
            if (running != null) return Outcome.failure(AudioError.ALREADY_RECORDING)
        }

        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Outcome.failure(AudioError.PERMISSION_DENIED)
        }

        // Opening the device can block; it is not something to do on whatever
        // thread happened to press the button.
        val opened = withContext(Dispatchers.IO) { open() }
            ?: return Outcome.failure(AudioError.MICROPHONE_UNAVAILABLE)

        val session = Running(opened.record, sink)
        synchronized(lock) {
            if (running != null) {
                session.release()
                return Outcome.failure(AudioError.ALREADY_RECORDING)
            }
            running = session
        }

        session.thread.start()
        logger.i(LogCategory.AUDIO) {
            "capturing from ${opened.source} at ${config.audio.sampleRateHz}Hz, " +
                "${opened.bufferBytes}B buffer"
        }
        return Outcome.success(Unit)
    }

    override suspend fun stop() {
        val session = synchronized(lock) { running.also { running = null } } ?: return
        withContext(Dispatchers.IO) { session.close() }
        logger.i(LogCategory.AUDIO) { "capture stopped" }
    }

    private class Opened(
        val record: AudioRecord,
        val source: AudioCaptureSource,
        val bufferBytes: Int,
    )

    /**
     * Tries each configured source in order. Null when none of them will open.
     *
     * The permission is checked in [start], immediately before this runs, and a
     * revocation between the two surfaces as a SecurityException that is caught
     * below -- which is why lint's check is suppressed here rather than worked
     * around with a second check it would not recognise either.
     */
    @Suppress("MissingPermission")
    private fun open(): Opened? {
        val audio = config.audio
        val minimum = AudioRecord.getMinBufferSize(
            audio.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) {
            logger.e(LogCategory.AUDIO) {
                "device will not do ${audio.sampleRateHz}Hz mono 16-bit (code $minimum)"
            }
            return null
        }
        val bufferBytes = audio.captureBufferBytes(minimum)

        for (source in audio.captureSources) {
            val record = try {
                AudioRecord(
                    source.platformValue(),
                    audio.sampleRateHz,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            } catch (error: Throwable) {
                // IllegalArgumentException for a rejected combination,
                // SecurityException if the permission was revoked between the
                // check and here. Neither is worth propagating: the caller has a
                // fallback source to try and a typed failure to report.
                logger.w(LogCategory.AUDIO, error) { "could not construct $source" }
                continue
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                logger.w(LogCategory.AUDIO) { "$source did not initialise" }
                record.release()
                continue
            }

            try {
                record.startRecording()
            } catch (error: IllegalStateException) {
                logger.w(LogCategory.AUDIO, error) { "$source would not start" }
                record.release()
                continue
            }

            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                logger.w(LogCategory.AUDIO) { "$source is not recording after start" }
                record.stop()
                record.release()
                continue
            }

            return Opened(record, source, bufferBytes)
        }

        logger.w(LogCategory.AUDIO) { "no capture source would open" }
        return null
    }

    private fun AudioCaptureSource.platformValue(): Int = when (this) {
        AudioCaptureSource.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        AudioCaptureSource.MIC -> MediaRecorder.AudioSource.MIC
    }

    private inner class Running(
        private val record: AudioRecord,
        private val sink: AudioFrameSink,
    ) {
        @Volatile
        private var stopping = false

        private val assembler = PcmFrameAssembler(config.audio.frameSizeBytes)

        // Read in whole frames. Nothing here is allocated again for the life of
        // the session -- not the scratch buffer, not the assembler's frame, and
        // not a lambda, because the sink is a fun interface.
        private val scratch = ByteArray(config.audio.frameSizeBytes)

        val thread = Thread({ loop() }, THREAD_NAME).apply { isDaemon = true }

        private fun loop() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                while (!stopping) {
                    val read = record.read(scratch, 0, scratch.size)
                    if (read > 0) {
                        assembler.accept(scratch, 0, read) { frame, length ->
                            sink.onFrame(frame, 0, length, nowMillis())
                        }
                        continue
                    }
                    if (read == 0) continue
                    // Negative codes are ERROR_INVALID_OPERATION and friends. The
                    // device is gone; there is nothing to retry.
                    if (!stopping) {
                        logger.e(LogCategory.AUDIO) { "capture read failed with $read" }
                    }
                    return
                }
            } catch (error: Throwable) {
                if (!stopping) logger.e(LogCategory.AUDIO, error) { "capture loop failed" }
            }
        }

        fun close() {
            stopping = true
            // The loop is blocked in read(); stopping the device is what returns
            // it, exactly as closing a socket is what returns receive().
            try {
                record.stop()
            } catch (_: IllegalStateException) {
                // Already stopped, or never started. Not worth a crash on the
                // way out.
            }
            thread.join(JOIN_TIMEOUT_MILLIS)
            record.release()
        }

        fun release() {
            try {
                record.stop()
            } catch (_: IllegalStateException) {
                // As above.
            }
            record.release()
        }
    }

    private companion object {
        const val THREAD_NAME = "audio-capture"
        const val JOIN_TIMEOUT_MILLIS = 500L
    }
}
