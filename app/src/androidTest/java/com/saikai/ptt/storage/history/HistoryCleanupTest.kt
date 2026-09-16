package com.saikai.ptt.storage.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saikai.ptt.core.audio.RecordingPaths
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.config.LoggingConfig
import com.saikai.ptt.core.domain.AudioFormat
import com.saikai.ptt.core.domain.CommunicationRecord
import com.saikai.ptt.core.domain.HistoryRetention
import com.saikai.ptt.core.domain.RecordStatus
import com.saikai.ptt.core.history.ActiveRecordings
import com.saikai.ptt.core.history.HistoryCleaner
import com.saikai.ptt.core.history.HistoryEraser
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.LogSink
import com.saikai.ptt.core.logger.Logger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Cleanup end to end: real SQLite, real files, real paths.
 *
 * `HistoryCleanerTest` in `:core` pins the *ordering* against in-memory
 * stand-ins, and `LocalRecordingFilesTest` pins the path arithmetic on the JVM.
 * Neither can catch the thing that only shows up when they are wired together
 * -- a path the database stores that the file layer resolves differently, a row
 * deleted whose file was never found because the walk returned a different
 * spelling of the same path.
 *
 * `docs/07_TestPlan.md` sections 31 and 32: TC-CONS-005, TC-CONS-006,
 * TC-CLEAN-001 through TC-CLEAN-006.
 */
@RunWith(AndroidJUnit4::class)
class HistoryCleanupTest {

    private lateinit var database: SaikaiDatabase
    private lateinit var dao: CommunicationRecordDao
    private lateinit var repository: RoomHistoryRepository
    private lateinit var filesDir: File
    private lateinit var files: LocalRecordingFiles
    private lateinit var active: ActiveRecordings

    private val logger = Logger(
        LoggingConfig.debug(),
        object : LogSink {
            override fun write(
                level: LogLevel,
                category: LogCategory,
                message: String,
                throwable: Throwable?,
            ) = Unit
        },
    )

    private val local = "11111111-1111-4111-8111-111111111111"
    private val remote = "22222222-2222-4222-8222-222222222222"

