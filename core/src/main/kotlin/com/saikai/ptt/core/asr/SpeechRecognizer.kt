package com.saikai.ptt.core.asr

import com.saikai.ptt.core.common.Outcome

/**
 * Turns audio into text, locally.
 *
 * An interface here and an implementation in `:app`, for the reason
 * `.claude/CLAUDE.md` section 29 gives generally and ADR-012 gives specifically:
 * the engine was changed once already (ADR-006 chose Vosk, ADR-011 rejected it,
 * ADR-012 chose sherpa-onnx), and the thing that made that survivable was that
 * no rule about *when* to recognise was written inside the engine.
 *
 * ### Everything here is local
 *
 * `CLAUDE.md` section 18: no cloud ASR, no audio upload, no remote API. The one
 * network access in the whole product is fetching the model, which happens once
 * and belongs to Task46 -- not to this interface, which only ever sees samples
 * that are already on the device.
 */
interface SpeechRecognizer {

    /**
     * Whether the engine is ready to be asked.
     *
     * False when the model has not been downloaded, or has been deleted. Not a
     * failure: ASR is off by default on every device (section 18.1), so this is
     * the ordinary answer.
     */
    val available: Boolean

    /**
     * Recognises one recording.
     *
     * @param samples mono PCM in `-1.0..1.0`, the whole recording at once.
     *   Whole rather than streamed because these are finished files and the
     *   engines this interface is written for take a complete utterance
     *   (`CLAUDE.md` section 18.3: recognition runs after a PTT segment, not
     *   during one).
     * @param sampleRateHz the rate [samples] is at. ADR-004 makes this 16 kHz
     *   everywhere in this app; it is a parameter so a mismatch is a value the
     *   engine can reject rather than an assumption that fails quietly.
     */
    suspend fun recognise(
        samples: FloatArray,
        sampleRateHz: Int,
    ): Outcome<String, RecognitionError>

    /**
     * Drops whatever the engine is holding.
     *
     * Called when the app goes idle. The model is over a hundred megabytes
     * resident (ADR-012), which is worth reclaiming on a 4 GB device, and
     * reloading it costs a file read the user is not waiting on.
     */
    fun release()
}

/** Why a recording could not be turned into text. */
enum class RecognitionError {
    /** No model on disk, or the engine could not be created from it. */
    ENGINE_UNAVAILABLE,

    /** The file could not be read or decoded. The record is still valid. */
    AUDIO_UNREADABLE,

    /** The engine ran and refused this input -- wrong rate, empty, too long. */
    AUDIO_REJECTED,

    /**
     * The engine ran and failed.
     *
     * One value rather than a taxonomy of native error codes, for the reason
     * `HistoryError.Unavailable` gives: nothing downstream behaves differently,
     * and the specific cause belongs in the log.
     */
    ENGINE_FAILED,

    /** A call was in progress and audio takes priority (section 18.3). */
    DEFERRED_TO_VOICE,
}
