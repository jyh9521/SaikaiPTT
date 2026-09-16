package com.saikai.ptt.core.history

import com.saikai.ptt.core.RecordingSink
import com.saikai.ptt.core.audio.RecordingPaths
import com.saikai.ptt.core.domain.HistoryRetention
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.config.LoggingConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cleanup, which is the one thing in this app that deletes something the user
 * cannot get back.
 *
 * So the tests are mostly about what must **not** happen: favourites surviving
 * every retention setting, a file being played surviving a pass, a recording
 * finished a minute ago surviving the orphan scan, and one stubborn file not
 * taking the rest of the pass down with it
 * (`docs/05_DataModel.md` sections 25, 30, 31, 32).
 */
class HistoryCleanerTest {

    private val sink = RecordingSink()
    private val logger = Logger(LoggingConfig.debug(), sink)
    private var now = 1_800_000_000_000L

    private val day = 24L * 60 * 60 * 1000

    private fun cleaner(
        history: FakeHistory,
        files: FakeRecordingFiles,
        active: ActiveRecordings = ActiveRecordings(),
    ) = HistoryCleaner(history, files, active, logger) { now }

    // --- expiry ----------------------------------------------------------------------

    @Test
    fun `records past the retention window go, with their audio`() = runBlocking {
        val old = record("old", now - 8 * day)
        val recent = record("recent", now - 1 * day)
        val history = FakeHistory(listOf(old, recent))
        val files = FakeRecordingFiles(
            mapOf(old.audioPath!! to old.timestamp, recent.audioPath!! to recent.timestamp)
        )

        val report = cleaner(history, files).run(HistoryRetention.SEVEN_DAYS)

        assertEquals(1, report.recordsDeleted)
        assertEquals(1, report.audioDeleted)
        assertEquals(listOf("recent"), history.rows.map { it.id })
        assertEquals(listOf(recent.audioPath), files.present.keys.toList())
    }

    @Test
    fun `a favourite survives every retention setting`() = runBlocking {
        val kept = record("kept", now - 400 * day, favorite = true)
        val history = FakeHistory(listOf(kept))
        val files = FakeRecordingFiles(mapOf(kept.audioPath!! to kept.timestamp))

        for (retention in HistoryRetention.entries) {
            cleaner(history, files).run(retention)
            assertEquals("survives $retention", listOf("kept"), history.rows.map { it.id })
            assertTrue("audio survives $retention", kept.audioPath in files.present)
        }
    }

    @Test
    fun `a favourite's audio is not an orphan either`() = runBlocking {
        // The orphan scan is the second way a favourite could lose its audio:
        // it is old, and nothing but its own row refers to it.
        val kept = record("kept", now - 400 * day, favorite = true)
        val history = FakeHistory(listOf(kept))
        val files = FakeRecordingFiles(mapOf(kept.audioPath!! to now - 400 * day))

        cleaner(history, files).run(HistoryRetention.ONE_DAY)

        assertTrue(kept.audioPath in files.present)
    }

    @Test
    fun `FOREVER expires nothing but still collects orphans`() = runBlocking {
        val kept = record("kept", now - 400 * day)
        val history = FakeHistory(listOf(kept))
        val files = FakeRecordingFiles(
            mapOf(
                kept.audioPath!! to kept.timestamp,
                "records/2020/01/01/ghost.opus" to now - 400 * day,
            )
        )

        val report = cleaner(history, files).run(HistoryRetention.FOREVER)

        assertEquals(0, report.recordsDeleted)
        assertEquals(1, report.orphansFound)
        assertEquals(setOf(kept.audioPath), files.present.keys)
    }

    // --- things in use ---------------------------------------------------------------

    @Test
    fun `a recording being played is left alone, row and all`() = runBlocking {
        val playing = record("playing", now - 30 * day)
        val history = FakeHistory(listOf(playing))
        val files = FakeRecordingFiles(mapOf(playing.audioPath!! to playing.timestamp))
        val active = ActiveRecordings().apply { claim(playing.audioPath) }

        val report = cleaner(history, files, active).run(HistoryRetention.ONE_DAY)

        assertEquals(0, report.recordsDeleted)
        assertTrue(playing.audioPath in files.present)
        assertEquals(listOf("playing"), history.rows.map { it.id })

        // Released, the next pass takes it.
        active.release(playing.audioPath)
        cleaner(history, files, active).run(HistoryRetention.ONE_DAY)
        assertTrue(history.rows.isEmpty())
        assertFalse(playing.audioPath in files.present)
    }

    @Test
    fun `a recording in progress is not an orphan`() = runBlocking {
        // Under .tmp, claimed by the recorder, and no row points at it -- which
        // is exactly what an abandoned file looks like as well. The claim is
        // what tells them apart while it is happening.
        val inProgress = "${RecordingPaths.TEMP_DIRECTORY}/live.opus"
        val history = FakeHistory()
        val files = FakeRecordingFiles(mapOf(inProgress to now - 400 * day))
        val active = ActiveRecordings().apply { claim(inProgress) }

        cleaner(history, files, active).run(HistoryRetention.ONE_DAY)

        assertTrue(inProgress in files.present)
    }

    @Test
    fun `a temporary file left by a dead process is collected once it is cold`() = runBlocking {
        val abandoned = "${RecordingPaths.TEMP_DIRECTORY}/crashed.opus"
        val history = FakeHistory()
        val files = FakeRecordingFiles(mapOf(abandoned to now - 2 * day))

        val report = cleaner(history, files).run(HistoryRetention.SEVEN_DAYS)

        assertEquals(1, report.orphansFound)
        assertTrue(files.present.isEmpty())
    }

