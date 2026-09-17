package com.saikai.ptt.asr

import com.saikai.ptt.core.asr.DecodeError
import com.saikai.ptt.core.asr.RecognitionError
import com.saikai.ptt.core.asr.RecordingDecoder
import com.saikai.ptt.core.asr.SpeechRecognizer
import com.saikai.ptt.core.asr.TranscriptionQueue
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.domain.HistoryRepository
import com.saikai.ptt.core.domain.TranscriptStatus
import com.saikai.ptt.core.domain.VoiceCodec
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Drains the recognition queue: file, decode, recognise, store.
 *
 * The one place the three halves meet -- `core.asr` decides what to do,
 * `SherpaRecognizer` does it, and the repository remembers it. Kept thin on
 * purpose: everything here that could be a rule is a rule somewhere else,
 * because rules in a worker loop are rules nobody can test.
 *
 * ### Voice wins, every time round the loop
 *
 * The session state is checked before each recording and the queue is told to
 * [TranscriptionQueue.defer] rather than fail if a call is in progress
 * (`CLAUDE.md` section 18.3). A deferral spends no attempt: the work never ran,
 * and charging for it would let a busy hour exhaust a recording's retries
 * without trying it once.
 *
 * It is not preemptive. A recognition already running finishes -- at ADR-012's
 * threshold that is about as long as the recording, and killing it halfway
 * wastes the CPU already spent on something that would have to be redone.
 * What it does guarantee is that no *new* recognition starts while a call is
 * live, so the microphone never competes with the model for a fresh allocation.
 *
 * ### The model is dropped when the work runs out
 *
 * [SpeechRecognizer.release] on an empty queue, so a device that is not
 * transcribing is not holding 150 MB (ADR-012).
 */
