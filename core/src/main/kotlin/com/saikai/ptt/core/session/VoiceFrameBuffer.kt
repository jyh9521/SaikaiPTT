package com.saikai.ptt.core.session

/**
 * The frames captured while the peer has not answered yet.
 *
 * `docs/03_Protocol.md` section 19.4 and `docs/01_PRD.md` section 10.1 both
 * require capture to begin on the button press rather than on the acceptance,
 * so that the first syllable survives a handshake that ADR-003 section 6 allows
 * to take half a second. Something has to hold that audio, and this is it.
 *
 * Bounded, and it drops the *oldest* frame when full. Both halves matter:
 *
 * - Unbounded, it would grow for as long as a peer that will never answer stays
 *   silent -- and the peer that never answers is the ordinary case, not the
 *   exceptional one.
 * - Dropping the newest would be easier and is wrong. If this buffer ever
 *   overflows the handshake has already failed and the session is about to be
 *   abandoned, but if it somehow does not, the audio worth keeping is the most
 *   recent, exactly as in the playback path.
 *
 * Holds encoded frames, not PCM. Encoding as frames arrive keeps the CPU cost
 * spread evenly instead of arriving as a spike at the moment of acceptance --
 * the moment the pipeline is already busiest -- and it makes the buffer twenty
 * times smaller. It is also what the codec requires: `VoiceCodec` carries state
 * between frames, so frames must be encoded in the order they were captured,
 * which is not the order a deferred encoder would see if any were dropped.
 *
 * Every buffer is allocated once, at construction. Nothing here allocates per
 * frame. Not thread-safe: it belongs to whatever holds the transmitter's lock.
 */
class VoiceFrameBuffer(
    val capacityFrames: Int,
    val maxFrameBytes: Int,
) {

    init {
        require(capacityFrames > 0) { "A pre-roll buffer holds at least one frame" }
        require(maxFrameBytes > 0) { "A frame is at least one byte" }
    }

    private val frames: Array<ByteArray> = Array(capacityFrames) { ByteArray(maxFrameBytes) }
    private val lengths: IntArray = IntArray(capacityFrames)

    private var head = 0
    private var count = 0

    /** Frames held. */
    val size: Int get() = count

    /** True when the next [add] will displace the oldest frame. */
    val isFull: Boolean get() = count == capacityFrames

    /** Frames displaced because the buffer was full, since the last [clear]. */
    var overflowed: Int = 0
        private set

    /**
     * Copies one encoded frame in, displacing the oldest if there is no room.
     *
     * @return false when the frame is larger than [maxFrameBytes] and was not
     *   stored. That is a codec producing something the wire cannot carry, which
     *   is worth refusing here rather than discovering at send time.
     */
    fun add(frame: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 1 || length > maxFrameBytes) return false
        if (offset < 0 || offset + length > frame.size) return false

        val slot = (head + count) % capacityFrames
        if (count == capacityFrames) {
            head = (head + 1) % capacityFrames
            overflowed++
        } else {
            count++
        }
        frame.copyInto(frames[slot], 0, offset, offset + length)
        lengths[slot] = length
        return true
    }

    /**
     * Hands every held frame to [action], oldest first, and empties the buffer.
     *
     * Emptying as it goes rather than afterwards: [action] sends a datagram, and
     * a send that throws must not leave frames behind to be sent a second time
     * in whatever order the next flush happens to produce.
     *
     * The array passed to [action] is this buffer's own storage and is valid only
     * for the duration of that call. Called once per session, so the lambda is
     * not on any hot path.
     */
    fun drainTo(action: (frame: ByteArray, length: Int) -> Unit) {
        while (count > 0) {
            val slot = head
            head = (head + 1) % capacityFrames
            count--
            action(frames[slot], lengths[slot])
        }
    }

    /** Discards everything, including the overflow count. */
    fun clear() {
        head = 0
        count = 0
        overflowed = 0
    }
}
