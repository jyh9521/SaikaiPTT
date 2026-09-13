package com.saikai.ptt.core.session

import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.domain.AudioPlayer
import com.saikai.ptt.core.domain.PcmConversion
import com.saikai.ptt.core.domain.VoiceCodec
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.SessionId

/**
 * The receive side: arriving frames, in order, decoded, into the speaker.
 *
 * The counterpart of [VoiceTransmitter], and split the same way. [JitterBuffer]
 * decides *which* frame is next and when to stop waiting for one that has not
 * come; this owns the decoder and turns that decision into sound. Keeping the
 * two apart is what lets the ordering rules -- the part with all the edge cases
 * -- be tested without a codec.
 *
 * ### A gap is filled three ways, in this order
 *
 * `docs/ADR/ADR-007` left this decision here, because the jitter buffer is the
 * only thing that knows what it still holds.
 *
 * 1. **The redundant copy.** ADR-004 turns on in-band FEC, which puts a
 *    low-rate copy of each frame inside the *next* packet. If the buffer holds
 *    the follower, the real audio can be recovered. This is the only thing that
 *    makes the FEC bitrate worth paying for -- it costs nothing unless someone
 *    asks for it.
 * 2. **Concealment.** With the follower gone too, the codec extrapolates from
 *    the frames before. Same cost as silence, because the decoder has to
 *    advance over the gap either way.
 * 3. **Silence**, if the codec can do neither.
 *
 * `docs/ADR/ADR-004` section 4 said "insert a silence frame". That was written
 * before the codec was chosen; silence is now the third choice rather than the
 * first, and the wording is corrected in `core.session/README.md`.
 *
 * ### One decoder per session, and it must see every frame
 *
 * A decoder carries state between frames, so it has to be fed the playout
 * sequence exactly once each and in order -- which is precisely what the jitter
 * buffer produces, and precisely why decoding happens here rather than as
 * packets arrive.
 *
 * Threading: frames arrive on the voice receive thread, [open] and [close] come
 * from the session machine while it holds its own lock, and [flush] from the
 * control thread. One lock covers all of it, and nothing suspends inside it --
 * `AudioPlayer.write` never blocks, which is what makes that safe.
 */
class VoiceReceiver(
    private val config: SaikaiConfig,
    private val logger: Logger,
    private val player: AudioPlayer,
    private val codecs: () -> VoiceCodec?,
) {

    private val lock = Any()
    private var session: Open? = null

    /** Frames that arrived for no open session, or the wrong one. */
    var strayFrames: Long = 0L
        private set

    /**
     * How the last transmission went, once it is over.
     *
     * Kept after the session closes on purpose: the numbers are only
     * interesting once there is nothing left to add to them, and by then the
     * session is gone. `docs/01_PRD.md` wants them in the debug log and on a
     * developer page; the log line is written here and the page reads this.
     */
    @Volatile
    var lastReception: ReceptionStats? = null
        private set

    /** True while a transmission is being played out. */
    val isOpen: Boolean get() = synchronized(lock) { session != null }

    /**
     * Prepares to play [sessionId].
     *
     * Called from the session machine, before VOICE_ACCEPT goes out, so that no
     * frame can arrive before there is something to put it in.
     *
     * @return false when no decoder could be created, which the caller treats
     *   the same as a speaker that will not open: refuse the session rather
     *   than accept one that can produce no sound.
     */
    fun open(sessionId: SessionId): Boolean = synchronized(lock) {
        session?.let { close(it) }
        val codec = codecs()
        if (codec == null) {
            logger.e(LogCategory.SESSION) { "no decoder; cannot receive" }
            return false
        }
        session = Open(sessionId, codec)
        true
    }

    /** One arriving VOICE_DATA frame, on the voice receive thread. */
    fun onFrame(
        sessionId: SessionId,
        sequence: Int,
        frame: ByteArray,
        offset: Int,
        length: Int,
    ) = synchronized(lock) {
        val current = session
        if (current == null || current.sessionId != sessionId) {
            strayFrames++
            return
        }
        current.buffer.offer(sequence, frame, offset, length)
    }

    /**
     * The sender said that was the last of it.
     *
     * Plays out what is held before the machine closes the speaker, which is
     * why it is called from the packet listener rather than from the state
     * machine: by the time the machine has finished with VOICE_END the speaker
     * is already being taken away.
     */
    fun flush(sessionId: SessionId, finalDataSequence: Int, frameCount: Int) =
        synchronized(lock) {
            val current = session ?: return
            if (current.sessionId != sessionId) return
            current.buffer.flush(finalDataSequence)
            report(current, frameCount)
        }

    /** The session is over, however it ended. */
    fun close() = synchronized(lock) {
        session?.let { close(it) }
        session = null
    }

    private fun close(current: Open) {
        // A session that ended without a VOICE_END -- interrupted, timed out,
        // the network gone -- still has numbers worth keeping. There is no
        // frame count to compare against, so the loss percentage is left out
        // rather than invented.
        if (lastReception?.sessionId != current.sessionId) report(current, expected = 0)
        current.codec.release()
        if (session === current) session = null
    }

    /** Caller holds the lock. */
    private fun report(current: Open, expected: Int) {
        val buffer = current.buffer
        val stats = ReceptionStats(
            sessionId = current.sessionId,
            expectedFrames = expected,
            played = buffer.played,
            concealed = buffer.concealed,
            droppedLate = buffer.droppedLate,
            droppedOverflow = buffer.droppedOverflow,
            droppedImplausible = buffer.droppedImplausible,
            neverArrived = buffer.neverArrived,
        )
        lastReception = stats
        logger.i(LogCategory.SESSION) { "reception: $stats" }
    }

    private inner class Open(
        val sessionId: SessionId,
        val codec: VoiceCodec,
    ) : JitterBufferSink {

        private val samples = ShortArray(config.audio.frameSizeSamples)
        private val pcm = ByteArray(config.audio.frameSizeBytes)

        val buffer = JitterBuffer(config, logger, this)

        override fun onFrame(frame: ByteArray, offset: Int, length: Int) {
            val produced = codec.decode(frame, offset, length, samples)
            if (produced > 0) {
                play(produced)
            } else {
                // A frame that arrived and would not decode is a gap like any
                // other. Dropping it instead would shorten the audio by 20 ms
                // with nothing to show for it.
                logger.throttled(LogLevel.WARN, LogCategory.AUDIO, "decode-failed") {
                    "the decoder refused a ${length}B frame ($produced)"
                }
                onLostFrame(null, 0, 0)
            }
        }

        override fun onLostFrame(next: ByteArray?, nextOffset: Int, nextLength: Int) {
            var produced = 0
            if (next != null) {
                produced = codec.decodeLost(next, nextOffset, nextLength, samples)
            }
            if (produced <= 0) produced = codec.conceal(samples)
            if (produced <= 0) {
                samples.fill(0)
                produced = samples.size
            }
            play(produced)
        }

        private fun play(samplesProduced: Int) {
            val count = samplesProduced.coerceAtMost(samples.size)
            val bytes = PcmConversion.shortsToBytes(samples, 0, count, pcm, 0)
            player.write(pcm, 0, bytes)
        }
    }
}
