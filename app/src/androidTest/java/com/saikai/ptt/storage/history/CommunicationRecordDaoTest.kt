package com.saikai.ptt.storage.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saikai.ptt.core.config.LoggingConfig
import com.saikai.ptt.core.domain.AudioFormat
import com.saikai.ptt.core.domain.CommunicationRecord
import com.saikai.ptt.core.domain.Direction
import com.saikai.ptt.core.domain.RecordStatus
import com.saikai.ptt.core.domain.TranscriptStatus
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.LogSink
import com.saikai.ptt.core.logger.Logger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * The DAO, its indices, and the schema, against real SQLite.
 *
 * On a device rather than the JVM because there is no other honest way: the
 * DAO's implementation is generated at build time and the thing it talks to is
 * Android's SQLite. A JVM double would be testing a hand-written stand-in for
 * the code that actually ships.
 *
 * What is *not* tested here is anything that can be checked without a database
 * -- the direction and remote-device rules, the enum fallbacks, the
 * path/format invariant. Those are in `CommunicationRecordTest` in `:core`,
 * where they run in milliseconds and without a phone.
 */
@RunWith(AndroidJUnit4::class)
class CommunicationRecordDaoTest {

    private lateinit var database: SaikaiDatabase
    private lateinit var dao: CommunicationRecordDao

    /** Nothing is asserted about the log here, so it goes nowhere. */
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
    private val other = "33333333-3333-4333-8333-333333333333"

