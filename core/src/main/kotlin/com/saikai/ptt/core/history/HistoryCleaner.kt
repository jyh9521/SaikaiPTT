package com.saikai.ptt.core.history

import com.saikai.ptt.core.audio.RecordingPaths
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.domain.HistoryRepository
import com.saikai.ptt.core.domain.HistoryRetention
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger

/**
 * Removes what the retention setting says is no longer kept, and the audio with
 * it.
 *
 * `docs/05_DataModel.md` sections 30 to 32, in that order and for the reasons
 * given there:
 *
 * ```text
 * query expired records -> delete audio -> delete database rows -> scan for orphans
 * ```
 *
 * ### Why audio goes first
 *
 * The two stores cannot be changed in one transaction, so the order decides
 * which way a half-finished pass fails. Deleting the row first and then dying
 * leaves a file nothing points at, and nothing will ever point at it again --
 * the orphan scan is the only thing that would find it, and it only runs after
 * a successful pass. Deleting the file first and then dying leaves a row whose
 * audio is missing, which the detail screen already handles: it shows
 * everything else and disables the play button with a reason (Task39). One of
 * those is a leak and the other is a screen that already works.
 *
 * ### Nothing here stops for a single failure
 *
 * Section 30 is explicit. A file that will not delete costs a log line, and the
 * pass carries on; its row is kept so the next pass tries the file again rather
 * than orphaning it. A pass that cannot read the database at all stops, because
 * there is nothing to go on.
 *
 * ### Favourites
 *
 * Never cleaned up automatically, at any retention setting (section 25). That
 * is enforced in the query ([HistoryRepository.expiredBefore]) rather than
 * here, so that no caller can skip it, and their audio is protected here as
 * well: a favourite's file is referenced, so the orphan scan passes it by.
 */
