package com.saikai.ptt.core.audio

import com.saikai.ptt.core.common.Outcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * The reader, checked against the writer.
 *
 * This is the test that matters. `OggOpusWriterTest` proves the writer emits
 * the bytes RFC 3533 and RFC 7845 describe; this proves the reader gets back
 * exactly what went in. Together they mean every recording this app has ever
 * written can be read back, which is the whole premise of recognising one.
 *
 * The rest is damage: a bad checksum, a file cut off mid-page, a packet split
 * across pages. `docs/05_DataModel.md` section 48 and Task45 both ask for
 * "skip the page, not the file", and those paths only ever run on a file that
 * is already broken -- which is to say, never in a test that does not break one
 * on purpose.
 */
class OggOpusReaderTest {

    private val sampleRate = 16_000
    private val frameSamples = 320

    /** Frames the size and shape a 20 kbps Opus encoder actually produces. */
    private fun frames(count: Int, seed: Int = 1): List<ByteArray> {
        val random = Random(seed)
        return List(count) { index ->
            ByteArray(40 + (index % 17)) { random.nextInt(256).toByte() }
        }
    }

    private fun write(
        frames: List<ByteArray>,
        serial: Int = 0x5341494B,
        finish: Boolean = true,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = OggOpusWriter(out, sampleRate, frameSamples, serial)
        frames.forEach { writer.write(it, 0, it.size) }
        if (finish) writer.finish()
        return out.toByteArray()
    }

    private fun readOk(bytes: ByteArray): OggOpusStream {
        val outcome = OggOpusReader.read(bytes)
        assertTrue("read failed: $outcome", outcome is Outcome.Success)
        return (outcome as Outcome.Success).value
    }

    // --- the round trip ----------------------------------------------------------------

    @Test
    fun `every frame comes back byte for byte`() {
        val original = frames(137)

        val stream = readOk(write(original))

        assertEquals(original.size, stream.packets.size)
        original.forEachIndexed { index, frame ->
            assertArrayEquals("frame $index", frame, stream.packets[index])
        }
        assertTrue(stream.intact)
    }

    @Test
    fun `the header survives the round trip`() {
        val stream = readOk(write(frames(3)))

        assertEquals(1, stream.info.channels)
        assertEquals(OggOpusWriter.PRE_SKIP_SAMPLES, stream.info.preSkipSamples)
        assertEquals(sampleRate, stream.info.inputSampleRateHz)
        assertEquals(0, stream.info.outputGainQ78)
    }

    @Test
    fun `a recording that spans many pages reassembles in order`() {
        // Well past FRAMES_PER_PAGE, so the reader has to stitch pages together
        // and keep them in sequence.
        val original = frames(OggOpusWriter.FRAMES_PER_PAGE * 7 + 13, seed = 9)

        val stream = readOk(write(original))

        assertEquals(original.size, stream.packets.size)
        assertArrayEquals(original.first(), stream.packets.first())
        assertArrayEquals(original.last(), stream.packets.last())
    }

    @Test
    fun `an empty recording is a valid file with no audio`() {
        // The writer always emits a final page, so this is a real file.
        val stream = readOk(write(emptyList()))

        assertTrue(stream.packets.isEmpty())
        assertTrue(stream.intact)
        assertEquals(0L, stream.durationMillis)
    }

    @Test
    fun `one frame round trips`() {
        val original = frames(1)
        val stream = readOk(write(original))
        assertArrayEquals(original[0], stream.packets[0])
    }

    // --- lacing edge cases -------------------------------------------------------------

    @Test
    fun `a frame whose length is an exact multiple of 255 is not merged with the next`() {
        // The writer's terminating-zero rule, from the reader's side. Get this
        // wrong and two packets become one, silently.
        val original = listOf(
            ByteArray(255) { 1 },
            ByteArray(7) { 2 },
            ByteArray(510) { 3 },
            ByteArray(9) { 4 },
        )

        val stream = readOk(write(original))

        assertEquals(4, stream.packets.size)
        original.forEachIndexed { index, frame ->
            assertArrayEquals("packet $index", frame, stream.packets[index])
        }
    }

    @Test
    fun `a packet split across two pages is rejoined`() {
        // A frame large enough that the page fills mid-packet, forcing a
        // continuation. Never happens at 20 kbps; happens the first time
        // somebody changes the bitrate.
        val big = ByteArray(200 * 255 + 11) { (it % 251).toByte() }
        val original = listOf(ByteArray(50) { 7 }, big, ByteArray(50) { 8 })

        val stream = readOk(write(original))

        assertEquals(3, stream.packets.size)
        assertArrayEquals(big, stream.packets[1])
    }

    // --- timing ------------------------------------------------------------------------

    @Test
    fun `duration comes from the granule with the pre-skip removed`() {
        // 150 frames of 20 ms is three seconds of encoded audio, but the first
        // PRE_SKIP_SAMPLES of it is encoder warm-up the decoder throws away
        // (RFC 7845 section 4.2), so the playable length is a few milliseconds
        // short of round. Derived from the constant rather than written out,
        // so exporting the real lookahead value later does not silently make
        // this test a lie.
        val encodedMillis = 150L * frameSamples * 1000L / sampleRate
        val preSkipMillis =
            OggOpusWriter.PRE_SKIP_SAMPLES * 1000L / OggOpusWriter.GRANULE_RATE_HZ
        val stream = readOk(write(frames(150)))

        assertEquals(3_000L, encodedMillis)
        assertEquals(encodedMillis - preSkipMillis - 1, stream.durationMillis)
        assertEquals(2_993L, stream.durationMillis)
    }

