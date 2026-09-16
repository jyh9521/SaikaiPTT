package com.saikai.ptt.core.history

import com.saikai.ptt.core.RecordingSink
import com.saikai.ptt.core.common.Outcome
import com.saikai.ptt.core.config.LoggingConfig
import com.saikai.ptt.core.logger.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deleting because the user asked, which differs from cleanup in exactly two
 * ways and both of them are about the user standing there
 * (`docs/05_DataModel.md` sections 38 and 39).
 */
class HistoryEraserTest {

    private val logger = Logger(LoggingConfig.debug(), RecordingSink())

    private fun eraser(history: FakeHistory, files: FakeRecordingFiles) =
        HistoryEraser(history, files, logger)

    @Test
    fun `deleting a record takes its audio with it`() = runBlocking {
        val one = record("one", 1_000)
        val two = record("two", 2_000)
        val history = FakeHistory(listOf(one, two))
        val files = FakeRecordingFiles(
            mapOf(one.audioPath!! to 1_000L, two.audioPath!! to 2_000L)
        )

        assertTrue(eraser(history, files).erase("one") is Outcome.Success)

        assertEquals(listOf("two"), history.rows.map { it.id })
        assertEquals(setOf(two.audioPath), files.present.keys)
    }

    @Test
    fun `deleting a record that is not there is reported, not ignored`() = runBlocking {
        val outcome = eraser(FakeHistory(), FakeRecordingFiles()).erase("ghost")
        assertTrue(outcome is Outcome.Failure)
    }

    @Test
    fun `a file that will not delete does not keep the row alive`() = runBlocking {
        // The opposite of cleanup's answer, on purpose. The user pressed a
        // button; a delete that appears to do nothing is the worse failure, and
        // the file is left for the orphan scan.
        val stuck = record("stuck", 1_000)
        val history = FakeHistory(listOf(stuck))
        val files = FakeRecordingFiles(mapOf(stuck.audioPath!! to 1_000L))
        files.undeletable += stuck.audioPath

        assertTrue(eraser(history, files).erase("stuck") is Outcome.Success)

        assertTrue(history.rows.isEmpty())
        assertTrue("left for the orphan scan", stuck.audioPath in files.present)
    }

    @Test
    fun `a favourite can be deleted by hand`() = runBlocking {
        // Cleanup must never touch one. A user who selects it and confirms is
        // saying something different.
        val kept = record("kept", 1_000, favorite = true)
        val history = FakeHistory(listOf(kept))
        val files = FakeRecordingFiles(mapOf(kept.audioPath!! to 1_000L))

        assertTrue(eraser(history, files).erase("kept") is Outcome.Success)

        assertTrue(history.rows.isEmpty())
        assertTrue(files.present.isEmpty())
    }

    @Test
    fun `clearing everything leaves the favourites alone by default`() = runBlocking {
        val ordinary = record("ordinary", 1_000)
        val kept = record("kept", 2_000, favorite = true)
        val history = FakeHistory(listOf(ordinary, kept))
        val files = FakeRecordingFiles(
            mapOf(ordinary.audioPath!! to 1_000L, kept.audioPath!! to 2_000L)
        )

        val erased = eraser(history, files).eraseEverything(includeFavorites = false)

        assertEquals(Outcome.success(1), erased)
        assertEquals(listOf("kept"), history.rows.map { it.id })
        assertEquals(setOf(kept.audioPath), files.present.keys)
    }

    @Test
    fun `clearing everything takes the favourites when that was confirmed too`() = runBlocking {
        val ordinary = record("ordinary", 1_000)
        val kept = record("kept", 2_000, favorite = true)
        val history = FakeHistory(listOf(ordinary, kept))
        val files = FakeRecordingFiles(
            mapOf(ordinary.audioPath!! to 1_000L, kept.audioPath!! to 2_000L)
        )

        val erased = eraser(history, files).eraseEverything(includeFavorites = true)

        assertEquals(Outcome.success(2), erased)
        assertTrue(history.rows.isEmpty())
        assertTrue(files.present.isEmpty())
    }

    @Test
    fun `clearing an empty history is not an error`() = runBlocking {
        val erased = eraser(FakeHistory(), FakeRecordingFiles())
            .eraseEverything(includeFavorites = true)
        assertEquals(Outcome.success(0), erased)
    }

    @Test
    fun `a database that will not answer stops the clear instead of half doing it`() =
        runBlocking {
            val history = FakeHistory(listOf(record("one", 1_000))).apply { readable = false }
            val files = FakeRecordingFiles(mapOf("records/2026/09/16/one.opus" to 1_000L))

            val erased = eraser(history, files).eraseEverything(includeFavorites = false)

            assertTrue(erased is Outcome.Failure)
            assertFalse("nothing was deleted", files.present.isEmpty())
        }

    @Test
    fun `deleting several records is one call`() = runBlocking {
        val records = (1..5).map { record("r$it", it * 1_000L) }
        val history = FakeHistory(records)
        val files = FakeRecordingFiles(records.associate { it.audioPath!! to it.timestamp })

        val erased = eraser(history, files).eraseAll(records.take(3))

        assertEquals(Outcome.success(3), erased)
        assertEquals(listOf("r4", "r5"), history.rows.map { it.id })
        assertEquals(2, files.present.size)
    }

    @Test
    fun `deleting nothing touches nothing`() = runBlocking {
        val files = FakeRecordingFiles(mapOf("records/2026/09/16/one.opus" to 1_000L))
        assertEquals(Outcome.success(0), eraser(FakeHistory(), files).eraseAll(emptyList()))
        assertEquals(0, files.pruned)
    }
}
