package com.saikai.ptt.core.history

/**
 * The recordings directory, as cleanup needs to see it.
 *
 * An interface rather than `java.io.File` calls inside [HistoryCleaner] for one
 * reason: the ordering rules in `docs/05_DataModel.md` sections 30 to 32 are
 * the part that can be got wrong, and they are only testable if the file system
 * can be stood in for. The implementation is a dozen lines
 * (`app/storage/history/LocalRecordingFiles.kt`).
 *
 * Every path here is **relative** to the app's private files directory, exactly
 * as the database stores it (section 19). The prefix belongs to the
 * implementation and appears nowhere else.
 */
interface RecordingFiles {

    /**
     * Every recording currently on disk, as relative paths.
     *
     * Includes the temporary directory. Cleanup needs to see those to find the
     * ones a dead process left behind, and decides what to do with them by
     * their age, not by their absence from the database -- a file being written
     * right now is in no database either.
     */
    fun list(): List<String>

    /**
     * When the file was last written, in epoch milliseconds, or null when it is
     * not there any more.
     */
    fun lastModified(path: String): Long?

    /** Bytes, or 0 when the file is gone. */
    fun sizeOf(path: String): Long

    /**
     * Removes one recording.
     *
     * @return false when the file could not be removed. Never throws:
     *   `docs/05_DataModel.md` section 30 requires one failed file to cost that
     *   file and not the rest of the pass.
     */
    fun delete(path: String): Boolean

    /** Removes directories under the recordings root that no longer hold anything. */
    fun pruneEmptyDirectories()
}
