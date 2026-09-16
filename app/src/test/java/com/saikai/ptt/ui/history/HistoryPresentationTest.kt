package com.saikai.ptt.ui.history

import com.saikai.ptt.core.domain.Direction
import com.saikai.ptt.core.domain.RecordStatus
import com.saikai.ptt.core.domain.TranscriptStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two pieces of history presentation that are pure enough to test on the
 * JVM: why the play button is off, and how a duration reads.
 *
 * Everything else on these screens needs a device, and Task39's acceptance
 * criteria say so. What is worth pinning here is the decision that Task39 calls
 * out by name -- a missing file must disable the button *with a reason* rather
 * than crash -- and the one formatting rule that a translation could silently
 * break.
 */
class HistoryPresentationTest {

    private fun detail(
        audioPath: String? = "records/2026/09/16/1.opus",
        status: RecordStatus = RecordStatus.COMPLETED,
        fileAvailable: Boolean = true,
    ) = HistoryDetail(
        id = "1",
        remoteUserName = "山田",
        direction = Direction.RECEIVE,
        timestamp = 0L,
        durationMs = 1_000L,
        audioPath = audioPath,
        transcript = null,
        transcriptStatus = TranscriptStatus.NOT_REQUESTED,
        isFavorite = false,
        status = status,
        fileAvailable = fileAvailable,
    )

    @Test
    fun `a present file plays`() {
        assertNull(playbackUnavailable(detail()))
    }

    @Test
    fun `a present file plays even when the record was written as failed`() {
        // The recorder reported a problem and the audio survived anyway. The
        // record's own status must not veto a file that is sitting there.
        assertNull(playbackUnavailable(detail(status = RecordStatus.FAILED, fileAvailable = true)))
    }

    @Test
    fun `a failed record with no file was never recorded`() {
        assertEquals(
            PlaybackUnavailable.NEVER_RECORDED,
            playbackUnavailable(detail(status = RecordStatus.FAILED, fileAvailable = false)),
        )
    }

    @Test
    fun `a record with no path at all was never recorded`() {
        assertEquals(
            PlaybackUnavailable.NEVER_RECORDED,
            playbackUnavailable(detail(audioPath = null, fileAvailable = false)),
        )
    }

    @Test
    fun `a completed record whose file has gone says so separately`() {
        // Deleted by hand, lost with the app's data, or removed by a cleanup
        // that Task40 will own. Different cause, different sentence.
        assertEquals(
            PlaybackUnavailable.FILE_MISSING,
            playbackUnavailable(detail(fileAvailable = false)),
        )
    }

    @Test
    fun `an interrupted record whose file has gone says so too`() {
        assertEquals(
            PlaybackUnavailable.FILE_MISSING,
            playbackUnavailable(detail(status = RecordStatus.INTERRUPTED, fileAvailable = false)),
        )
    }

    @Test
    fun `durations read as minutes and seconds`() {
        assertEquals("00:00", HistoryFormat.duration(0))
        assertEquals("00:01", HistoryFormat.duration(1_000))
        assertEquals("00:59", HistoryFormat.duration(59_999))
        assertEquals("01:00", HistoryFormat.duration(60_000))
        assertEquals("12:34", HistoryFormat.duration(754_000))
        assertEquals("99:59", HistoryFormat.duration(5_999_000))
    }

    @Test
    fun `a negative duration reads as zero rather than as a minus sign`() {
        // Clocks that disagree, or a finish written before its start. The row
        // still has to draw something.
        assertEquals("00:00", HistoryFormat.duration(-1))
        assertEquals("00:00", HistoryFormat.duration(Long.MIN_VALUE))
    }

    @Test
    fun `subtitles are absent until they are asked for`() {
        // The default on every device (CLAUDE.md section 18.1), and the reason
        // the subtitle area is not drawn at all rather than drawn empty.
        assertNull(TranscriptStatus.NOT_REQUESTED.statusLabelRes())
        // A finished transcript needs no status line either: the text is there.
        assertNull(TranscriptStatus.COMPLETED.statusLabelRes())
    }

    @Test
    fun `a completed conversation carries no status note`() {
        assertNull(RecordStatus.COMPLETED.labelRes())
    }
}