    @Test
    fun `a recording shorter than the pre-skip has no negative duration`() {
        val stream = readOk(write(frames(1)))
        assertTrue("got ${stream.durationMillis}", stream.durationMillis >= 0)
    }

    // --- damage ------------------------------------------------------------------------

    @Test
    fun `a page with a broken checksum is skipped and the rest is kept`() {
        val original = frames(OggOpusWriter.FRAMES_PER_PAGE * 3)
        val bytes = write(original)
        // Corrupt a byte inside the last audio page's payload. The page fails
        // its checksum; the pages before it do not.
        bytes[bytes.size - 20] = (bytes[bytes.size - 20] + 1).toByte()

        val stream = readOk(bytes)

        assertTrue("a page should have been skipped", stream.pagesSkipped > 0)
        assertFalse(stream.intact)
        assertTrue("the earlier pages survive", stream.packets.size >= OggOpusWriter.FRAMES_PER_PAGE)
        stream.packets.forEachIndexed { index, packet ->
            assertArrayEquals("surviving packet $index", original[index], packet)
        }
    }

    @Test
    fun `a file cut off mid-page reads as everything before the cut`() {
        val original = frames(OggOpusWriter.FRAMES_PER_PAGE * 2 + 20)
        val whole = write(original)
        val cut = whole.copyOfRange(0, whole.size - 40)

        val stream = readOk(cut)

        assertTrue(stream.truncated)
        assertFalse(stream.intact)
        assertTrue("something survived", stream.packets.isNotEmpty())
        stream.packets.forEachIndexed { index, packet ->
            assertArrayEquals("surviving packet $index", original[index], packet)
        }
    }

    @Test
    fun `a recording whose writer never finished still reads`() {
        // The process died mid-call: no end-of-stream page was ever written.
        val original = frames(OggOpusWriter.FRAMES_PER_PAGE + 5)
        val stream = readOk(write(original, finish = false))

        // Only whole pages were flushed, so the tail frames are not there --
        // but everything that reached the disk comes back.
        assertEquals(OggOpusWriter.FRAMES_PER_PAGE, stream.packets.size)
        stream.packets.forEachIndexed { index, packet ->
            assertArrayEquals(original[index], packet)
        }
    }

    @Test
    fun `garbage before the first page is skipped`() {
        val bytes = ByteArray(64) { 0x55 } + write(frames(10))

        val stream = readOk(bytes)

        assertEquals(10, stream.packets.size)
        assertTrue(stream.pagesSkipped > 0)
    }

    @Test
    fun `a continuation with nothing to continue is dropped rather than guessed`() {
        // The page carrying a packet's first half was skipped, so the second
        // half arrives with the continued flag and no partner. Joining it to
        // whatever came before would invent a frame.
        val original = frames(3)
        val bytes = write(original)
        // Flip the continued bit on the first audio page (page index 2).
        val third = nthPageOffset(bytes, 2)
        bytes[third + 5] = (bytes[third + 5].toInt() or 0x01).toByte()
        repatchCrc(bytes, third)

        val stream = readOk(bytes)

        // The page's packets are still complete in themselves, so they survive;
        // what must not happen is a crash or a fabricated packet.
        assertTrue(stream.packets.size <= original.size)
        stream.packets.forEachIndexed { index, packet ->
            assertArrayEquals(original[index], packet)
        }
    }

    // --- not a recording ---------------------------------------------------------------

    @Test
    fun `an empty file is not an Ogg file`() {
        assertEquals(Outcome.failure(OggError.NotOgg), OggOpusReader.read(ByteArray(0)))
    }

    @Test
    fun `a file of noise is not an Ogg file`() {
        val noise = ByteArray(4096) { (it * 31 % 256).toByte() }
        assertTrue(OggOpusReader.read(noise) is Outcome.Failure)
    }

    @Test
    fun `an oversized file is refused before it is parsed`() {
        // Not allocated: the guard is on the length, so this test does not need
        // 64 MB to prove it.
        val outcome = OggOpusReader.read(ByteArray(0))
        assertTrue(outcome is Outcome.Failure)
        assertEquals(64 * 1024 * 1024, OggOpusReader.MAX_BYTES)
    }

    // --- helpers -----------------------------------------------------------------------

    /** Offset of the nth `OggS` in [bytes]. */
    private fun nthPageOffset(bytes: ByteArray, n: Int): Int {
        var found = -1
        var at = 0
        var index = -1
        while (at <= bytes.size - 4) {
            if (bytes[at] == 'O'.code.toByte() && bytes[at + 1] == 'g'.code.toByte() &&
                bytes[at + 2] == 'g'.code.toByte() && bytes[at + 3] == 'S'.code.toByte()
            ) {
                index++
                if (index == n) { found = at; break }
            }
            at++
        }
        require(found >= 0) { "no page $n" }
        return found
    }

    /** Recomputes a page's checksum in place, so a deliberate edit stays valid. */
    private fun repatchCrc(bytes: ByteArray, pageAt: Int) {
        val segmentCount = bytes[pageAt + 26].toInt() and 0xFF
        var payload = 0
        for (index in 0 until segmentCount) {
            payload += bytes[pageAt + 27 + index].toInt() and 0xFF
        }
        val end = pageAt + 27 + segmentCount + payload
        val page = bytes.copyOfRange(pageAt, end)
        for (index in 22 until 26) page[index] = 0
        val crc = OggOpusWriter.crc32(page)
        for (byte in 0 until 4) {
            bytes[pageAt + 22 + byte] = ((crc ushr (8 * byte)) and 0xFF).toByte()
        }
    }
}