class HistoryCleaner(
    private val history: HistoryRepository,
    private val files: RecordingFiles,
    private val active: ActiveRecordings,
    private val logger: Logger,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * One cleanup pass.
     *
     * @param retention what the user chose. [HistoryRetention.FOREVER] still
     *   runs: nothing expires, but the orphan scan does, and a file left behind
     *   by a crash during a call is exactly as orphaned either way.
     */
    suspend fun run(retention: HistoryRetention): CleanupReport {
        val expired = if (retention.duration == null) {
            ExpiredSweep(recordsDeleted = 0, audioDeleted = 0, audioFailed = 0, readable = true)
        } else {
            sweepExpired(now() - retention.duration.inWholeMilliseconds)
        }

        val orphans = if (expired.readable) sweepOrphans() else OrphanSweep(0, 0, 0)

        val report = CleanupReport(
            recordsDeleted = expired.recordsDeleted,
            audioDeleted = expired.audioDeleted + orphans.deleted,
            audioFailed = expired.audioFailed + orphans.failed,
            orphansFound = orphans.found,
        )
        if (report.didSomething) {
            files.pruneEmptyDirectories()
            logger.i(LogCategory.STORAGE) { "cleanup: $report" }
        }
        return report
    }

    /**
     * Expired records, in batches, until a batch comes back short.
     *
     * Batched so a device that has been switched off for a month does not read
     * a month of rows into memory to catch up.
     */
    private suspend fun sweepExpired(cutoffMillis: Long): ExpiredSweep {
        var recordsDeleted = 0
        var audioDeleted = 0
        var audioFailed = 0

        while (true) {
            val batch = when (val outcome = history.expiredBefore(cutoffMillis)) {
                is Outcome.Success -> outcome.value
                is Outcome.Failure -> {
                    // Nothing to go on. The next pass tries again; meanwhile the
                    // orphan scan is skipped too, because a database that will
                    // not answer cannot say which files are referenced and
                    // everything on disk would look like an orphan.
                    logger.w(LogCategory.STORAGE) { "cleanup: the history could not be read" }
                    return ExpiredSweep(recordsDeleted, audioDeleted, audioFailed, readable = false)
                }
            }
            if (batch.isEmpty()) break

            val removable = mutableListOf<String>()
            for (record in batch) {
                val path = record.audioPath
                when {
                    path == null -> removable += record.id

                    // Somebody has it open. Leaving the row means the next pass
                    // sees it again -- and a file the user is listening to is
                    // one button press away from being free.
                    active.isActive(path) ->
                        logger.i(LogCategory.STORAGE) { "cleanup: skipping a recording in use" }

                    files.delete(path) -> {
                        audioDeleted++
                        removable += record.id
                    }

                    // The file is genuinely gone: the row may go too.
                    files.lastModified(path) == null -> removable += record.id

                    else -> {
                        // It is there and it will not delete. Keep the row, or
                        // the file becomes an orphan the scan would then delete
                        // on a later pass anyway -- with the record it belonged
                        // to already gone.
                        audioFailed++
                        logger.w(LogCategory.STORAGE) { "cleanup: a recording would not delete" }
                    }
                }
            }

            if (removable.isNotEmpty()) {
                when (val outcome = history.deleteAll(removable)) {
                    is Outcome.Success -> recordsDeleted += outcome.value
                    is Outcome.Failure -> {
                        logger.w(LogCategory.STORAGE) { "cleanup: rows could not be deleted" }
                        return ExpiredSweep(
                            recordsDeleted, audioDeleted, audioFailed, readable = false,
                        )
                    }
                }
            }

            // A short batch is the last batch. A batch that was entirely skipped
            // would otherwise loop forever, so this ends on size, not on
            // progress.
            if (batch.size < HistoryRepository.CLEANUP_BATCH) break
            if (removable.isEmpty()) break
        }

        return ExpiredSweep(recordsDeleted, audioDeleted, audioFailed, readable = true)
    }

    /**
     * Files on disk that no row points at.
     *
     * Section 31 warns against the naive version of this, and the three cases
     * it names are all handled by age rather than by reference:
     *
     *  - **being written**: under `records/.tmp/`, claimed, or younger than
     *    [ORPHAN_GRACE_MILLIS];
     *  - **being played**: claimed in [ActiveRecordings];
     *  - **a call in progress**: its file is a temporary one, and claimed.
     *
     * The grace period is what covers the gap the other two cannot: between the
     * writer finishing a file and the row being inserted, the file is finished,
     * unclaimed and unreferenced -- for a few milliseconds. Anything younger
     * than an hour is left for the next pass.
     */
    private suspend fun sweepOrphans(): OrphanSweep {
        val referenced = when (val outcome = history.audioPaths()) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> {
                logger.w(LogCategory.STORAGE) { "cleanup: skipping the orphan scan" }
                return OrphanSweep(0, 0, 0)
            }
        }

        val claimed = active.snapshot()
        val cutoff = now() - ORPHAN_GRACE_MILLIS
        var found = 0
        var deleted = 0
        var failed = 0

        for (path in files.list()) {
            if (path in referenced || path in claimed) continue
            val modified = files.lastModified(path) ?: continue
            if (modified > cutoff) continue

            found++
            if (files.delete(path)) {
                deleted++
                logger.i(LogCategory.STORAGE) {
                    val kind = if (RecordingPaths.isTemporary(path)) "abandoned" else "orphaned"
                    "cleanup: removed an $kind recording"
                }
            } else {
                failed++
            }
        }
        return OrphanSweep(found, deleted, failed)
    }

    private data class ExpiredSweep(
        val recordsDeleted: Int,
        val audioDeleted: Int,
        val audioFailed: Int,
        /** False when the database stopped answering, which also skips the orphan scan. */
        val readable: Boolean,
    )

    private data class OrphanSweep(val found: Int, val deleted: Int, val failed: Int)

    companion object {
        /**
         * How old an unreferenced file must be before it counts as an orphan.
         *
         * One hour, and generous on purpose. The window this closes is
         * milliseconds wide -- a file finished and moved, its row not yet
         * inserted -- and the cost of being wrong is deleting a recording the
         * user just made. The cost of waiting is an hour of disk for a file
         * that was going to be deleted anyway.
         */
        const val ORPHAN_GRACE_MILLIS: Long = 60L * 60L * 1000L
    }
}

/** What one pass did, for the log and for the settings screen. */
data class CleanupReport(
    val recordsDeleted: Int = 0,
    val audioDeleted: Int = 0,
    val audioFailed: Int = 0,
    val orphansFound: Int = 0,
) {
    val didSomething: Boolean
        get() = recordsDeleted > 0 || audioDeleted > 0 || audioFailed > 0 || orphansFound > 0

    override fun toString(): String =
        "$recordsDeleted records, $audioDeleted files" +
            (if (orphansFound > 0) ", $orphansFound orphaned" else "") +
            (if (audioFailed > 0) ", $audioFailed would not delete" else "")
}
