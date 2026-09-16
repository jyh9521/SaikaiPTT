package com.saikai.ptt.storage.history

import com.saikai.ptt.core.audio.RecordingPaths
import com.saikai.ptt.core.history.RecordingFiles
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger
import java.io.File

/**
 * [RecordingFiles] on the app's private files directory.
 *
 * The only place in the app that turns a stored relative path into an absolute
 * one for cleanup's sake (`docs/05_DataModel.md` section 19 keeps the prefix
 * out of the database; `SessionRecorder` and `RecordingPlayback` each add it
 * for their own purposes).
 *
 * Nothing here throws. A file system that refuses is an ordinary condition --
 * a file held open, a directory a vendor ROM has decided to protect -- and
 * section 30 requires a cleanup pass to survive one.
 */
class LocalRecordingFiles(
    private val filesDir: File,
    private val logger: Logger,
) : RecordingFiles {

    private val root: File get() = File(filesDir, RecordingPaths.ROOT)

    override fun list(): List<String> = try {
        root.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { relativePathOf(it) }
            .toList()
    } catch (error: Exception) {
        logger.w(LogCategory.STORAGE, error) { "could not list the recordings directory" }
        emptyList()
    }

    override fun lastModified(path: String): Long? {
        val file = File(filesDir, path)
        // Zero is what File returns both for "not there" and for an error, so
        // it is treated as absent: a file whose age cannot be read must not be
        // deleted for being old.
        val stamp = try {
            file.lastModified()
        } catch (_: SecurityException) {
            0L
        }
        return if (stamp <= 0L) null else stamp
    }

    override fun sizeOf(path: String): Long = try {
        File(filesDir, path).length()
    } catch (_: SecurityException) {
        0L
    }

    override fun delete(path: String): Boolean = try {
        val file = File(filesDir, path)
        // A path that resolves outside the recordings root is a bug somewhere
        // upstream, and the cost of not checking is deleting something else in
        // the app's private storage.
        if (!file.canonicalPath.startsWith(root.canonicalPath)) {
            logger.e(LogCategory.STORAGE) { "refusing to delete outside the recordings directory" }
            false
        } else {
            file.delete()
        }
    } catch (error: Exception) {
        logger.w(LogCategory.STORAGE, error) { "a recording would not delete" }
        false
    }

    /**
     * Removes the dated directories a cleanup pass emptied.
     *
     * Cosmetic in effect and cheap: without it a device accumulates one empty
     * directory per day forever. The recordings root and the temporary
     * directory are never removed -- the recorder expects them to exist.
     */
    override fun pruneEmptyDirectories() {
        val temp = File(filesDir, RecordingPaths.TEMP_DIRECTORY)
        try {
            root.walkBottomUp()
                .filter { it.isDirectory && it != root && it != temp }
                .filter { it.list()?.isEmpty() ?: false }
                .forEach { it.delete() }
        } catch (error: Exception) {
            logger.w(LogCategory.STORAGE, error) { "could not prune empty directories" }
        }
    }

    /** Total bytes of everything under the recordings root (`docs/05_DataModel.md` section 45). */
    fun totalBytes(): Long = try {
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (error: Exception) {
        logger.w(LogCategory.STORAGE, error) { "could not measure the recordings directory" }
        0L
    }

    /** `<filesDir>/records/2026/09/16/x.opus` -> `records/2026/09/16/x.opus`. */
    private fun relativePathOf(file: File): String? {
        val base = filesDir.path.removeSuffix(File.separator) + File.separator
        val path = file.path
        return if (path.startsWith(base)) path.removePrefix(base).replace(File.separatorChar, '/')
        else null
    }
}