    @Test
    fun `a file finished a minute ago is not an orphan yet`() = runBlocking {
        // The window between "the file is moved and closed" and "the row is
        // inserted". Milliseconds wide in practice; the grace period is an hour
        // because the cost of being wrong is deleting what the user just said.
        val justWritten = "records/2026/09/16/fresh.opus"
        val history = FakeHistory()
        val files = FakeRecordingFiles(mapOf(justWritten to now - 60_000))

        val report = cleaner(history, files).run(HistoryRetention.SEVEN_DAYS)

        assertEquals(0, report.orphansFound)
        assertTrue(justWritten in files.present)
    }

    @Test
    fun `the grace period is exactly an hour`() = runBlocking {
        val history = FakeHistory()
        val files = FakeRecordingFiles(
            mapOf(
                "records/2026/09/16/inside.opus" to now - HistoryCleaner.ORPHAN_GRACE_MILLIS + 1,
                "records/2026/09/16/outside.opus" to now - HistoryCleaner.ORPHAN_GRACE_MILLIS - 1,
            )
        )

        cleaner(history, files).run(HistoryRetention.SEVEN_DAYS)

        assertEquals(setOf("records/2026/09/16/inside.opus"), files.present.keys)
    }

    // --- failures --------------------------------------------------------------------

    @Test
    fun `one file that will not delete does not stop the pass`() = runBlocking {
        val stuck = record("stuck", now - 30 * day)
        val fine = record("fine", now - 30 * day)
        val history = FakeHistory(listOf(stuck, fine))
        val files = FakeRecordingFiles(
            mapOf(stuck.audioPath!! to stuck.timestamp, fine.audioPath!! to fine.timestamp)
        )
        files.undeletable += stuck.audioPath

        val report = cleaner(history, files).run(HistoryRetention.ONE_DAY)

        assertEquals(1, report.audioFailed)
        assertEquals(1, report.recordsDeleted)
        assertEquals(1, report.audioDeleted)
        // The stubborn one keeps its row, so the next pass tries the file again
        // instead of the orphan scan finding a file whose record has gone.
        assertEquals(listOf("stuck"), history.rows.map { it.id })
    }

    @Test
    fun `a record whose audio has already vanished still loses its row`() = runBlocking {
        val gone = record("gone", now - 30 * day)
        val history = FakeHistory(listOf(gone))
        val files = FakeRecordingFiles()

        val report = cleaner(history, files).run(HistoryRetention.ONE_DAY)

        assertEquals(1, report.recordsDeleted)
        assertEquals(0, report.audioDeleted)
        assertTrue(history.rows.isEmpty())
    }

    @Test
    fun `a record that never had audio still loses its row`() = runBlocking {
        val noAudio = record("no-audio", now - 30 * day, audioPath = null)
        val history = FakeHistory(listOf(noAudio))
        val files = FakeRecordingFiles()

        assertEquals(1, cleaner(history, files).run(HistoryRetention.ONE_DAY).recordsDeleted)
    }

    @Test
    fun `a database that will not answer deletes nothing at all`() = runBlocking {
        // The dangerous case: if the orphan scan ran here it would see an empty
        // set of referenced paths and call every recording on the device an
        // orphan.
        val existing = record("existing", now - 30 * day)
        val history = FakeHistory(listOf(existing)).apply { readable = false }
        val files = FakeRecordingFiles(mapOf(existing.audioPath!! to existing.timestamp))

        val report = cleaner(history, files).run(HistoryRetention.ONE_DAY)

        assertFalse(report.didSomething)
        assertEquals(setOf(existing.audioPath), files.present.keys)
    }

    @Test
    fun `rows that will not delete stop the pass before the orphan scan`() = runBlocking {
        // Audio is deleted before its row, so if the row delete fails the file
        // is already gone. Continuing to the orphan scan would then be fine --
        // but a database that refuses a delete may refuse the read too, and the
        // scan's failure mode is deleting everything.
        val expired = record("expired", now - 30 * day)
        val history = FakeHistory(listOf(expired)).apply { writable = false }
        val files = FakeRecordingFiles(
            mapOf(
                expired.audioPath!! to expired.timestamp,
                "records/2020/01/01/ghost.opus" to now - 400 * day,
            )
        )

        val report = cleaner(history, files).run(HistoryRetention.ONE_DAY)

        assertEquals(0, report.orphansFound)
        assertTrue("records/2020/01/01/ghost.opus" in files.present)
    }

    // --- batching --------------------------------------------------------------------

    @Test
    fun `a batch of only skipped records ends the sweep instead of looping`() = runBlocking {
        val held = record("held", now - 30 * day)
        val history = FakeHistory(listOf(held))
        val files = FakeRecordingFiles(mapOf(held.audioPath!! to held.timestamp))
        val active = ActiveRecordings().apply { claim(held.audioPath) }

        cleaner(history, files, active).run(HistoryRetention.ONE_DAY)

        assertEquals(1, history.expiredQueries)
    }

    @Test
    fun `empty directories are pruned only when something was removed`() = runBlocking {
        val files = FakeRecordingFiles()
        cleaner(FakeHistory(), files).run(HistoryRetention.SEVEN_DAYS)
        assertEquals(0, files.pruned)

        val expired = record("expired", now - 30 * day)
        val history = FakeHistory(listOf(expired))
        val withFile = FakeRecordingFiles(mapOf(expired.audioPath!! to expired.timestamp))
        cleaner(history, withFile).run(HistoryRetention.ONE_DAY)
        assertEquals(1, withFile.pruned)
    }
}
