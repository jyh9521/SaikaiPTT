package com.saikai.ptt.core.protocol

/**
 * The rules for the u32 sequence number at header offset 60.
 *
 * `docs/ADR/ADR-003-Wire-Format.md` section 5 fixes what each packet type puts
 * there, which the earlier protocol draft left to "the implementation", and
 * fixes how two of them are compared.
 *
 * Kotlin has no unsigned type in its stable public API surface here, so a
 * sequence number is carried as an `Int` holding the raw 32 bits. Values above
 * 2^31-1 are negative as an `Int`; that is correct and expected, and it is
 * exactly why [isNewer] exists.
 */
object SequenceNumbers {

    /** What DISCOVERY, HEARTBEAT, PING, PONG, BUSY and SESSION_TERMINATE carry. */
    const val CONTROL: Int = 0

    /** VOICE_START and VOICE_ACCEPT carry zero: they precede the frame stream. */
    const val VOICE_START: Int = 0

    /** The first VOICE_DATA frame of a session. Frames count from one, not zero. */
    const val FIRST_VOICE_DATA: Int = 1

    private const val MASK: Long = 0xFFFFFFFFL
    private const val HALF: Long = 0x80000000L

    /**
     * True when [a] is newer than [b], across the wraparound at 2^32.
     *
     * RFC 1982 serial number arithmetic. A session would have to run for
     * 2^32 / 50 packets per second, about 2.7 years, to wrap in practice -- but
     * the comparison is also applied to sequence numbers that arrive out of
     * order or forged, so `a > b` is wrong at any duration and is forbidden by
     * the ADR.
     *
     * Equal numbers are not newer. Exactly-opposite numbers (a difference of
     * 2^31) have no defined order and are reported as not newer.
     */
    fun isNewer(a: Int, b: Int): Boolean {
        val difference = (a.toLong() - b.toLong()) and MASK
        return difference in 1L until HALF
    }

    /**
     * How far [a] is ahead of [b], as a signed count of packets.
     *
     * Negative when [a] is older, zero when they are the same. Correct across
     * the wrap at 2^32, because Int subtraction wraps exactly as the counter
     * does for any true distance below 2^31 -- and every distance this protocol
     * can produce is far below it, since a session is capped at five minutes.
     *
     * It exists so that the arithmetic reads as what it is. `a - b` on two
     * sequence numbers looks like the bug [isNewer] was written to prevent, and
     * the alternative -- spelling it out with a comment at every site -- is how
     * one of those sites ends up without the comment.
     */
    fun distance(a: Int, b: Int): Int = a - b

    /** The next sequence number. Wraps naturally at 2^32. */
    fun next(sequence: Int): Int = sequence + 1

    /** What VOICE_END carries: one past the last frame actually sent. */
    fun voiceEnd(lastDataSequence: Int): Int = lastDataSequence + 1

    /** The unsigned value, for logging and arithmetic that must not go negative. */
    fun toUnsignedLong(sequence: Int): Long = sequence.toLong() and MASK
}
