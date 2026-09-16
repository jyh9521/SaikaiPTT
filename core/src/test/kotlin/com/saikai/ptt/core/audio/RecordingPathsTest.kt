package com.saikai.ptt.core.audio

import com.saikai.ptt.core.domain.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** The layout `docs/05_DataModel.md` section 20 specifies, without a filesystem. */
class RecordingPathsTest {

    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val id = "e4b7c1a2-0000-4000-8000-000000000001"

    @Test
    fun `a finished recording is filed under its date`() {
        // 2026-09-16 09:00 in Tokyo.
        val millis = 1_789_524_000_000L

        assertEquals(
            "records/2026/09/16/$id.opus",
            RecordingPaths.finished(id, AudioFormat.OPUS, millis, tokyo),
        )
    }

    @Test
    fun `the date is the device's own, not UTC`() {
        // 2026-09-16 00:30 in Tokyo is still 2026-09-15 in UTC. The directory
        // is for a person reading the files, so it follows the device; the
        // record's timestamp stays UTC and is what anything sorts by.
        val millis = 1_789_493_400_000L

        assertTrue(
            RecordingPaths.finished(id, AudioFormat.OPUS, millis, tokyo)
                .startsWith("records/2026/09/16/"),
        )
        assertTrue(
            RecordingPaths.finished(id, AudioFormat.OPUS, millis, ZoneId.of("UTC"))
                .startsWith("records/2026/09/15/"),
        )
    }

    @Test
    fun `months and days are zero padded`() {
        // 2026-01-05.
        val millis = 1_767_571_200_000L
        val path = RecordingPaths.finished(id, AudioFormat.OPUS, millis, ZoneId.of("UTC"))

        assertEquals("records/2026/01/05/$id.opus", path)
    }

    @Test
    fun `the extension follows the format`() {
        val millis = 1_789_524_000_000L

        assertTrue(RecordingPaths.finished(id, AudioFormat.OPUS, millis, tokyo).endsWith(".opus"))
        assertTrue(RecordingPaths.finished(id, AudioFormat.AAC, millis, tokyo).endsWith(".m4a"))
    }

    @Test
    fun `a recording in progress lives under the temporary directory`() {
        assertEquals("records/.tmp/$id.opus", RecordingPaths.temporary(id, AudioFormat.OPUS))
    }

    /**
     * Cleanup tells the two apart by path alone.
     *
     * `docs/05_DataModel.md` section 32: a file being recorded must not be
     * deleted, and nothing in the database points at one, so the path is the
     * only thing cleanup has to go on.
     */
    @Test
    fun `temporary and finished paths are distinguishable`() {
        val temporary = RecordingPaths.temporary(id, AudioFormat.OPUS)
        val finished = RecordingPaths.finished(id, AudioFormat.OPUS, 1_789_524_000_000L, tokyo)

        assertTrue(RecordingPaths.isTemporary(temporary))
        assertFalse(RecordingPaths.isTemporary(finished))
        // Not fooled by a dated directory that merely mentions the name.
        assertFalse(RecordingPaths.isTemporary("records/2026/09/16/.tmp.opus"))
    }

    @Test
    fun `no path is absolute or escapes the records directory`() {
        val paths = listOf(
            RecordingPaths.temporary(id, AudioFormat.OPUS),
            RecordingPaths.finished(id, AudioFormat.OPUS, 1_789_524_000_000L, tokyo),
        )

        paths.forEach { path ->
            assertFalse("$path must be relative", path.startsWith("/"))
            assertFalse("$path must not contain ..", path.contains(".."))
            assertTrue("$path must be under records/", path.startsWith("records/"))
        }
    }

    @Test
    fun `the directory part is everything before the file name`() {
        val path = RecordingPaths.finished(id, AudioFormat.OPUS, 1_789_524_000_000L, tokyo)

        assertEquals("records/2026/09/16", RecordingPaths.directoryOf(path))
        assertEquals("records/.tmp", RecordingPaths.directoryOf(RecordingPaths.temporary(id, AudioFormat.OPUS)))
    }
}
