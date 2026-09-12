package com.saikai.ptt.core.session

import com.saikai.ptt.core.protocol.SessionId

/**
 * Where a transmission is written down, if anywhere.
 *
 * `docs/01_PRD.md` section 10.3 ends a transmission with "finish the local
 * recording" and "create the history record", and Task38 is what actually
 * writes a file. This is the seam that lets the send pipeline be finished and
 * verified now: the pipeline calls it at the right four moments, and until
 * Task38 the implementation is [NoVoiceRecording].
 *
 * A seam rather than a stub inside the pipeline, because the four moments are
 * the part that is easy to get wrong and hard to add later -- in particular
 * [discard], which is what keeps a refused or unanswered call from leaving a
 * file and a history row behind. `docs/01_PRD.md` section 10.4 is explicit that
 * BUSY and a timeout produce no record at all.
 *
 * Recording captures what was *spoken*, which is not the same as what was sent:
 * the pre-roll frames captured before the peer accepted belong in the file even
 * when the peer never accepts them, and a session cut off mid-word should still
 * finish the file it has.
 */
interface VoiceRecording {

    /** A transmission started. Called before any frame. */
    fun begin(sessionId: SessionId)

    /**
     * One 20 ms frame of PCM, on the capture thread.
     *
     * **[pcm] is the capture buffer and is reused immediately.** Anything kept
     * past this call must copy it. Must return before the next frame is due;
     * a file write belongs on another thread.
     */
    fun frame(pcm: ByteArray, offset: Int, length: Int, capturedAtMillis: Long)

    /** The transmission ended normally. The recording is worth keeping. */
    fun finish(sessionId: SessionId)

    /** The transmission never happened, or is not worth keeping. Leave nothing behind. */
    fun discard(sessionId: SessionId)
}

/**
 * Records nothing.
 *
 * The implementation until Task38, and what the pipeline's own tests run
 * against: every path through the send pipeline is exercisable without a
 * filesystem.
 */
object NoVoiceRecording : VoiceRecording {
    override fun begin(sessionId: SessionId) = Unit
    override fun frame(pcm: ByteArray, offset: Int, length: Int, capturedAtMillis: Long) = Unit
    override fun finish(sessionId: SessionId) = Unit
    override fun discard(sessionId: SessionId) = Unit
}