    /** Fixed, so retention arithmetic in the test does not depend on the clock. */
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SaikaiDatabase::class.java,
        ).build()
        dao = database.records()
        repository = RoomHistoryRepository(dao, logger)

        // A directory of this test's own, not the app's: an instrumented test
        // must not delete recordings somebody made on the device it runs on.
        filesDir = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            "cleanup-test-${UUID.randomUUID()}",
        ).apply { mkdirs() }
        files = LocalRecordingFiles(filesDir, logger)
        active = ActiveRecordings()
    }

    @After
    fun close() {
        database.close()
        filesDir.deleteRecursively()
    }

    private fun cleaner() = HistoryCleaner(repository, files, active, logger) { now }

    private fun eraser() = HistoryEraser(repository, files, logger)

    /**
     * Stores a record and writes the audio file it points at.
     *
     * @param modifiedAt when the file was last written; defaults to the
     *   record's own timestamp, which is what a real recording looks like.
     */
    private suspend fun store(
        sessionId: String,
        timestamp: Long,
        favorite: Boolean = false,
        withAudio: Boolean = true,
        modifiedAt: Long = timestamp,
    ): CommunicationRecord {
        val id = UUID.randomUUID().toString()
        val path = if (withAudio) {
            RecordingPaths.finished(id, AudioFormat.OPUS, timestamp)
        } else {
            null
        }
        if (path != null) {
            val file = File(filesDir, path)
            file.parentFile?.mkdirs()
            file.writeBytes(ByteArray(64))
            file.setLastModified(modifiedAt)
        }
        val record = CommunicationRecord.create(
            localDeviceId = local,
            sessionId = sessionId,
            senderDeviceId = remote,
            receiverDeviceId = local,
            localUserId = "user-1",
            remoteUserName = "山田",
            timestamp = timestamp,
            durationMs = 4_200L,
            status = RecordStatus.COMPLETED,
            audioPath = path,
            audioFormat = if (path == null) null else AudioFormat.OPUS,
            id = id,
        ).copy(isFavorite = favorite)
        assertTrue(repository.save(record) is Outcome.Success)
        return record
    }

    private fun exists(record: CommunicationRecord): Boolean =
        record.audioPath != null && File(filesDir, record.audioPath!!).isFile

    private suspend fun rowCount(): Int =
        (repository.counts() as Outcome.Success).value.total

    // --- TC-CLEAN-001: audio goes with the row ----------------------------------------

    @Test
    fun anExpiredRecordLosesItsRowAndItsAudio() = runBlocking {
        val old = store("old", now - 8 * day)
        val recent = store("recent", now - 1 * day)

        val report = cleaner().run(HistoryRetention.SEVEN_DAYS)

        assertEquals(1, report.recordsDeleted)
        assertEquals(1, rowCount())
        assertFalse("the expired recording is gone", exists(old))
        assertTrue("the recent one is untouched", exists(recent))
    }

    @Test
    fun theDatedDirectoryIsRemovedOnceItIsEmpty() = runBlocking {
        val old = store("old", now - 30 * day)
        val directory = File(filesDir, RecordingPaths.directoryOf(old.audioPath!!))
        assertTrue(directory.isDirectory)

        cleaner().run(HistoryRetention.ONE_DAY)

        assertFalse("an empty dated directory per day forever", directory.exists())
    }

    // --- TC-CLEAN-002: favourites survive ---------------------------------------------

    @Test
    fun aFavouriteSurvivesEveryRetentionSetting() = runBlocking {
        val kept = store("kept", now - 400 * day, favorite = true)

        for (retention in HistoryRetention.entries) {
            cleaner().run(retention)
            assertEquals("survives $retention", 1, rowCount())
            assertTrue("audio survives $retention", exists(kept))
        }
    }

    // --- TC-CLEAN-004: orphan scan ----------------------------------------------------

    @Test
    fun aFileNoRowPointsAtIsCollectedOnceItIsCold() = runBlocking {
        val orphan = File(filesDir, "records/2020/01/01/orphan.opus")
        orphan.parentFile?.mkdirs()
        orphan.writeBytes(ByteArray(64))
        orphan.setLastModified(now - 30 * day)

        val report = cleaner().run(HistoryRetention.FOREVER)

        assertEquals(1, report.orphansFound)
        assertFalse(orphan.exists())
    }

    @Test
    fun aRecordingFinishedMinutesAgoIsNotAnOrphanYet() = runBlocking {
        // The window between the recorder moving a file into place and the row
        // being inserted. Real, and milliseconds wide; the grace period is an
        // hour because deleting what the user just said is the worse mistake.
        val fresh = File(filesDir, "records/2026/09/16/fresh.opus")
        fresh.parentFile?.mkdirs()
        fresh.writeBytes(ByteArray(64))
        fresh.setLastModified(now - 60_000)

        val report = cleaner().run(HistoryRetention.FOREVER)

        assertEquals(0, report.orphansFound)
        assertTrue(fresh.exists())
    }

    @Test
    fun aFavouritesAudioIsNotAnOrphanEither() = runBlocking {
        // The second way a favourite could lose its audio: it is old, and the
        // only thing referring to it is its own row.
        val kept = store("kept", now - 400 * day, favorite = true)

        cleaner().run(HistoryRetention.ONE_DAY)

        assertTrue(exists(kept))
    }

    // --- TC-CONS-005 / TC-CONS-006: files in use --------------------------------------

    @Test
    fun aRecordingInProgressIsNotCollected() = runBlocking {
        // Under .tmp, claimed by the recorder, referenced by no row -- which is
        // also exactly what an abandoned file looks like. The claim is what
        // tells them apart while it is still happening.
        val temporary = "${RecordingPaths.TEMP_DIRECTORY}/live.opus"
        val file = File(filesDir, temporary)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(64))
        file.setLastModified(now - 30 * day)
        active.claim(temporary)

        cleaner().run(HistoryRetention.ONE_DAY)

        assertTrue("a call in progress lost its recording", file.exists())
    }

    @Test
    fun aRecordingBeingPlayedKeepsItsRowAndItsFileUntilItIsReleased() = runBlocking {
        val playing = store("playing", now - 30 * day)
        active.claim(playing.audioPath!!)

        cleaner().run(HistoryRetention.ONE_DAY)
        assertEquals("the row is kept so the next pass sees it again", 1, rowCount())
        assertTrue(exists(playing))

        active.release(playing.audioPath!!)
        cleaner().run(HistoryRetention.ONE_DAY)
        assertEquals(0, rowCount())
        assertFalse(exists(playing))
    }

    @Test
    fun aTemporaryFileLeftByADeadProcessIsCollected() = runBlocking {
        // Same path shape as the test above, minus the claim: the process that
        // was writing it is gone, so nothing will ever finish it.
        val abandoned = File(filesDir, "${RecordingPaths.TEMP_DIRECTORY}/crashed.opus")
        abandoned.parentFile?.mkdirs()
        abandoned.writeBytes(ByteArray(64))
        abandoned.setLastModified(now - 2 * day)

        val report = cleaner().run(HistoryRetention.SEVEN_DAYS)

        assertEquals(1, report.orphansFound)
        assertFalse(abandoned.exists())
        assertTrue(
            "the recorder expects this directory to exist",
            File(filesDir, RecordingPaths.TEMP_DIRECTORY).isDirectory,
        )
    }

    // --- TC-CLEAN-005 / TC-CLEAN-006: deleting by hand --------------------------------

    @Test
    fun deletingOneRecordTakesItsAudio() = runBlocking {
        val gone = store("gone", now)
        val kept = store("kept", now)

        assertTrue(eraser().erase(gone.id) is Outcome.Success)

        assertEquals(1, rowCount())
        assertFalse(exists(gone))
        assertTrue(exists(kept))
    }

    @Test
    fun aFavouriteCanBeDeletedByHand() = runBlocking {
        // Automatic cleanup must never touch one. A user who selects it and
        // confirms is saying something different.
        val kept = store("kept", now, favorite = true)

        assertTrue(eraser().erase(kept.id) is Outcome.Success)

        assertEquals(0, rowCount())
        assertFalse(exists(kept))
    }

    @Test
    fun clearingEverythingLeavesTheFavouritesUnlessAsked() = runBlocking {
        val ordinary = store("ordinary", now)
        val kept = store("kept", now, favorite = true)

        assertEquals(Outcome.success(1), eraser().eraseEverything(includeFavorites = false))
        assertEquals(1, rowCount())
        assertFalse(exists(ordinary))
        assertTrue(exists(kept))

        assertEquals(Outcome.success(1), eraser().eraseEverything(includeFavorites = true))
        assertEquals(0, rowCount())
        assertFalse(exists(kept))
    }

    @Test
    fun aRecordWithNoAudioDeletesCleanly() = runBlocking {
        // status FAILED with no path: the recorder could not write anything.
        val noAudio = store("no-audio", now, withAudio = false)

        assertTrue(eraser().erase(noAudio.id) is Outcome.Success)
        assertEquals(0, rowCount())
    }

    // --- a full sweep -----------------------------------------------------------------

    @Test
    fun onePassOverAMixedHistoryLeavesExactlyWhatItShould() = runBlocking {
        val expired = store("expired", now - 30 * day)
        val expiredFavourite = store("expired-favourite", now - 30 * day, favorite = true)
        val recent = store("recent", now - 1 * day)
        val playing = store("playing", now - 30 * day)
        active.claim(playing.audioPath!!)

        val orphan = File(filesDir, "records/2019/05/05/orphan.opus")
        orphan.parentFile?.mkdirs()
        orphan.writeBytes(ByteArray(64))
        orphan.setLastModified(now - 100 * day)

        val report = cleaner().run(HistoryRetention.SEVEN_DAYS)

        assertEquals("only the plain expired record", 1, report.recordsDeleted)
        assertEquals(1, report.orphansFound)
        assertFalse(exists(expired))
        assertTrue(exists(expiredFavourite))
        assertTrue(exists(recent))
        assertTrue(exists(playing))
        assertFalse(orphan.exists())
        assertEquals(3, rowCount())
    }
}
