package com.saikai.ptt.core.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cutting arbitrary reads into whole frames.
 *
 * `AudioRecord.read` is allowed to return fewer bytes than it was asked for, and
 * an encoder fed a short frame produces a packet the far end cannot decode. The
 * resulting bug is nasty in a specific way: audio that is mostly fine with a
 * click wherever a read happened to be short, on somebody else's phone.
 */
class PcmFrameAssemblerTest {

    private val frameSize = 640
    private val assembler = PcmFrameAssembler(frameSize)

    private val emitted = mutableListOf<ByteArray>()
    private val collect: (ByteArray, Int) -> Unit = { frame, length ->
        emitted += frame.copyOf(length)
    }

    private fun ramp(size: Int, from: Int = 0) = ByteArray(size) { (from + it).toByte() }

    @Test
    fun `an exact frame comes straight through`() {
        val source = ramp(frameSize)

        assertEquals(1, assembler.accept(source, 0, frameSize, collect))

        assertArrayEquals(source, emitted.single())
        assertEquals(0, assembler.pending)
    }

    @Test
    fun `short reads are held until the frame is whole`() {
        val source = ramp(frameSize)

        // Three partial reads, as a device under load would produce.
        assertEquals(0, assembler.accept(source, 0, 100, collect))
        assertEquals(100, assembler.pending)
        assertEquals(0, assembler.accept(source, 100, 300, collect))
        assertEquals(400, assembler.pending)
        assertEquals(1, assembler.accept(source, 400, 240, collect))

        assertArrayEquals(source, emitted.single())
        assertEquals(0, assembler.pending)
    }

    @Test
    fun `a long read produces several frames and keeps the remainder`() {
        val source = ramp(frameSize * 3 + 17)

        assertEquals(3, assembler.accept(source, 0, source.size, collect))

        assertEquals(3, emitted.size)
        assertArrayEquals(source.copyOfRange(0, frameSize), emitted[0])
        assertArrayEquals(source.copyOfRange(frameSize, frameSize * 2), emitted[1])
        assertArrayEquals(source.copyOfRange(frameSize * 2, frameSize * 3), emitted[2])
        assertEquals(17, assembler.pending)
    }

    @Test
    fun `bytes keep their order across a frame boundary`() {
        // The failure this guards against is not "wrong count" but "right count,
        // shuffled contents", which sounds like noise rather than like silence.
        val source = ramp(frameSize + 10)
        assembler.accept(source, 0, source.size, collect)
        assembler.accept(ramp(frameSize - 10, from = frameSize + 10), 0, frameSize - 10, collect)

        assertEquals(2, emitted.size)
        val stream = emitted[0] + emitted[1]
        assertArrayEquals(ramp(frameSize * 2), stream)
    }

    @Test
    fun `the frame buffer is reused, not reallocated`() {
        // Fifty times a second for the length of a transmission; the contract is
        // that callers copy, and this is what makes that contract necessary.
        val seen = mutableListOf<ByteArray>()
        val source = ramp(frameSize * 2)
        assembler.accept(source, 0, source.size) { frame, _ -> seen += frame }

        assertEquals(2, seen.size)
        assertSame(seen[0], seen[1])
    }

    @Test
    fun `an empty read does nothing`() {
        assertEquals(0, assembler.accept(ramp(10), 0, 0, collect))
        assertEquals(0, assembler.pending)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `resetting discards a partial frame`() {
        assembler.accept(ramp(100), 0, 100, collect)
        assertEquals(100, assembler.pending)

        assembler.reset()

        assertEquals(0, assembler.pending)
        assertEquals(1, assembler.accept(ramp(frameSize), 0, frameSize, collect))
    }

    @Test
    fun `a range outside the source is a programming error, not a silent truncation`() {
        assertThrows(IllegalArgumentException::class.java) {
            assembler.accept(ramp(10), 0, 20, collect)
        }
        assertThrows(IllegalArgumentException::class.java) {
            assembler.accept(ramp(10), -1, 5, collect)
        }
    }

    @Test
    fun `a frame size must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { PcmFrameAssembler(0) }
    }
}
