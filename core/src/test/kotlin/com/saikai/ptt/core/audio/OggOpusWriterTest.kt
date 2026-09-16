package com.saikai.ptt.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The container, byte for byte.
 *
 * Every assertion here is about a file that some other program has to read, so
 * the test parses what was written rather than inspecting the writer's state --
 * a reader that disagrees with the writer is the only failure that matters, and
 * a test that shares the writer's assumptions cannot find one.
 */
class OggOpusWriterTest {

    private val sink = ByteArrayOutputStream()

    private fun writer(
        sampleRateHz: Int = 16_000,
        frameSizeSamples: Int = 320,
    ) = OggOpusWriter(sink, sampleRateHz, frameSizeSamples, serialNumber = 0x5341494B)

    /** A frame of plausible size, filled with a recognisable pattern. */
    private fun frame(size: Int = 48, fill: Int = 0xA5) = ByteArray(size) { fill.toByte() }

    // --- A minimal Ogg reader, written against RFC 3533 rather than the writer ---

    private class Page(
        val headerType: Int,
        val granule: Long,
        val serial: Int,
        val sequence: Int,
        val packets: List<ByteArray>,
        val crcValid: Boolean,
    ) {
        val isBeginning: Boolean get() = headerType and 0x02 != 0
        val isEnd: Boolean get() = headerType and 0x04 != 0
    }

    private fun parse(bytes: ByteArray): List<Page> {
        val pages = mutableListOf<Page>()
        var at = 0
        while (at < bytes.size) {
            assertEquals("OggS", String(bytes, at, 4, Charsets.US_ASCII))
            assertEquals("stream structure version", 0, bytes[at + 4].toInt())
            val headerType = bytes[at + 5].toInt() and 0xFF
            val granule = readLongLe(bytes, at + 6)
            val serial = readIntLe(bytes, at + 14)
            val sequence = readIntLe(bytes, at + 18)
            val storedCrc = readIntLe(bytes, at + 22)
            val segmentCount = bytes[at + 26].toInt() and 0xFF
            val table = IntArray(segmentCount) { bytes[at + 27 + it].toInt() and 0xFF }
            val payloadAt = at + 27 + segmentCount
            val payloadSize = table.sum()

            // Recompute over the page with the checksum field zeroed, which is
            // what a reader does.
            val page = bytes.copyOfRange(at, payloadAt + payloadSize)
            for (byte in 0 until 4) page[22 + byte] = 0
            val crcValid = OggOpusWriter.crc32(page) == storedCrc

            val packets = mutableListOf<ByteArray>()
            var cursor = payloadAt
            var current = ByteArrayOutputStream()
            table.forEach { lacing ->
                current.write(bytes, cursor, lacing)
                cursor += lacing
                if (lacing < 255) {
                    packets += current.toByteArray()
                    current = ByteArrayOutputStream()
                }
            }

            pages += Page(headerType, granule, serial, sequence, packets, crcValid)
            at = payloadAt + payloadSize
        }
        return pages
    }

    private fun readIntLe(bytes: ByteArray, at: Int): Int =
        (0 until 4).fold(0) { acc, byte -> acc or ((bytes[at + byte].toInt() and 0xFF) shl (8 * byte)) }

    private fun readLongLe(bytes: ByteArray, at: Int): Long =
        (0 until 8).fold(0L) { acc, byte ->
            acc or ((bytes[at + byte].toLong() and 0xFF) shl (8 * byte))
        }

    // --- The CRC, independently ------------------------------------------------------

    /**
     * Ogg's CRC is not zlib's.
     *
     * These three are the first entries of the standard table for polynomial
     * 0x04c11db7 applied MSB-first with no reflection. A table built with the
     * reflected algorithm -- what `java.util.zip.CRC32` uses -- produces
     * completely different values, and a file checksummed that way is rejected
     * by every Ogg reader while looking perfectly well formed.
     */
    @Test
    fun `the checksum is the non-reflected CRC-32`() {
        assertEquals(0x04c11db7, OggOpusWriter.crc32(byteArrayOf(0x01)))
        assertEquals(0x09823b6e, OggOpusWriter.crc32(byteArrayOf(0x02)))
        assertEquals(0x130476dc, OggOpusWriter.crc32(byteArrayOf(0x04)))
        assertEquals(0, OggOpusWriter.crc32(ByteArray(0)))
    }

