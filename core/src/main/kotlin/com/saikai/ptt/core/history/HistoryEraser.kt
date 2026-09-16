package com.saikai.ptt.core.history

import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.domain.CommunicationRecord
import com.saikai.ptt.core.domain.HistoryError
import com.saikai.ptt.core.domain.HistoryRepository
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger

/**
 * Deleting records because the **user** asked.
 *
 * Separate from [HistoryCleaner], which deletes because time passed. The
 * ordering is the same and for the same reason (`docs/05_DataModel.md`
 * sections 38 and 39: never a row whose audio outlives it), but two things
 * differ and both are about the user being there:
 *
 *  - **favourites are not exempt.** Cleanup must never touch one; a user who
 *    selects a favourite and confirms is saying to delete it. The confirmation
 *    is the screen's job (section 39 asks for a separate one), and by the time
 *    this is called it has happened.
 *  - **a file in use is deleted anyway.** Cleanup leaves it for next time,
 *    which is a fine answer for a background job and a terrible one for a
 *    button: a delete that silently does nothing is worse than one that leaves
 *    a file behind. The row goes, the file is attempted, and a file that will
 *    not go is logged and left for the orphan scan.
 */
class HistoryEraser(
    private val history: HistoryRepository,
    private val files: RecordingFiles,
    private val logger: Logger,
) {

    /** Deletes one record and its recording. */
    suspend fun erase(id: String): Outcome<Unit, HistoryError> {
        val record = when (val found = history.byId(id)) {
            is Outcome.Success -> found.value ?: return Outcome.failure(HistoryError.NotFound(id))
            is Outcome.Failure -> return found
        }
        return eraseAll(listOf(record)).map { }
    }

    /**
     * Deletes a set of records and their recordings.
     *
     * @return how many rows went.
     */
    suspend fun eraseAll(records: Collection<CommunicationRecord>): Outcome<Int, HistoryError> {
        if (records.isEmpty()) return Outcome.success(0)

        // Audio first, for the reason HistoryCleaner's own documentation gives:
        // a row without its file is a screen that already works, and a file
        // without its row is a leak only the orphan scan would ever find.
        var stranded = 0
        for (record in records) {
            val path = record.audioPath ?: continue
            if (!files.delete(path) && files.lastModified(path) != null) {
                stranded++
            }
        }
        if (stranded > 0) {
            // Left for the orphan scan. The row goes regardless: the user asked
            // for this record to disappear, and it must.
            logger.w(LogCategory.STORAGE) { "$stranded recordings would not delete" }
        }

        val deleted = history.deleteAll(records.map { it.id })
        if (deleted is Outcome.Success) files.pruneEmptyDirectories()
        return deleted
    }

    /**
     * Everything, optionally including favourites.
     *
     * `docs/05_DataModel.md` section 39: clearing the history must not quietly
     * take the records the user marked as worth keeping. [includeFavorites] is
     * the second confirmation's answer, not a default.
     */
    suspend fun eraseEverything(includeFavorites: Boolean): Outcome<Int, HistoryError> {
        var total = 0
        while (true) {
            // Read in batches through the same query cleanup uses, so "the
            // whole history" is not a list of every record in memory at once.
            // Far in the future, so nothing is excluded by age.
            val batch = when (val found = history.expiredBefore(FAR_FUTURE)) {
                is Outcome.Success -> found.value
                is Outcome.Failure -> return found
            }
            if (batch.isEmpty()) break
            when (val erased = eraseAll(batch)) {
                is Outcome.Success -> total += erased.value
                is Outcome.Failure -> return erased
            }
        }

        if (includeFavorites) {
            // Favourites are invisible to expiredBefore by design, so they are
            // fetched the only other way there is: the list itself.
            val favorites = when (val counted = history.counts()) {
                is Outcome.Success -> counted.value.favorites
                is Outcome.Failure -> return counted
            }
            if (favorites > 0) {
                when (val erased = eraseFavorites()) {
                    is Outcome.Success -> total += erased.value
                    is Outcome.Failure -> return erased
                }
            }
        }
        return Outcome.success(total)
    }

    /**
     * The favourites, which no cleanup query will return.
     *
     * Read off the list rather than through a query of their own: this runs
     * once, when somebody has confirmed twice that they want their whole
     * history gone, and one more DAO method exists to be got wrong.
     */
    private suspend fun eraseFavorites(): Outcome<Int, HistoryError> {
        var total = 0
        while (true) {
            val batch = when (val paths = history.favorites()) {
                is Outcome.Success -> paths.value
                is Outcome.Failure -> return paths
            }
            if (batch.isEmpty()) break
            when (val erased = eraseAll(batch)) {
                is Outcome.Success -> {
                    if (erased.value == 0) break
                    total += erased.value
                }
                is Outcome.Failure -> return erased
            }
        }
        return Outcome.success(total)
    }

    private companion object {
        /** Later than any timestamp a device will produce, so nothing is filtered out. */
        const val FAR_FUTURE = Long.MAX_VALUE
    }
}