class TranscriptionWorker(
    private val queue: TranscriptionQueue,
    private val recognizer: SpeechRecognizer,
    private val history: HistoryRepository,
    private val session: StateFlow<SessionState>,
    private val filesDir: File,
    /**
     * A fresh decoder, or null when this device could not build one.
     *
     * Nullable because the service's own factory is: `OpusVoiceCodec.create`
     * can fail on a device whose native library will not load, and ADR-004
     * names that as a real possibility rather than a defensive check. It costs
     * a recording here; it costs the whole product everywhere else.
     */
    private val codecs: () -> VoiceCodec?,
    private val sampleRateHz: Int,
    private val logger: Logger,
) {

    private var loop: Job? = null

    /**
     * One token means "there may be work now".
     *
     * Conflated, so ten recordings arriving during one call collapse into a
     * single wake-up rather than ten trips round a loop that will defer all of
     * them. The loop *waits* on this instead of returning when it finds nothing
     * to do: a worker that returned would leave [loop] non-null and every later
     * [start] a no-op, which is a queue that silently stops draining after the
     * first idle moment.
     */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** True while something is being recognised, for the screen to show. */
    @Volatile
    var working: Boolean = false
        private set

    /** Starts draining. Idempotent. */
    fun start(scope: CoroutineScope) {
        if (loop != null) return
        loop = scope.launch {
            // The end of a call is the other thing that makes work possible.
            // A child, so cancelling the loop cancels this with it.
            launch {
                session.collect { if (it == SessionState.Idle) wake.trySend(Unit) }
            }
            drain()
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        recognizer.release()
        working = false
    }

    /** Adds a finished conversation. Called as calls end, and by the sweep. */
    fun submit(recordId: String): Boolean {
        val added = queue.enqueue(recordId)
        if (added) wake.trySend(Unit)
        return added
    }

    /**
     * Reads PENDING records out of the database into the queue.
     *
     * Called at start-up and when the user turns subtitles on. Anything the
     * queue refuses keeps its PENDING status and is found by the next sweep.
     */
    suspend fun sweep(): Int {
        val pending = when (val found = history.pendingTranscripts()) {
            is Outcome.Success -> found.value
            is Outcome.Failure -> {
                logger.w(LogCategory.ASR) { "could not read pending transcripts" }
                return 0
            }
        }
        return pending.count { submit(it.id) }
    }

    private suspend fun drain() {
        while (true) {
            val item = queue.next()
            if (item == null) {
                // Nothing to do. Give the model back and sleep until something
                // is enqueued or a call ends.
                recognizer.release()
                wake.receive()
                continue
            }

            if (session.value != SessionState.Idle) {
                queue.defer(item.recordId)
                logger.d(LogCategory.ASR) { "a call is in progress; recognition waits" }
                // No lost wake-up: the session collector puts a token in the
                // channel when the call ends, whether or not this line has been
                // reached yet.
                wake.receive()
                continue
            }

            working = true
            try {
                process(item.recordId, item.lastAttempt)
            } finally {
                working = false
            }
        }
    }

    private suspend fun process(recordId: String, lastAttempt: Boolean) {
        val record = when (val found = history.byId(recordId)) {
            is Outcome.Success -> found.value
            is Outcome.Failure -> null
        }
        val path = record?.audioPath
        if (record == null || path == null) {
            // Deleted, or never had audio. Not a failure to retry.
            queue.succeeded(recordId)
            return
        }

        history.setTranscript(recordId, record.transcript, TranscriptStatus.PROCESSING)

        val outcome = recognise(File(filesDir, path))
        when (outcome) {
            is Outcome.Success -> {
                queue.succeeded(recordId)
                history.setTranscript(
                    recordId,
                    outcome.value.ifBlank { null },
                    TranscriptStatus.COMPLETED,
                )
                logger.i(LogCategory.ASR) { "transcribed a ${record.durationMs}ms recording" }
            }

            is Outcome.Failure -> {
                if (outcome.error == RecognitionError.DEFERRED_TO_VOICE) {
                    queue.defer(recordId)
                    history.setTranscript(recordId, record.transcript, TranscriptStatus.PENDING)
                    return
                }
                val next = queue.failed(recordId)
                history.setTranscript(recordId, record.transcript, next)
                logger.w(LogCategory.ASR) {
                    "recognition failed (${outcome.error}); record is now $next"
                }
                if (next == TranscriptStatus.FAILED && !lastAttempt) {
                    // The queue gave up early -- it was asked to. Worth a line,
                    // because the two counters disagreeing is a bug.
                    logger.w(LogCategory.ASR) { "gave up before the last attempt" }
                }
            }
        }
    }

    private suspend fun recognise(file: File): Outcome<String, RecognitionError> {
        val bytes = try {
            if (!file.isFile) return Outcome.failure(RecognitionError.AUDIO_UNREADABLE)
            file.readBytes()
        } catch (error: Exception) {
            logger.w(LogCategory.ASR, error) { "could not read a recording" }
            return Outcome.failure(RecognitionError.AUDIO_UNREADABLE)
        }

        val codec = codecs()
        if (codec == null) {
            logger.e(LogCategory.ASR) { "no decoder on this device; nothing can be transcribed" }
            return Outcome.failure(RecognitionError.ENGINE_UNAVAILABLE)
        }
        val decoded = try {
            RecordingDecoder.decode(bytes, codec, sampleRateHz)
        } finally {
            codec.release()
        }

        return when (decoded) {
            is Outcome.Failure -> {
                logger.w(LogCategory.ASR) { "could not decode a recording: ${decoded.error}" }
                Outcome.failure(
                    when (decoded.error) {
                        is DecodeError.Container -> RecognitionError.AUDIO_UNREADABLE
                        DecodeError.NoAudio -> RecognitionError.AUDIO_REJECTED
                    }
                )
            }

            is Outcome.Success -> {
                val audio = decoded.value
                if (!audio.intact) {
                    // Recognised anyway: a transcript of most of a sentence is
                    // worth having. Logged so a strange one has an explanation.
                    logger.i(LogCategory.ASR) {
                        "recognising a damaged recording " +
                            "(${audio.pagesSkipped} pages, ${audio.framesDropped} frames lost)"
                    }
                }
                // Checked again here, not only in drain(): decoding a long
                // recording takes long enough for a call to have started.
                if (session.value != SessionState.Idle) {
                    Outcome.failure(RecognitionError.DEFERRED_TO_VOICE)
                } else {
                    recognizer.recognise(audio.samples, audio.sampleRateHz)
                }
            }
        }
    }
}
