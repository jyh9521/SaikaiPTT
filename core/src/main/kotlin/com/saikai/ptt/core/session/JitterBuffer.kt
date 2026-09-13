package com.saikai.ptt.core.session

import com.saikai.ptt.core.config.SaikaiConfig
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.protocol.SequenceNumbers
import com.saikai.ptt.core.protocol.WireFormat

/**
 * Where a frame goes once the buffer has decided it is next.
 *
 * Two events rather than one with a flag, because the two are answered
 * differently: a frame that arrived is decoded, and one that did not has to be
 * reconstructed. Keeping the distinction in the interface is what lets the
 * buffer stay free of any knowledge of a codec.
 */
interface JitterBufferSink {

    /**
     * Play this frame.
     *
     * The array is the buffer's own storage and is valid only for the duration
     * of the call.
     */
    fun onFrame(frame: ByteArray, offset: Int, length: Int)

    /**
     * The next frame never arrived.
     *
     * [next] is the frame that follows the missing one, when the buffer still
     * holds it, so the caller can ask the codec for the redundant copy that
     * ADR-004's in-band FEC put there -- the copy travels in the *following*
     * packet, which is why it is passed here rather than looked up later. Null
     * when even that is gone, and the caller has only concealment left.
     */
    fun onLostFrame(next: ByteArray?, nextOffset: Int, nextLength: Int)
}

/**
 * Puts arriving voice frames back in order, and decides when to stop waiting.
 *
 * UDP delivers frames late, out of order, twice, or not at all, and a speaker
 * needs one frame every 20 ms in the right order. This is the thing in between.
 * `docs/ADR/ADR-004` and `JitterBufferConfig` fix the three numbers: start
 * playing at 3 frames, wait up to 3 frames for a straggler, never hold more
 * than 10.
 *
 * ### No timer
 *
 * The obvious design has a 20 ms ticker pulling frames out. This has none, and
 * the reason is that there is already a clock: `AudioTrack` consumes what it is
 * given at exactly real time, so writing on arrival paces itself. What is left
 * for the buffer is *ordering*, and ordering can be decided entirely by
 * arrivals -- "how long have I waited for the missing frame" is measured in
 * frames of newer audio received, and at fifty frames a second each one of
 * those is 20 ms. So `targetFrames = 3` is a 60 ms wait, stated in the units
 * the buffer can actually observe, and there is no second thread to keep in
 * step with the first.
 *
 * ### The three numbers, and what each is for
 *
 * - **Start threshold.** Nothing is played until three frames are held, so the
 *   first reordering does not have to be resolved in zero time. This is the
 *   only latency the buffer adds, it is paid once per transmission, and 60 ms
 *   is inside `docs/01_PRD.md`'s end-to-end budget.
 * - **Target depth.** Once playing, a missing frame is waited out until three
 *   newer ones have arrived, then declared lost and filled. Waiting longer
 *   would recover more stragglers at the cost of delaying everything behind
 *   them, permanently.
 * - **Maximum.** Beyond ten frames the buffer is holding 200 ms of audio the
 *   listener has not heard yet, which on a walkie-talkie feels like a fault
 *   even when every frame eventually arrives. The oldest are dropped, because
 *   dropping the newest would keep the latency and lose the audio as well.
 *
 * ### Nothing here trusts the sequence number
 *
 * It arrives from a device on a LAN this app does not control, inside a session
 * that has only been checked for identity, so every arithmetic decision here is
 * made with [SequenceNumbers.distance] rather than `>` and is bounded by
 * something that does not depend on the value. A sequence far enough ahead to
 * be impossible is refused outright: no transmission can outlast the session's
 * own five-minute cap, so anything beyond that many frames is not a jump, it is
 * a peer talking nonsense. What is left is resynchronised in one pass over the
 * ring rather than one step per skipped frame, so the cost of a jump is fixed
 * whatever its size. Both matter for the same reason: this runs on the voice
 * receive thread, and a loop that walks two billion times there does not crash,
 * it stops the device receiving anything at all.
 *
 * Not thread-safe. It belongs to whatever holds the receiver's lock.
 */
