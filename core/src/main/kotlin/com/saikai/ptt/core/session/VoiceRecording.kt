package com.saikai.ptt.core.session

import com.saikai.ptt.core.domain.DeviceId
import com.saikai.ptt.core.protocol.SessionId

/**
 * Where a transmission is written down, if anywhere.
 *
 * The seam between the voice pipelines and the filesystem. Both directions use
 * it: the send side hands over the frames its encoder produced, the receive
 * side hands over the frames that arrived, and neither knows whether anything
 * is on the other end.
 *
 * ### The frames are encoded, not PCM
 *
 * ADR-004 section 6 forbids re-encoding. The bytes the encoder produced for the
 * network are the bytes that go in the file, and on the receiving side the
 * bytes that arrived are written as they arrived -- so the recording costs file
 * I/O and nothing else on either device.
 *
 * Task25 built this seam before that had been settled and passed PCM, with a
 * comment arguing that a recording is of what was *said* rather than what was
 * sent. The intent survives and the position barely moved: the send side still
 * records frames the peer may never accept, because [frame] is called after the
 * encoder and before the send-or-buffer decision. What changed is that they are
 * no longer re-encoded on the way to disk, which is what the ADR requires.
 *
 * ### What is recorded, and what is not
 *
 * The send side records everything it encoded, including the pre-roll captured
 * before the peer accepted -- that audio was spoken, and a recording that
 * started when the handshake finished would be missing the first word.
 *
 * The receive side records what the jitter buffer played out, in playout order,
 * once each. Frames that never arrived are simply not in the file, so a
 * recording of a lossy conversation is slightly shorter than the call was.
 * Filling the gaps would mean writing concealed audio, which means encoding it,
 * which is the thing the ADR forbids.
 */
interface VoiceRecording {

    /** A transmission started. Called before any frame. */
    fun begin(session: RecordingSession)

    /**
     * One encoded frame, on the thread that produced it.
     *
     * **[frame] is the codec's buffer and is reused immediately.** Anything
     * kept past this call must copy it.
     *
     * Called from the capture thread when sending and from the voice receive
     * thread when receiving, so it must return before the next frame is due --
     * 20 ms. It must never block on a file: the implementation hands the bytes
     * to another thread and returns (`docs/02_Architecture.md` section 18).
     */
    fun frame(frame: ByteArray, offset: Int, length: Int, capturedAtMillis: Long)

    /**
     * The transmission ended and the recording is worth keeping.
     *
     * @param interrupted true when it ended early -- force interrupt, timeout,
     *   network loss, audio focus taken. The audio captured so far is still
     *   kept; the record is labelled INTERRUPTED rather than COMPLETED
     *   (`docs/05_DataModel.md` section 26).
     */
    fun finish(sessionId: SessionId, interrupted: Boolean = false)

    /**
     * The transmission never happened, or is not worth keeping.
     *
     * Leaves no file and no history row. `docs/01_PRD.md` section 10.4: a call
     * refused with BUSY, or that nobody answered, produces no record at all.
     */
    fun discard(sessionId: SessionId)
}

/**
 * What a recording needs to know about the conversation it belongs to.
 *
 * Carried at [VoiceRecording.begin] rather than looked up at the end, because
 * by the end the session is gone -- and because the peer's name is a snapshot
 * of what it called itself at the time (`docs/05_DataModel.md` section 16).
 */
data class RecordingSession(
    val sessionId: SessionId,
    val peer: DeviceId,
    /** What the peer called itself when this started. */
    val peerName: String,
    /** True when this device is the one speaking. */
    val outgoing: Boolean,
    val startedAtMillis: Long,
)

/**
 * Records nothing.
 *
 * What the pipelines' own tests run against: every path through sending and
 * receiving is exercisable without a filesystem.
 */
object NoVoiceRecording : VoiceRecording {
    override fun begin(session: RecordingSession) = Unit
    override fun frame(frame: ByteArray, offset: Int, length: Int, capturedAtMillis: Long) = Unit
    override fun finish(sessionId: SessionId, interrupted: Boolean) = Unit
    override fun discard(sessionId: SessionId) = Unit
}