    @Before
    fun open() {
        // In memory, so each test starts empty and nothing is left on the
        // device. Still the platform's SQLite, which is the point.
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SaikaiDatabase::class.java,
        ).build()
        dao = database.records()
    }

    @After
    fun close() {
        database.close()
    }

    private fun record(
        sender: String = remote,
        receiver: String = local,
        userName: String = "山田",
        timestamp: Long = 1_700_000_000_000L,
        sessionId: String = UUID.randomUUID().toString(),
        status: RecordStatus = RecordStatus.COMPLETED,
        audioPath: String? = null,
        audioFormat: AudioFormat? = null,
    ): CommunicationRecord = CommunicationRecord.create(
        localDeviceId = local,
        sessionId = sessionId,
        senderDeviceId = sender,
        receiverDeviceId = receiver,
        localUserId = "user-1",
        remoteUserName = userName,
        timestamp = timestamp,
        durationMs = 4_200L,
        status = status,
        audioPath = audioPath,
        audioFormat = audioFormat,
    )

    @Test
    fun everyFieldSurvivesARoundTrip() = runBlocking {
        val original = record(
            sender = local,
            receiver = remote,
            audioPath = "records/2026/09/16/a.opus",
            audioFormat = AudioFormat.OPUS,
        ).copy(
            transcript = "おはようございます",
            transcriptStatus = TranscriptStatus.COMPLETED,
            isFavorite = true,
            status = RecordStatus.INTERRUPTED,
        )

        assertTrue(dao.save(original.toEntity()))

        // Field for field: docs/05_DataModel.md section 10 is the contract, and
        // a column silently dropped in mapping would look exactly like a
        // feature nobody implemented.
        assertEquals(original, dao.byId(original.id)?.toDomain())
    }

    @Test
    fun aNullAudioPathRoundTripsAsNull() = runBlocking {
        val original = record()
        dao.save(original.toEntity())

        val stored = dao.byId(original.id)?.toDomain()
        assertNull(stored?.audioPath)
        assertNull(stored?.audioFormat)
        assertEquals(TranscriptStatus.NOT_REQUESTED, stored?.transcriptStatus)
    }

    /**
     * The unique index on `sessionId` is what makes a duplicate impossible.
     *
     * Both devices in a conversation write their own record for the same
     * session, into their own databases; what must never happen is one device
     * writing two. IGNORE rather than REPLACE, so the first row -- and the
     * audio file named after its record id -- survives.
     */
    @Test
    fun aSecondRecordForTheSameSessionIsRefusedAndTheFirstSurvives() = runBlocking {
        val session = UUID.randomUUID().toString()
        val first = record(sessionId = session, userName = "山田")
        val second = record(sessionId = session, userName = "田中")

        assertTrue(dao.save(first.toEntity()))
        assertFalse(dao.save(second.toEntity()))

        assertEquals(1, dao.count())
        assertEquals("山田", dao.bySession(session)?.remoteUserName)
        assertNotNull(dao.byId(first.id))
        assertNull(dao.byId(second.id))
    }

    @Test
    fun recentRecordsComeBackNewestFirst() = runBlocking {
        val oldest = record(timestamp = 1_000L)
        val middle = record(timestamp = 2_000L)
        val newest = record(timestamp = 3_000L)
        listOf(middle, oldest, newest).forEach { dao.save(it.toEntity()) }

        val ordered = dao.observeRecent(10).first().map { it.timestamp }
        assertEquals(listOf(3_000L, 2_000L, 1_000L), ordered)
    }

    @Test
    fun theLimitIsApplied() = runBlocking {
        repeat(5) { index -> dao.save(record(timestamp = index.toLong()).toEntity()) }

        assertEquals(2, dao.observeRecent(2).first().size)
    }

    @Test
    fun recordsCanBeFoundByTheRemoteDevice() = runBlocking {
        dao.save(record(sender = remote, receiver = local).toEntity())
        dao.save(record(sender = local, receiver = remote).toEntity())
        dao.save(record(sender = other, receiver = local).toEntity())

        // The redundant column earns itself here: one indexed equality instead
        // of an OR across sender and receiver (docs/05_DataModel.md section 10).
        assertEquals(2, dao.byRemoteDevice(remote).size)
        assertEquals(1, dao.byRemoteDevice(other).size)
    }

    @Test
    fun onlyReceivedRecordsCountAsUnread() = runBlocking {
        dao.save(record(sender = remote, receiver = local).toEntity())
        dao.save(record(sender = remote, receiver = local).toEntity())
        // Sent by this device, so it starts read (section 24) and must not
        // appear in a badge for the user's own voice.
        dao.save(record(sender = local, receiver = remote).toEntity())

        assertEquals(2, dao.observeUnreadCount().first())
    }

    @Test
    fun markingReadClearsItFromTheUnreadCount() = runBlocking {
        val received = record(sender = remote, receiver = local)
        dao.save(received.toEntity())
        assertEquals(1, dao.observeUnreadCount().first())

        assertEquals(1, dao.setRead(received.id, read = true, now = 9_999L))

        assertEquals(0, dao.observeUnreadCount().first())
        val stored = dao.byId(received.id)
        assertTrue(stored!!.isRead)
        assertEquals(9_999L, stored.updatedAt)
    }

    @Test
    fun updatingAMissingRecordTouchesNothing() = runBlocking {
        assertEquals(0, dao.setRead("nobody", read = true, now = 1L))
        assertEquals(0, dao.setFavorite("nobody", favorite = true, now = 1L))
        assertEquals(0, dao.deleteById("nobody"))
    }

    @Test
    fun favouritesAreStoredAndDeletionRemovesTheRow() = runBlocking {
        val row = record()
        dao.save(row.toEntity())

        assertEquals(1, dao.setFavorite(row.id, favorite = true, now = 5L))
        assertTrue(dao.byId(row.id)!!.isFavorite)

        assertEquals(1, dao.deleteById(row.id))
        assertNull(dao.byId(row.id))
        assertEquals(0, dao.count())
    }

    @Test
    fun theRecentQueryEmitsAgainWhenARecordIsAdded() = runBlocking {
        // A Flow that does not re-emit would leave the history list stale after
        // every conversation, which is the one moment the user looks at it.
        assertEquals(0, dao.observeRecent(10).first().size)
        dao.save(record().toEntity())
        assertEquals(1, dao.observeRecent(10).first().size)
    }

    /**
     * Every index section 27 asks for is actually on the table.
     *
     * Read back from SQLite rather than from the annotation, because the
     * annotation is what was asked for and this is what was built. An index
     * that silently failed to be created is a query that works and gets slower
     * with every conversation.
     */
    @Test
    fun allEightIndicesExist() {
        val indexed = mutableSetOf<String>()
        var uniqueOnSessionId = false

        database.openHelper.readableDatabase
            .query("PRAGMA index_list('communication_record')").use { list ->
                while (list.moveToNext()) {
                    val name = list.getString(list.getColumnIndexOrThrow("name"))
                    val unique = list.getInt(list.getColumnIndexOrThrow("unique")) == 1
                    database.openHelper.readableDatabase
                        .query("PRAGMA index_info('$name')").use { info ->
                            while (info.moveToNext()) {
                                val column = info.getString(info.getColumnIndexOrThrow("name"))
                                indexed += column
                                if (column == "sessionId" && unique) uniqueOnSessionId = true
                            }
                        }
                }
            }

        listOf(
            "timestamp",
            "sessionId",
            "remoteDeviceId",
            "remoteUserName",
            "isRead",
            "isFavorite",
            "transcriptStatus",
            "status",
        ).forEach { column ->
            assertTrue("no index covers $column (docs/05_DataModel.md 27)", column in indexed)
        }

        assertTrue("the index on sessionId must be unique", uniqueOnSessionId)
    }

    @Test
    fun theDirectionColumnStoresTheEnumName() = runBlocking {
        // The unread query filters on the literal 'RECEIVE', so the stored
        // representation is part of the contract rather than an implementation
        // detail of the converter.
        dao.save(record(sender = remote, receiver = local).toEntity())

        database.openHelper.readableDatabase
            .query("SELECT direction FROM communication_record").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(Direction.RECEIVE.name, cursor.getString(0))
            }
    }

    // --- search (Task40, docs/05_DataModel.md section 27.1) ---------------------------

    /** Wraps a term the way [RoomHistoryRepository] does, so the tests exercise the real one. */
    private suspend fun search(term: String): List<CommunicationRecordEntity> {
        val repository = RoomHistoryRepository(dao, logger)
        return repository.observeMatching(term).first().map { it.toEntity() }
    }

    @Test
    fun searchMatchesTheRemoteUserName() = runBlocking {
        dao.save(record(userName = "山田").toEntity())
        dao.save(record(userName = "田中").toEntity())

        assertEquals(listOf("山田"), search("山田").map { it.remoteUserName })
    }

    @Test
    fun searchMatchesPartOfAName() = runBlocking {
        dao.save(record(userName = "山田太郎").toEntity())

        assertEquals(1, search("田太").size)
    }

    @Test
    fun searchMatchesTheTranscript() = runBlocking {
        dao.save(
            record(userName = "山田")
                .copy(
                    transcript = "おはようございます",
                    transcriptStatus = TranscriptStatus.COMPLETED,
                )
                .toEntity()
        )
        dao.save(record(userName = "田中").toEntity())

        assertEquals(listOf("山田"), search("はよう").map { it.remoteUserName })
    }

    @Test
    fun searchWorksInEveryShippedScript() = runBlocking {
        // The five languages of section 15. Nothing is tokenised, which is the
        // reason LIKE was chosen over FTS -- so the only way this can break is
        // if something along the way mangles the encoding.
        val names = listOf("山田", "倉庫", "Warehouse", "ကျေးဇူး", "গুদাম")
        names.forEach { dao.save(record(userName = it, sessionId = it).toEntity()) }

        names.forEach { name ->
            assertEquals("searching for $name", 1, search(name).size)
        }
    }

    @Test
    fun aBlankSearchIsTheWholeHistory() = runBlocking {
        dao.save(record(userName = "山田").toEntity())
        dao.save(record(userName = "田中").toEntity())

        assertEquals(2, search("").size)
        assertEquals(2, search("   ").size)
    }

    @Test
    fun anUnderscoreInATermIsALiteralUnderscore() = runBlocking {
        // Unescaped, LIKE would read this as "any single character" and the
        // second row would match too.
        dao.save(record(userName = "a_b", sessionId = "u1").toEntity())
        dao.save(record(userName = "axb", sessionId = "u2").toEntity())

        assertEquals(listOf("a_b"), search("a_b").map { it.remoteUserName })
    }

    @Test
    fun aPercentInATermIsALiteralPercent() = runBlocking {
        dao.save(record(userName = "100%", sessionId = "p1").toEntity())
        dao.save(record(userName = "満充電", sessionId = "p2").toEntity())

        assertEquals(listOf("100%"), search("100%").map { it.remoteUserName })
    }

    @Test
    fun aBackslashInATermIsALiteralBackslash() = runBlocking {
        // The escape character itself. If it were not escaped first, escaping
        // the other two would consume it.
        dao.save(record(userName = """a\b""", sessionId = "b1").toEntity())
        dao.save(record(userName = "ab", sessionId = "b2").toEntity())

        assertEquals(1, search("""\""").size)
    }

    @Test
    fun searchResultsAreNewestFirst() = runBlocking {
        dao.save(record(userName = "山田", timestamp = 1_000, sessionId = "s1").toEntity())
        dao.save(record(userName = "山田", timestamp = 3_000, sessionId = "s2").toEntity())
        dao.save(record(userName = "山田", timestamp = 2_000, sessionId = "s3").toEntity())

        assertEquals(listOf(3_000L, 2_000L, 1_000L), search("山田").map { it.timestamp })
    }

    @Test
    fun aSearchThatMatchesNothingIsEmptyRatherThanEverything() = runBlocking {
        dao.save(record(userName = "山田").toEntity())

        assertTrue(search("存在しない名前").isEmpty())
    }

    // --- cleanup queries (Task40) -----------------------------------------------------

    @Test
    fun expiredReturnsOnlyRecordsOlderThanTheCutoff() = runBlocking {
        dao.save(record(timestamp = 1_000, sessionId = "old").toEntity())
        dao.save(record(timestamp = 5_000, sessionId = "new").toEntity())

        val expired = dao.expiredBefore(beforeMillis = 3_000, limit = 100)

        assertEquals(listOf(1_000L), expired.map { it.timestamp })
    }

    @Test
    fun expiredNeverReturnsAFavourite() = runBlocking {
        // docs/05_DataModel.md section 25. The rule lives in the query so no
        // caller can skip it, which is exactly why it is tested at this level.
        dao.save(record(timestamp = 1_000, sessionId = "kept").toEntity().copy(isFavorite = true))
        dao.save(record(timestamp = 1_000, sessionId = "ordinary").toEntity())

        val expired = dao.expiredBefore(beforeMillis = 3_000, limit = 100)

        assertEquals(listOf("ordinary"), expired.map { it.sessionId })
    }

    @Test
    fun expiredComesBackOldestFirst() = runBlocking {
        // Oldest first so a batched sweep makes progress from the far end.
        dao.save(record(timestamp = 3_000, sessionId = "c").toEntity())
        dao.save(record(timestamp = 1_000, sessionId = "a").toEntity())
        dao.save(record(timestamp = 2_000, sessionId = "b").toEntity())

        assertEquals(
            listOf("a", "b", "c"),
            dao.expiredBefore(beforeMillis = 9_000, limit = 100).map { it.sessionId },
        )
    }

    @Test
    fun expiredRespectsItsLimit() = runBlocking {
        repeat(5) { dao.save(record(timestamp = it + 1L, sessionId = "s$it").toEntity()) }

        assertEquals(2, dao.expiredBefore(beforeMillis = 9_000, limit = 2).size)
    }

    @Test
    fun favouritesCanBeListedForTheDeleteEverythingPath() = runBlocking {
        dao.save(record(sessionId = "kept").toEntity().copy(isFavorite = true))
        dao.save(record(sessionId = "ordinary").toEntity())

        assertEquals(listOf("kept"), dao.favorites(limit = 100).map { it.sessionId })
    }

    @Test
    fun audioPathsReturnsOnlyTheRowsThatHaveOne() = runBlocking {
        dao.save(
            record(sessionId = "with", audioPath = "records/2026/09/16/a.opus", audioFormat = AudioFormat.OPUS)
                .toEntity()
        )
        dao.save(record(sessionId = "without").toEntity())

        assertEquals(listOf("records/2026/09/16/a.opus"), dao.audioPaths())
    }

    @Test
    fun countsSeparateFavouritesFromTheTotal() = runBlocking {
        dao.save(record(sessionId = "a").toEntity().copy(isFavorite = true))
        dao.save(record(sessionId = "b").toEntity())
        dao.save(record(sessionId = "c").toEntity())

        assertEquals(3, dao.count())
        assertEquals(1, dao.favoriteCount())
    }

    // --- bulk delete (Task40) ---------------------------------------------------------

    @Test
    fun deletingASetRemovesExactlyThoseRows() = runBlocking {
        val ids = (1..5).map { index ->
            record(sessionId = "s$index").also { dao.save(it.toEntity()) }.id
        }

        assertEquals(3, dao.deleteByIds(ids.take(3)))
        assertEquals(2, dao.count())
    }

    @Test
    fun deletingIdsThatAreNotThereIsNotAnError() = runBlocking {
        val present = record(sessionId = "present").also { dao.save(it.toEntity()) }

        assertEquals(1, dao.deleteByIds(listOf(present.id, "ghost-1", "ghost-2")))
        assertEquals(0, dao.count())
    }

    @Test
    fun deletingAnEmptySetIsHarmless() = runBlocking {
        dao.save(record(sessionId = "present").toEntity())

        assertEquals(0, dao.deleteByIds(emptyList()))
        assertEquals(1, dao.count())
    }

    @Test
    fun deletingAWholeHistoryExceedsOneStatementsParameterLimit() = runBlocking {
        // SQLITE_MAX_VARIABLE_NUMBER is 999 on the SQLite versions this app's
        // minimum ships with, and "delete my whole history" is exactly the call
        // that would hit it. The repository chunks; this proves the chunking is
        // needed and that it works.
        val repository = RoomHistoryRepository(dao, logger)
        val ids = (1..1_200).map { index ->
            record(sessionId = "s$index", timestamp = index.toLong())
                .also { dao.save(it.toEntity()) }.id
        }

        val outcome = repository.deleteAll(ids)

        assertTrue(outcome is com.saikai.ptt.core.common.Outcome.Success)
        assertEquals(0, dao.count())
    }
}
