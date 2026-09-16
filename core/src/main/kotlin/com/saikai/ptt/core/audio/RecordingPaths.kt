package com.saikai.ptt.core.audio

import com.saikai.ptt.core.domain.AudioFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Where a recording lives, as a path relative to the app's private files
 * directory.
 *
 * Relative, always. `docs/05_DataModel.md` section 19 is explicit that the
 * database must not store an absolute path: the private directory is a location
 * Android is free to move, and a stored absolute path survives exactly until it
 * does. The prefix is added by whoever opens the file and by nobody else.
 *
 * Pure string and date arithmetic, so the layout `docs/05_DataModel.md`
 * section 20 specifies is provable without a filesystem.
 *
 * ```text
 * records/
 *   .tmp/                       being written right now
 *   2026/09/16/<recordId>.opus  finished
 * ```
 */
object RecordingPaths {

    const val ROOT: String = "records"

    /**
     * Where a recording is written while it is still happening.
     *
     * Separate from the dated directories so that cleanup can tell the two
     * apart by path alone (`docs/05_DataModel.md` section 32: a file being
     * recorded must not be deleted). A directory whose name starts with a dot
     * also keeps it out of the way of anything that lists the finished ones.
     */
    const val TEMP_DIRECTORY: String = "$ROOT/.tmp"

    private val DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd")

    /** `records/.tmp/<recordId>.opus` */
    fun temporary(recordId: String, format: AudioFormat): String =
        "$TEMP_DIRECTORY/$recordId.${format.extension}"

    /**
     * `records/2026/09/16/<recordId>.opus`
     *
     * Dated in the device's own time zone, because the directories exist to be
     * readable by a person looking at the files -- the record's `timestamp`
     * stays UTC and is what anything sorts or filters by
     * (`docs/05_DataModel.md` section 17).
     *
     * The record id is the file name. A user name would be the obvious
     * alternative and is forbidden: names are editable, and they can contain
     * characters no file system accepts -- including in the five languages this
     * app ships (section 20).
     */
    fun finished(
        recordId: String,
        format: AudioFormat,
        timestampMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val date = Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()
        return "$ROOT/${DATE_PATH.format(date)}/$recordId.${format.extension}"
    }

    /** The directory part of [finished], for creating it before writing. */
    fun directoryOf(path: String): String = path.substringBeforeLast('/', "")

    /**
     * Whether this path is a recording in progress.
     *
     * Cleanup asks this. A file under the temporary directory is either being
     * written now or was abandoned by a process that died mid-call; either way
     * it is not a finished recording and no database row points at it
     * (section 32).
     */
    fun isTemporary(path: String): Boolean = path.startsWith("$TEMP_DIRECTORY/")
}
