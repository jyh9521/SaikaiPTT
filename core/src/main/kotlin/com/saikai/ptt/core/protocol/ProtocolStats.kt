package com.saikai.ptt.core.protocol

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Counts what the validator accepted and why it dropped the rest.
 *
 * `docs/03_Protocol.md` section 29 requires invalid packets to be counted rather
 * than merely discarded. The count is the only way a field problem is ever
 * diagnosed: nothing about "the other phone does not appear" tells a user
 * whether the packets are arriving at all, arriving addressed to someone else,
 * or arriving from a build that speaks a different version.
 *
 * Free of locks and safe from both receive threads: a counter that made the
 * receive loop wait would be worse than no counter.
 */
class ProtocolStats {

    private val accepted = AtomicLong()
    private val rejections = AtomicLongArray(RejectionReason.entries.size)

    fun recordAccepted() {
        accepted.incrementAndGet()
    }

    fun recordRejected(reason: RejectionReason) {
        rejections.incrementAndGet(reason.ordinal)
    }

    fun accepted(): Long = accepted.get()

    fun rejected(reason: RejectionReason): Long = rejections.get(reason.ordinal)

    /** Every rejection, including the two that are normal traffic rather than faults. */
    fun totalRejected(): Long {
        var total = 0L
        for (reason in RejectionReason.entries) total += rejections.get(reason.ordinal)
        return total
    }

    /** Rejections that indicate something is actually wrong on the network. */
    fun totalInvalid(): Long {
        var total = 0L
        for (reason in RejectionReason.entries) {
            if (reason.countsAsInvalid) total += rejections.get(reason.ordinal)
        }
        return total
    }

    /** A point-in-time copy, for diagnostics. Only the non-zero counts. */
    fun snapshot(): Map<RejectionReason, Long> = buildMap {
        for (reason in RejectionReason.entries) {
            val count = rejections.get(reason.ordinal)
            if (count > 0L) put(reason, count)
        }
    }

    fun reset() {
        accepted.set(0)
        for (reason in RejectionReason.entries) rejections.set(reason.ordinal, 0L)
    }
}
