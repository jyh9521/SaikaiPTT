package com.saikai.ptt.ui.history

import androidx.annotation.StringRes
import com.saikai.ptt.R
import com.saikai.ptt.audio.PlaybackError
import com.saikai.ptt.core.domain.Direction
import com.saikai.ptt.core.domain.RecordStatus
import com.saikai.ptt.core.domain.TranscriptStatus

/**
 * What the history screens show.
 *
 * The list holds only what a row draws; the detail holds the record itself,
 * because a detail screen shows everything and has no recomposition budget to
 * protect.
 */

/** One line in the list (`docs/04_UI_UX.md` section 27). */
data class HistoryRow(
    val id: String,
    val remoteUserName: String,
    val direction: Direction,
    val timestamp: Long,
    val durationMs: Long,
    /** First line of the transcript, or null when there is none yet. */
    val transcriptPreview: String?,
    val isRead: Boolean,
    val isFavorite: Boolean,
    val status: RecordStatus,
    /** False when the row points at no file, so the list can say so too. */
    val playable: Boolean,
)

/** The detail screen, once the record has been read back. */
data class HistoryDetail(
    val id: String,
    val remoteUserName: String,
    val direction: Direction,
    val timestamp: Long,
    val durationMs: Long,
    val audioPath: String?,
    val transcript: String?,
    val transcriptStatus: TranscriptStatus,
    val isFavorite: Boolean,
    val status: RecordStatus,
    /**
     * Whether the file is actually on disk.
     *
     * Checked when the screen opens rather than trusted from the row: a record
     * can outlive its recording -- cleanup, a restore, a user with a file
     * manager -- and Task39 requires that the screen still show everything else
     * and simply disable the button (`docs/05_DataModel.md` section 40).
     */
    val fileAvailable: Boolean,
)

/**
 * Why the play button is off.
 *
 * Null when it is on. Stated as a reason rather than a disabled control with no
 * explanation, because "nothing happens when I press it" is the one thing a
 * user cannot debug.
 */
enum class PlaybackUnavailable {
    /** The conversation happened but its audio was never stored (status FAILED). */
    NEVER_RECORDED,

    /** There was a file and there is not any more. */
    FILE_MISSING,
}

/**
 * Why the play button is off for this record, or null when it is on.
 *
 * A free function rather than a view-model method so it can be tested without
 * a view model: Task39 makes the disabled-with-a-reason case an acceptance
 * criterion, and this is the whole of that decision.
 */
internal fun playbackUnavailable(detail: HistoryDetail): PlaybackUnavailable? = when {
    // A file that is there beats everything else the row might say: a record
    // written as FAILED whose audio survived is still playable.
    detail.fileAvailable -> null

    // Nothing was ever written: the recorder failed, or the path is empty.
    detail.status == RecordStatus.FAILED || detail.audioPath == null ->
        PlaybackUnavailable.NEVER_RECORDED

    // There was a path, and the file behind it has gone.
    else -> PlaybackUnavailable.FILE_MISSING
}

@StringRes
internal fun PlaybackUnavailable.labelRes(): Int = when (this) {
    PlaybackUnavailable.NEVER_RECORDED -> R.string.history_not_recorded
    PlaybackUnavailable.FILE_MISSING -> R.string.history_file_missing
}

@StringRes
internal fun PlaybackError.labelRes(): Int = when (this) {
    PlaybackError.MISSING_FILE -> R.string.history_file_missing
    PlaybackError.UNPLAYABLE -> R.string.history_unplayable
}

/**
 * The subtitle area, which is not always there.
 *
 * `docs/04_UI_UX.md` section 29.1 maps all five states, and the first one is
 * the important one: with ASR off -- the default on every device -- there is no
 * subtitle section at all, rather than an empty box implying something is
 * missing.
 */
@StringRes
internal fun TranscriptStatus.statusLabelRes(): Int? = when (this) {
    TranscriptStatus.NOT_REQUESTED -> null
    TranscriptStatus.PENDING -> R.string.history_transcript_pending
    TranscriptStatus.PROCESSING -> R.string.history_transcript_processing
    TranscriptStatus.COMPLETED -> null
    TranscriptStatus.FAILED -> R.string.history_transcript_failed
}

@StringRes
internal fun RecordStatus.labelRes(): Int? = when (this) {
    // A conversation that simply happened needs no label; the other two are
    // why a recording is short or missing.
    RecordStatus.COMPLETED -> null
    RecordStatus.INTERRUPTED -> R.string.history_status_interrupted
    RecordStatus.FAILED -> R.string.history_status_failed
}
