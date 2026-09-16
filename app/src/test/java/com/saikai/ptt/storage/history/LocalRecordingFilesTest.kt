package com.saikai.ptt.storage.history

import com.saikai.ptt.core.audio.RecordingPaths
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.LogLevel
import com.saikai.ptt.core.logger.LogSink
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.core.config.LoggingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The recordings directory as cleanup sees it, against a real file system.
 *
 * Task40 tested the cleanup *ordering* with an in-memory stand-in; this is the
 * other half, and the half that can be wrong in ways an interface cannot show:
 * path arithmetic, a delete that walks out of the directory it was given, an
 * empty directory left behind every day forever.
 *
 * A JVM test rather than an instrumented one, because this class touches
 * `java.io.File` and nothing else -- no Context, no Room, no Android at all.
 * `docs/07_TestPlan.md` section 3 puts a test at the cheapest layer that can
 * still fail honestly.
 */
class LocalRecordingFilesTest {

    private val sink = object : LogSink {
        override fun write(
            level: LogLevel,
            category: LogCategory,
            message: String,
            throwable: Throwable?,
        ) = Unit
    }
    private val logger = Logger(LoggingConfig.debug(), sink)

    private lateinit var temp: File
    private lateinit var filesDir: File
    private lateinit var files: LocalRecordingFiles

    @Before
    fun setUp() {
        temp = Files.createTempDirectory("saikai-files").toFile()
        filesDir = File(temp, "files").apply { mkdirs() }
        files = LocalRecordingFiles(filesDir, logger)
    }

    @After
    fun tearDown() {
        temp.deleteRecursively()
    }

    /** Creates `<filesDir>/<relative>` with [bytes] bytes and returns it. */
    private fun write(relative: String, bytes: Int = 16): File {
        val file = File(filesDir, relative)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes))
        return file
    }

    // --- listing ---------------------------------------------------------------------

    @Test
    fun `an app that has never recorded lists nothing rather than failing`() {
        // The records directory does not exist yet. A walk of a missing
        // directory must be an empty list, not an exception that ends the
        // cleanup pass before it starts.
        assertEquals(emptyList<String>(), files.list())
    }

    @Test
    fun `recordings come back as the relative paths the database stores`() {
        write("records/2026/09/16/a.opus")
        write("records/2026/09/15/b.opus")
        write("${RecordingPaths.TEMP_DIRECTORY}/c.opus")

        assertEquals(
            listOf(
                "records/.tmp/c.opus",
                "records/2026/09/15/b.opus",
                "records/2026/09/16/a.opus",
            ),
            files.list().sorted(),
        )
    }

    @Test
    fun `nothing outside the recordings root is listed`() {
        write("records/2026/09/16/a.opus")
        write("datastore/settings.preferences_pb")
        write("databases/saikai.db")

        assertEquals(listOf("records/2026/09/16/a.opus"), files.list())
    }

    @Test
    fun `directories are not listed as recordings`() {
        File(filesDir, "records/2026/09/16").mkdirs()

        assertEquals(emptyList<String>(), files.list())
    }

    // --- metadata --------------------------------------------------------------------

    @Test
    fun `a missing file has no modification time`() {
        assertNull(files.lastModified("records/2026/09/16/ghost.opus"))
    }

    @Test
    fun `an existing file reports a plausible modification time`() {
        val before = System.currentTimeMillis() - 5_000
        write("records/2026/09/16/a.opus")

        val stamp = files.lastModified("records/2026/09/16/a.opus")
        assertTrue("got $stamp", stamp != null && stamp >= before)
    }

    @Test
    fun `size is the file's own and zero for one that is gone`() {
        write("records/2026/09/16/a.opus", bytes = 1_234)

        assertEquals(1_234L, files.sizeOf("records/2026/09/16/a.opus"))
        assertEquals(0L, files.sizeOf("records/2026/09/16/ghost.opus"))
    }

    @Test
    fun `total bytes adds up everything under the recordings root and nothing else`() {
        write("records/2026/09/16/a.opus", bytes = 1_000)
        write("records/2026/09/15/b.opus", bytes = 2_000)
        write("${RecordingPaths.TEMP_DIRECTORY}/c.opus", bytes = 500)
        // Not a recording: the database itself is much larger than the audio on
        // a device that has talked a lot, and counting it would make the
        // storage figure a lie.
        write("databases/saikai.db", bytes = 999_999)

        assertEquals(3_500L, files.totalBytes())
    }

    @Test
    fun `an app that has never recorded reports no storage used`() {
        assertEquals(0L, files.totalBytes())
    }

    // --- deletion --------------------------------------------------------------------

    @Test
    fun `deleting removes the file and says so`() {
        val file = write("records/2026/09/16/a.opus")

        assertTrue(files.delete("records/2026/09/16/a.opus"))
        assertFalse(file.exists())
    }

    @Test
    fun `deleting something already gone reports false rather than throwing`() {
        assertFalse(files.delete("records/2026/09/16/ghost.opus"))
    }

    @Test
    fun `a path that climbs out of the recordings directory is refused`() {
        val settings = write("datastore/settings.preferences_pb")

        // A bug upstream -- a path built from something a peer sent, a botched
        // migration -- must not cost the user their settings. The check is
        // canonical, so this is the whole class of it.
        assertFalse(files.delete("records/../datastore/settings.preferences_pb"))
        assertTrue("the settings survived", settings.exists())
    }

    @Test
    fun `an absolute path outside the directory is refused too`() {
        val outside = File(temp, "outside.opus").apply { writeBytes(ByteArray(8)) }

        assertFalse(files.delete(outside.absolutePath))
        assertTrue(outside.exists())
    }

    // --- pruning ---------------------------------------------------------------------

    @Test
    fun `emptied dated directories are removed`() {
        write("records/2026/09/16/a.opus")
        files.delete("records/2026/09/16/a.opus")

        files.pruneEmptyDirectories()

        assertFalse(File(filesDir, "records/2026/09/16").exists())
        assertFalse(File(filesDir, "records/2026/09").exists())
        assertFalse(File(filesDir, "records/2026").exists())
    }

    @Test
    fun `a directory that still holds a recording is kept`() {
        write("records/2026/09/16/a.opus")
        write("records/2026/09/15/b.opus")
        files.delete("records/2026/09/15/b.opus")

        files.pruneEmptyDirectories()

        assertTrue(File(filesDir, "records/2026/09/16").isDirectory)
        assertFalse(File(filesDir, "records/2026/09/15").exists())
    }

    @Test
    fun `the root and the temporary directory are never pruned`() {
        File(filesDir, RecordingPaths.TEMP_DIRECTORY).mkdirs()

        files.pruneEmptyDirectories()

        // The recorder opens its next file inside these and expects them to be
        // there. Removing them because they happen to be empty between calls
        // would make the next recording depend on mkdirs succeeding.
        assertTrue(File(filesDir, RecordingPaths.ROOT).isDirectory)
        assertTrue(File(filesDir, RecordingPaths.TEMP_DIRECTORY).isDirectory)
    }

    @Test
    fun `pruning an app that has never recorded is harmless`() {
        files.pruneEmptyDirectories()
        assertFalse(File(filesDir, RecordingPaths.ROOT).exists())
    }
}
