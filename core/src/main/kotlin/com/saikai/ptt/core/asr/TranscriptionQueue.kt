package com.saikai.ptt.core.asr

import com.saikai.ptt.core.domain.TranscriptStatus
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * What is waiting to be recognised, in what order, and how many times each has
 * been tried.
 *
 * Serial by construction: [next] hands out one item and will not hand out
 * another until that one is reported back. Recognition on the reference device
 * is expected to cost real CPU (ADR-012 sets the threshold at one second of
 * work per second of audio), and `CLAUDE.md` section 18.3 puts audio above it
 * unconditionally -- two recognisers competing for a 4 GB phone is the opposite
 * of that.
 *
 * ### Attempts are counted in memory, on purpose
 *
 * `HistoryConfig.asrMaxRetries` bounds how many times a recording is retried.
 * That count lives here and nowhere else, because the alternative is a column
 * on `communication_record`, and Task45 is explicit that it introduces no
 * schema change -- a migration to add a counter, and another to remove it when
 * the retry policy changes, is a high price for a number that only matters
 * while the app is running.
 *
 * The consequence is worth stating plainly rather than discovering later:
 * **a recording that fails twice and then meets a process death comes back with
 * a fresh allowance.** Its stored status is still PENDING, so it is queued
 * again and gets [maxAttempts] more tries. That is the right behaviour for the
 * common reason recognition fails on a phone -- the app was killed for memory
 * while the model was loaded -- and the wrong behaviour only for a file that is
 * permanently undecodable, which costs a few seconds of CPU per app start until
 * some attempt finally writes FAILED.
 *
 * ### Bounded
 *
 * [capacity] is a limit on how far behind recognition may fall, not on how much
 * is ever recognised: a full queue refuses new work and the next sweep of
 * PENDING records picks up whatever was refused. Unbounded would mean a device
 * that talked all day holding every id in memory.
 *
 * Thread-safe. Records are enqueued from the service's scope as calls end, and
 * drained by the worker.
 */
class TranscriptionQueue(
    private val maxAttempts: Int,
    private val capacity: Int = DEFAULT_CAPACITY,
) {

    init {
        require(maxAttempts >= 0) { "maxAttempts cannot be negative" }
        require(capacity > 0) { "capacity must be positive" }
    }

    private val lock = ReentrantLock()

    /** Waiting, in arrival order. */
    private val waiting = ArrayDeque<String>()

    /** Record id to attempts already made. Survives a record leaving the deque. */
    private val attempts = HashMap<String, Int>()

    /** The one item [next] has handed out and is waiting to hear about. */
    private var inFlight: String? = null

    val size: Int get() = lock.withLock { waiting.size + if (inFlight == null) 0 else 1 }

    val busy: Boolean get() = lock.withLock { inFlight != null }

    /**
     * Adds a record, unless it is already queued, already running, or the queue
     * is full.
     *
     * @return true when it was taken.
     */
    fun enqueue(recordId: String): Boolean = lock.withLock {
        if (recordId == inFlight || recordId in waiting) return false
        if (waiting.size >= capacity) return false
        waiting.addLast(recordId)
        true
    }

    /**
     * Takes the next record, or null when there is nothing to do or something
     * is already running.
     *
     * Marks the attempt as started, so [attemptsFor] counts it even if the
     * worker dies before reporting.
     */
    fun next(): QueuedRecording? = lock.withLock {
        if (inFlight != null) return null
        val id = waiting.removeFirstOrNull() ?: return null
        val attempt = (attempts[id] ?: 0) + 1
        attempts[id] = attempt
        inFlight = id
        QueuedRecording(id, attempt, attempt >= maxAttempts)
    }

    /** Recognition produced text. The record is done either way it is called. */
    fun succeeded(recordId: String) = lock.withLock {
        if (inFlight == recordId) inFlight = null
        attempts.remove(recordId)
        waiting.remove(recordId)
        Unit
    }

    /**
     * Recognition failed.
     *
     * @return what the record's stored status should become.
     *   [TranscriptStatus.PENDING] when there are attempts left and the record
     *   has been put back at the **end** of the queue -- behind anything newer,
     *   because a recording somebody just made is worth more than one that has
     *   already failed. [TranscriptStatus.FAILED] when the allowance is spent.
     */
    fun failed(recordId: String): TranscriptStatus = lock.withLock {
        if (inFlight == recordId) inFlight = null
        val made = attempts[recordId] ?: 0
        if (made >= maxAttempts) {
            attempts.remove(recordId)
            TranscriptStatus.FAILED
        } else {
            if (recordId !in waiting) waiting.addLast(recordId)
            TranscriptStatus.PENDING
        }
    }

    /**
     * Gives an item back without counting it as an attempt.
     *
     * For [RecognitionError.DEFERRED_TO_VOICE]: a call started, so the work
     * never ran. Charging an attempt for that would let a busy hour exhaust a
     * recording's retries without anything having been tried.
     * Goes back to the **front**, because nothing about it has changed.
     */
    fun defer(recordId: String) = lock.withLock {
        if (inFlight == recordId) inFlight = null
        attempts[recordId] = ((attempts[recordId] ?: 1) - 1).coerceAtLeast(0)
        if (recordId !in waiting) waiting.addFirst(recordId)
        Unit
    }

    fun attemptsFor(recordId: String): Int = lock.withLock { attempts[recordId] ?: 0 }

    /** Drops everything. For the user turning ASR off. */
    fun clear() = lock.withLock {
        waiting.clear()
        attempts.clear()
        inFlight = null
    }

    companion object {
        /**
         * How far recognition may fall behind.
         *
         * Two hundred is more conversations than a shift produces, and a full
         * queue loses nothing: the refused records keep their PENDING status
         * and are found again by the next sweep.
         */
        const val DEFAULT_CAPACITY: Int = 200
    }
}

/** One item handed out by [TranscriptionQueue.next]. */
class QueuedRecording(
    val recordId: String,
    /** 1 for the first try. */
    val attempt: Int,
    /** True when a failure now means [TranscriptStatus.FAILED] rather than a retry. */
    val lastAttempt: Boolean,
)
