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

    /** Frames held, waiting either for their turn or for a gap ahead of them. */
    val depth: Int get() = held

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

        val distance = sequence - nextToPlay
        if (distance < 0) {
            // Its turn has passed. Playing it now would be audio out of order,
            // which is worse than the gap that was already filled for it.
            droppedLate++
            return
        }

        // Far enough ahead that it cannot be stored without exceeding the
        // maximum depth. Give up the oldest audio rather than the newest.
        while (sequence - nextToPlay >= capacity) {
            discardOldest()
        }

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
        val last = if (SequenceNumbers.isNewer(finalDataSequence, highest)) {
            highest
        } else {
            finalDataSequence
        }

        while (last - nextToPlay >= 0) {
            val slot = slotOf(nextToPlay)
            if (occupied[slot] && sequences[slot] == nextToPlay) play(slot) else fillGap()
        }

        val missing = finalDataSequence - highest
        logger.i(LogCategory.SESSION) {
            "playout done: $played played, $concealed concealed, $droppedLate late, " +
                "$droppedOverflow dropped" +
                if (missing > 0) ", $missing never arrived" else ""
        }
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
                highest - nextToPlay >= config.jitterBuffer.targetFrames -> fillGap()

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

    private fun discardOldest() {
        val slot = slotOf(nextToPlay)
        if (occupied[slot] && sequences[slot] == nextToPlay) {
            occupied[slot] = false
            held--
        }
        nextToPlay++
        droppedOverflow++
        logger.throttled(LogLevel.WARN, LogCategory.SESSION, "jitter-overflow") {
            "the jitter buffer is full; dropping the oldest frame to keep latency bounded"
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
