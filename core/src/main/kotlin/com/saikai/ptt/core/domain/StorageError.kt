package com.saikai.ptt.core.domain

/**
 * Something could not be stored, and the conversation carried on regardless.
 *
 * Every value here is reported *after* the audio it concerns has already been
 * sent or played. `docs/02_Architecture.md` section 18 and
 * `docs/05_DataModel.md` section 48 both say the same thing from different
 * ends: a disk that is full costs the user a recording, never a call.
 *
 * Four values rather than an exception, because the UI says something different
 * for each and the log wants the cause either way (`.claude/CLAUDE.md`
 * section 25).
 */
enum class StorageError {
    /** The recording file could not be opened. Nothing was written. */
    RECORDING_UNAVAILABLE,

    /** Writing failed partway -- a full disk, an I/O error. */
    RECORDING_FAILED,

    /**
     * The audio was written but could not be moved out of the temporary
     * directory, so there is no finished file to point a record at.
     */
    RECORDING_NOT_FINALISED,

    /**
     * The audio is on disk and the database refused the row.
     *
     * The file is deliberately kept: `docs/05_DataModel.md` section 34 calls it
     * an orphan candidate and leaves it for cleanup, because throwing away
     * audio the user recorded is worse than leaving a file nothing points at.
     */
    HISTORY_UNAVAILABLE,
}
