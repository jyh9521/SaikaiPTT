package com.saikai.ptt.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.saikai.ptt.core.asr.RecognitionError
import com.saikai.ptt.core.asr.SpeechRecognizer
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [SpeechRecognizer] on sherpa-onnx (ADR-012).
 *
 * A zipformer transducer: three int8 ONNX graphs and a token table, loaded from
 * files rather than from assets, because the model is downloaded rather than
 * bundled (`ADR-006` section 2, still in force under ADR-012).
 *
 * ### The model is loaded late and dropped early
 *
 * Over 150 MB resident on a device with 4 GB. It is created on the first
 * recognition after an idle period and released by [release] when the worker
 * runs out of work, so a phone that is not transcribing is not holding it.
 * Reloading costs a file read nobody is waiting on -- recognition already runs
 * after the call, not during it (`CLAUDE.md` section 18.3).
 *
 * ### One thread, low priority
 *
 * `numThreads = 1`, and everything runs on [dispatcher], which the container
 * gives a thread below the audio ones. Section 18.3 puts voice above
 * recognition unconditionally; two of these on a four-core MTK P22 would be
 * taking that from the wrong end. The mutex is not for correctness of the
 * native handle alone -- it is what makes "one recognition at a time" true even
 * if a caller forgets.
 */
class SherpaRecognizer(
    private val model: AsrModel,
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher,
    private val expectedSampleRateHz: Int,
) : SpeechRecognizer {

    private val lock = Mutex()

    /** Null when nothing is loaded. Only touched under [lock]. */
    private var engine: OfflineRecognizer? = null

    override val available: Boolean get() = model.installed

    override suspend fun recognise(
        samples: FloatArray,
        sampleRateHz: Int,
    ): Outcome<String, RecognitionError> {
        if (sampleRateHz != expectedSampleRateHz) {
            // ADR-004 fixes this at 16 kHz everywhere. A mismatch is a bug
            // upstream, and resampling here would hide it while quietly making
            // every transcript worse.
            logger.e(LogCategory.ASR) { "refusing $sampleRateHz Hz audio" }
            return Outcome.failure(RecognitionError.AUDIO_REJECTED)
        }
        if (samples.isEmpty()) return Outcome.failure(RecognitionError.AUDIO_REJECTED)
        if (!model.installed) return Outcome.failure(RecognitionError.ENGINE_UNAVAILABLE)

        return lock.withLock {
            withContext(dispatcher) {
                val recognizer = engine ?: create() ?: return@withContext Outcome.failure(
                    RecognitionError.ENGINE_UNAVAILABLE
                )
                try {
                    val stream = recognizer.createStream()
                    try {
                        stream.acceptWaveform(samples, sampleRateHz)
                        recognizer.decode(stream)
                        Outcome.success(recognizer.getResult(stream).text)
                    } finally {
                        stream.release()
                    }
                } catch (error: Throwable) {
                    // Includes the Errors a native library can raise. One
                    // recording is not worth taking the process down, and the
                    // engine is dropped so the next attempt starts clean.
                    logger.e(LogCategory.ASR, error) { "recognition failed" }
                    dropEngine()
                    Outcome.failure(RecognitionError.ENGINE_FAILED)
                }
            }
        }
    }

    /** @return null when the engine could not be built from the files on disk. */
    private fun create(): OfflineRecognizer? = try {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = expectedSampleRateHz, featureDim = FEATURE_DIM),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = model.encoder.absolutePath,
                    decoder = model.decoder.absolutePath,
                    joiner = model.joiner.absolutePath,
                ),
                tokens = model.tokens.absolutePath,
                // One, deliberately. See the class comment.
                numThreads = 1,
                debug = false,
                provider = "cpu",
                modelType = "transducer",
            ),
            decodingMethod = "greedy_search",
        )
        // assetManager = null selects the from-file constructor.
        OfflineRecognizer(assetManager = null, config = config).also {
            engine = it
            logger.i(LogCategory.ASR) { "recogniser loaded" }
        }
    } catch (error: Throwable) {
        // A truncated model, a .so that will not load on this ABI, a device
        // out of memory. All of them mean the same thing to the caller.
        logger.e(LogCategory.ASR, error) { "could not load the recogniser" }
        engine = null
        null
    }

    override fun release() {
        // Not under the mutex: this is called from a different scope than
        // recognise(), and blocking a shutdown on a recognition in flight would
        // be worse than the race it prevents. dropEngine() tolerates being
        // called twice.
        dropEngine()
    }

    private fun dropEngine() {
        val current = engine ?: return
        engine = null
        try {
            current.release()
            logger.i(LogCategory.ASR) { "recogniser released" }
        } catch (error: Throwable) {
            logger.w(LogCategory.ASR, error) { "the recogniser did not release cleanly" }
        }
    }

    private companion object {
        /** What the zipformer models in ADR-012 were exported with. */
        const val FEATURE_DIM = 80
    }
}
