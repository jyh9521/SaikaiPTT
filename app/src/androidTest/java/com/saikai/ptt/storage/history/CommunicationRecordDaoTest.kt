package com.saikai.ptt.storage.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saikai.ptt.core.domain.AudioFormat
import com.saikai.ptt.core.domain.CommunicationRecord
import com.saikai.ptt.core.domain.Direction
import com.saikai.ptt.core.domain.RecordStatus
import com.saikai.ptt.core.domain.TranscriptStatus
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
}
