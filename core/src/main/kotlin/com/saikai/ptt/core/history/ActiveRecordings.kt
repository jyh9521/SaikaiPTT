package com.saikai.ptt.core.history

import java.util.concurrent.ConcurrentHashMap

/**
 * The recordings something is holding open right now.
 *
 * `docs/05_DataModel.md` sections 31 and 32: cleanup must not delete a file
 * that is being written, played, or is part of a call in progress. Two of those
 * three are answered by the path alone -- a recording in progress lives under
 * `records/.tmp/` and no database row points at it -- but a *finished* file
 * being played back is a normal path with a normal row, and the only thing that
 * knows it is in use is whatever opened it.
 *
 * So this is a register: a component that opens a recording claims it and
 * releases it afterwards. Cleanup skips whatever is claimed and will find it
 * again on the next pass, which is the right answer for something that is one
 * button press from being finished with.
 *
 * Claims are counted rather than flagged, so two holders releasing in either
 * order is safe. Thread-safe because the claimants are on different threads:
 * playback on the main thread, the recorder on its own writer thread, cleanup
 * on IO.
 */
class ActiveRecordings {

    private val claims = ConcurrentHashMap<String, Int>()

    /** Marks [path] as in use until the matching [release]. */
    fun claim(path: String) {
        claims.compute(path) { _, count -> (count ?: 0) + 1 }
    }

    /** Gives up one claim on [path]. Releasing something never claimed is a no-op. */
    fun release(path: String) {
        claims.computeIfPresent(path) { _, count -> if (count <= 1) null else count - 1 }
    }

    fun isActive(path: String): Boolean = claims.containsKey(path)

    /** A snapshot, for a cleanup pass that wants to decide against one set of claims. */
    fun snapshot(): Set<String> = claims.keys.toSet()

    /**
     * Claims [path] for the duration of [block].
     *
     * The form callers should reach for: a claim that leaks is a file that is
     * never cleaned up again, and this one cannot leak.
     */
    inline fun <T> holding(path: String, block: () -> T): T {
        claim(path)
        return try {
            block()
        } finally {
            release(path)
        }
    }
}