class JitterBuffer(
    private val config: SaikaiConfig,
    private val logger: Logger,
    private val sink: JitterBufferSink,
) {

    private val capacity = config.jitterBuffer.maxFrames

    private val frames: Array<ByteArray> =
        Array(capacity) { ByteArray(WireFormat.MAX_VOICE_PAYLOAD_BYTES) }
    private val lengths = IntArray(capacity)
    private val sequences = IntArray(capacity)
    private val occupied = BooleanArray(capacity)

    /** The frame the speaker wants next. Frames are numbered from one. */
    private var nextToPlay: Int = SequenceNumbers.FIRST_VOICE_DATA

    /** The newest sequence seen. One before [nextToPlay] means nothing yet. */
    private var highest: Int = SequenceNumbers.FIRST_VOICE_DATA - 1

    private var held: Int = 0

    /** True until enough frames are held to start. */
    var isPriming: Boolean = true
        private set

    var played: Int = 0
        private set

    /** Frames that never arrived and were filled in. */
    var concealed: Int = 0
        private set

    /** Frames that arrived after their turn had passed, or twice. */
    var droppedLate: Int = 0
        private set

    /** Frames discarded unheard to keep the buffer inside its maximum depth. */
    var droppedOverflow: Int = 0
        private set

    /** Frames whose sequence number could not belong to this session at all. */
    var droppedImplausible: Int = 0
        private set

    /**
     * The furthest ahead a frame can be and still be believable.
     *
     * One session's worth of frames. Beyond it the number is not a gap in a
     * stream, it is a peer sending something that cannot be true, and acting on
     * it would mean throwing away a buffer full of real audio on its say-so.
     */
    private val horizonFrames: Int = (
        config.session.maxDuration.inWholeMilliseconds /
            config.audio.frameDuration.inWholeMilliseconds
        ).toInt().coerceAtLeast(capacity)

    /** Frames held, waiting either for their turn or for a gap ahead of them. */
    val depth: Int get() = held

    /** Set by [flush]: frames the sender counted that never reached this device. */
    var neverArrived: Int = 0
        private set

    /**
     * Takes one arriving frame and plays out whatever that makes possible.
     *
     * Called on the voice receive thread, fifty times a second, and allocates
     * nothing.
     */
    fun offer(sequence: Int, frame: ByteArray, offset: Int, length: Int) {
        if (length < 1 || length > WireFormat.MAX_VOICE_PAYLOAD_BYTES) {
            droppedLate++
            return
        }

        val distance = SequenceNumbers.distance(sequence, nextToPlay)
        if (distance < 0) {
            // Its turn has passed. Playing it now would be audio out of order,
            // which is worse than the gap that was already filled for it.
            droppedLate++
            return
        }
        if (distance >= horizonFrames) {
            droppedImplausible++
            logger.throttled(LogLevel.WARN, LogCategory.SESSION, "jitter-implausible") {
                "a peer sent sequence $sequence while $nextToPlay was expected; ignored"
            }
            return
        }

        // Far enough ahead that it cannot be stored without exceeding the
        // maximum depth. Give up the oldest audio rather than the newest.
        val overshoot = distance - (capacity - 1)
        if (overshoot > 0) discardOldest(overshoot)

        val slot = slotOf(sequence)
        if (occupied[slot] && sequences[slot] == sequence) {
            droppedLate++
            return
        }

        frame.copyInto(frames[slot], 0, offset, offset + length)
        lengths[slot] = length
        sequences[slot] = sequence
        occupied[slot] = true
        held++
        if (SequenceNumbers.isNewer(sequence, highest)) highest = sequence

        drain()
    }

    /**
     * The speaker said that was the last of it.
     *
     * Plays out what is held, filling any gap inside it, and stops at the last
     * frame that actually arrived. [finalDataSequence] comes from the VOICE_END
     * payload and says what the sender believes it sent; anything past the
     * newest frame received is not late, it is gone, and manufacturing a second
     * of concealment for it would be worse than silence.
     */
    fun flush(finalDataSequence: Int) {
        isPriming = false

        // Out to the newest frame that actually arrived, and no further. What
        // the sender says it sent is a count, not an instruction: everything
        // past [highest] is gone rather than late, and manufacturing a second
        // of concealment for it would be worse than the silence it replaces.
        // Taking the sender's number as the stopping point would also let a
        // peer suppress the tail by understating it.
        while (SequenceNumbers.distance(highest, nextToPlay) >= 0) {
            val slot = slotOf(nextToPlay)
            if (occupied[slot] && sequences[slot] == nextToPlay) play(slot) else fillGap()
        }

        neverArrived = SequenceNumbers.distance(finalDataSequence, highest).coerceAtLeast(0)
    }

    // --- Internals -------------------------------------------------------------------

    private fun drain() {
        if (isPriming) {
            if (held < config.jitterBuffer.startThresholdFrames) return
            isPriming = false
        }

        while (true) {
            val slot = slotOf(nextToPlay)
            when {
                occupied[slot] && sequences[slot] == nextToPlay -> play(slot)

                // Three newer frames have arrived while this one has not. At
                // fifty frames a second that is the 60 ms the config allows a
                // straggler; everything behind it has waited long enough.
                SequenceNumbers.distance(highest, nextToPlay) >=
                    config.jitterBuffer.targetFrames -> fillGap()

                else -> return
            }
        }
    }

    private fun play(slot: Int) {
        sink.onFrame(frames[slot], 0, lengths[slot])
        occupied[slot] = false
        held--
        nextToPlay++
        played++
    }

    private fun fillGap() {
        // The redundant copy of a lost frame rides in the packet after it, so
        // the follower is handed over when it is held. It usually is: this is
        // reached because newer frames have arrived.
        val followerSlot = slotOf(nextToPlay + 1)
        val hasFollower = occupied[followerSlot] && sequences[followerSlot] == nextToPlay + 1
        if (hasFollower) {
            sink.onLostFrame(frames[followerSlot], 0, lengths[followerSlot])
        } else {
            sink.onLostFrame(null, 0, 0)
        }
        nextToPlay++
        concealed++
    }

    /**
     * Gives up the [count] oldest frames, heard or not, in one pass.
     *
     * One pass rather than one step per frame: [count] is bounded only by the
     * horizon, and this runs on the voice receive thread. Past a ring's worth
     * there is nothing left to examine anyway -- every slot is stale -- so the
     * work is capped at the ring size however large the jump.
     */
    private fun discardOldest(count: Int) {
        droppedOverflow += count
        if (count >= capacity) {
            occupied.fill(false)
            held = 0
        } else {
            for (step in 0 until count) {
                val sequence = nextToPlay + step
                val slot = slotOf(sequence)
                if (occupied[slot] && sequences[slot] == sequence) {
                    occupied[slot] = false
                    held--
                }
            }
        }
        nextToPlay += count
        logger.throttled(LogLevel.WARN, LogCategory.SESSION, "jitter-overflow") {
            "the jitter buffer overflowed; dropped $count frames to keep latency bounded"
        }
    }

    /**
     * Sequences within one buffer's worth map to distinct slots, which is what
     * makes an out-of-order insert an array write rather than a search.
     */
    private fun slotOf(sequence: Int): Int {
        val index = sequence % capacity
        return if (index < 0) index + capacity else index
    }
}