    // --- Structure -------------------------------------------------------------------

    @Test
    fun `an empty recording is still a valid stream`() {
        writer().finish()

        val pages = parse(sink.toByteArray())
        assertEquals("OpusHead, OpusTags and a final page", 3, pages.size)
        assertTrue(pages.first().isBeginning)
        assertTrue(pages.last().isEnd)
        assertTrue(pages.all { it.crcValid })
    }

    @Test
    fun `the first page carries OpusHead alone and the beginning-of-stream flag`() {
        writer().finish()
        val head = parse(sink.toByteArray()).first()

        assertTrue(head.isBeginning)
        assertFalse(head.isEnd)
        assertEquals(1, head.packets.size)
        assertEquals(0L, head.granule)

        val opusHead = head.packets.single()
        assertEquals(19, opusHead.size)
        assertEquals("OpusHead", String(opusHead, 0, 8, Charsets.US_ASCII))
        assertEquals("version", 1, opusHead[8].toInt())
        assertEquals("mono", 1, opusHead[9].toInt())
        assertEquals(
            "pre-skip",
            OggOpusWriter.PRE_SKIP_SAMPLES,
            (opusHead[10].toInt() and 0xFF) or ((opusHead[11].toInt() and 0xFF) shl 8),
        )
        assertEquals("input rate", 16_000, readIntLe(opusHead, 12))
        assertEquals("output gain", 0, (opusHead[16].toInt() and 0xFF) or (opusHead[17].toInt() shl 8))
        assertEquals("mapping family", 0, opusHead[18].toInt())
    }

    @Test
    fun `the second page carries OpusTags alone`() {
        writer().finish()
        val tags = parse(sink.toByteArray())[1]

        assertFalse(tags.isBeginning)
        assertEquals(1, tags.packets.size)
        assertEquals("OpusTags", String(tags.packets.single(), 0, 8, Charsets.US_ASCII))
    }

    @Test
    fun `page sequence numbers start at zero and never skip`() {
        val target = writer()
        repeat(120) { target.write(frame(), 0, 48) }
        target.finish()

        parse(sink.toByteArray()).forEachIndexed { index, page ->
            assertEquals(index, page.sequence)
        }
    }

    @Test
    fun `every page carries the same serial number`() {
        val target = writer()
        repeat(60) { target.write(frame(), 0, 48) }
        target.finish()

        assertTrue(parse(sink.toByteArray()).all { it.serial == 0x5341494B })
    }

    // --- Payload ---------------------------------------------------------------------

    @Test
    fun `frames come back exactly as they went in, in order`() {
        val written = (1..130).map { index -> ByteArray(20 + index % 17) { index.toByte() } }

        val target = writer()
        written.forEach { target.write(it, 0, it.size) }
        target.finish()

        val readBack = parse(sink.toByteArray()).drop(2).flatMap { it.packets }
        assertEquals(written.size, readBack.size)
        written.zip(readBack).forEach { (expected, actual) -> assertArrayEquals(expected, actual) }
    }

    @Test
    fun `only the requested range of the caller's buffer is written`() {
        val buffer = ByteArray(100) { 0x11 }
        for (index in 30 until 40) buffer[index] = 0x22

        val target = writer()
        target.write(buffer, 30, 10)
        target.finish()

        val packet = parse(sink.toByteArray()).drop(2).flatMap { it.packets }.single()
        assertArrayEquals(ByteArray(10) { 0x22 }, packet)
    }

    @Test
    fun `the frame is copied, so a reused codec buffer cannot corrupt the page`() {
        val buffer = ByteArray(48) { 0x33 }

        val target = writer()
        target.write(buffer, 0, 48)
        // Exactly what the encoder does with its output buffer on the next
        // frame, before the page has been flushed.
        buffer.fill(0x77)
        target.finish()

        val packet = parse(sink.toByteArray()).drop(2).flatMap { it.packets }.single()
        assertArrayEquals(ByteArray(48) { 0x33 }, packet)
    }

    // --- Lacing ----------------------------------------------------------------------

    /**
     * A packet whose length is a multiple of 255 needs a terminating zero.
     *
     * Without it a reader takes the packet as continuing onto the next page and
     * glues it to whatever follows. Our frames are around fifty bytes so this
     * never happens in practice, which is exactly why it would be wrong forever
     * if it were not pinned here.
     */
    @Test
    fun `a packet of exactly 255 bytes is laced as 255 then 0`() {
        val target = writer()
        target.write(ByteArray(255) { 0x5A }, 0, 255)
        target.finish()

        val packets = parse(sink.toByteArray()).drop(2).flatMap { it.packets }
        assertEquals(1, packets.size)
        assertEquals(255, packets.single().size)
    }

    @Test
    fun `a packet longer than 255 bytes spans several segments`() {
        val long = ByteArray(600) { (it % 251).toByte() }

        val target = writer()
        target.write(long, 0, 600)
        target.finish()

        assertArrayEquals(long, parse(sink.toByteArray()).drop(2).flatMap { it.packets }.single())
    }

    @Test
    fun `a page never exceeds the 255 segment limit`() {
        val target = writer()
        // 200-byte frames: one segment each, so the frame cap is what bounds
        // the page. 600 of them is well past both limits.
        repeat(600) { target.write(ByteArray(200), 0, 200) }
        target.finish()

        parse(sink.toByteArray()).forEach { page ->
            val segments = page.packets.sumOf { it.size / 255 + 1 }
            assertTrue("a page held $segments segments", segments <= 255)
        }
    }

    // --- Granule ---------------------------------------------------------------------

    /**
     * Granules count 48 kHz samples, whatever rate the audio was encoded at.
     *
     * RFC 7845 section 4: a 20 ms frame is 960 of them, from 16 kHz audio just
     * as from 48 kHz. Counting the encoder's own 320 samples instead would make
     * every player report a recording three times shorter than it is.
     */
    @Test
    fun `the final granule counts 48 kHz samples`() {
        val target = writer(sampleRateHz = 16_000, frameSizeSamples = 320)
        repeat(50) { target.write(frame(), 0, 48) }
        target.finish()

        // One second of 20 ms frames.
        assertEquals(48_000L, parse(sink.toByteArray()).last().granule)
    }

    @Test
    fun `granules increase monotonically across audio pages`() {
        val target = writer()
        repeat(130) { target.write(frame(), 0, 48) }
        target.finish()

        val granules = parse(sink.toByteArray()).drop(2).map { it.granule }
        assertEquals(granules.sortedBy { it }, granules)
        assertTrue(granules.first() > 0)
    }

    @Test
    fun `the header pages carry a zero granule`() {
        val target = writer()
        target.write(frame(), 0, 48)
        target.finish()

        assertEquals(0L, parse(sink.toByteArray())[0].granule)
        assertEquals(0L, parse(sink.toByteArray())[1].granule)
    }

    @Test
    fun `frames are counted for the duration the record stores`() {
        val target = writer()
        repeat(37) { target.write(frame(), 0, 48) }
        assertEquals(37L, target.frameCount)
        target.finish()
        assertEquals(37L, target.frameCount)
    }

    // --- Lifecycle -------------------------------------------------------------------

    @Test
    fun `finishing twice writes nothing more`() {
        val target = writer()
        target.write(frame(), 0, 48)
        target.finish()
        val afterFirst = sink.size()

        target.finish()

        assertEquals(afterFirst, sink.size())
    }

    @Test
    fun `writing after finishing is refused`() {
        val target = writer()
        target.finish()

        assertThrows(IllegalStateException::class.java) { target.write(frame(), 0, 48) }
    }

    @Test
    fun `a frame outside the buffer is refused`() {
        val target = writer()

        assertThrows(IllegalArgumentException::class.java) { target.write(ByteArray(10), 5, 10) }
        assertThrows(IllegalArgumentException::class.java) { target.write(ByteArray(10), -1, 4) }
        assertThrows(IllegalArgumentException::class.java) { target.write(ByteArray(10), 0, 0) }
    }

    /**
     * A file that stops mid-stream is a shorter file, not a broken one.
     *
     * This is the property that lets recordings be written straight through
     * instead of being assembled at the end: a process killed mid-call, or a
     * disk that fills, leaves pages that a player reads up to the point where
     * they stop.
     */
    @Test
    fun `pages written before an abrupt stop are complete and checksummed`() {
        val target = writer()
        repeat(130) { target.write(frame(), 0, 48) }
        // No finish(): the process went away.

        val pages = parse(sink.toByteArray())
        assertTrue("some audio should already be on disk", pages.size > 2)
        assertTrue(pages.all { it.crcValid })
        assertFalse("no end-of-stream flag was written", pages.any { it.isEnd })
    }
}
