package com.saikai.ptt.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the wraparound comparison required by `docs/ADR/ADR-003-Wire-Format.md`
 * section 5.
 *
 * The interesting case is the one a signed comparison gets exactly backwards:
 * after 0xFFFFFFFF comes 0, and as `Int` that is a jump from -1 to 0 in one
 * direction and from 2147483647-adjacent nonsense in the other.
 */
class SequenceNumbersTest {

    private val minusTwo = 0xFFFFFFFE.toInt()
    private val minusOne = 0xFFFFFFFF.toInt()

    @Test
    fun `the ADR sequence rules are the declared constants`() {
        assertEquals(0, SequenceNumbers.CONTROL)
        assertEquals(0, SequenceNumbers.VOICE_START)
        assertEquals(1, SequenceNumbers.FIRST_VOICE_DATA)
    }

    @Test
    fun `walks across the wraparound boundary`() {
        // 0xFFFFFFFE -> 0xFFFFFFFF -> 0x00000000 -> 0x00000001
        assertTrue(SequenceNumbers.isNewer(minusOne, minusTwo))
        assertTrue(SequenceNumbers.isNewer(0, minusOne))
        assertTrue(SequenceNumbers.isNewer(1, 0))

        assertFalse(SequenceNumbers.isNewer(minusTwo, minusOne))
        assertFalse(SequenceNumbers.isNewer(minusOne, 0))
        assertFalse(SequenceNumbers.isNewer(0, 1))
    }

    @Test
    fun `next wraps instead of overflowing`() {
        assertEquals(minusOne, SequenceNumbers.next(minusTwo))
        assertEquals(0, SequenceNumbers.next(minusOne))
        assertEquals(1, SequenceNumbers.next(0))
    }

    @Test
    fun `a naive signed comparison disagrees at the sign flip`() {
        // The wrap from 0xFFFFFFFF to 0 survives a signed comparison by accident:
        // as Int that is -1 to 0, which still reads as increasing. The crossing
        // that does not survive is the one at 0x80000000, where the Int sign flips
        // in the middle of an ordinary run of sequence numbers. That is the case
        // this function exists to get right.
        val before = 0x7FFFFFFF // 2147483647 unsigned and signed
        val after = 0x80000002.toInt() // three later on the wire, -2147483646 as Int

        assertTrue(SequenceNumbers.isNewer(after, before))
        assertFalse(after > before)

        // And the accidental agreement at the top of the range, for contrast.
        assertTrue(SequenceNumbers.isNewer(0, minusOne))
        assertTrue(0 > minusOne)
    }

    @Test
    fun `a number is not newer than itself`() {
        assertFalse(SequenceNumbers.isNewer(0, 0))
        assertFalse(SequenceNumbers.isNewer(minusOne, minusOne))
        assertFalse(SequenceNumbers.isNewer(12345, 12345))
    }

    @Test
    fun `ordinary ordering still works well away from the boundary`() {
        assertTrue(SequenceNumbers.isNewer(100, 1))
        assertFalse(SequenceNumbers.isNewer(1, 100))
    }

    @Test
    fun `numbers half the space apart have no defined order`() {
        val half = 0x80000000.toInt()
        assertFalse(SequenceNumbers.isNewer(half, 0))
        assertFalse(SequenceNumbers.isNewer(0, half))
    }

    @Test
    fun `voiceEnd is one past the last frame`() {
        assertEquals(251, SequenceNumbers.voiceEnd(250))
        assertEquals(0, SequenceNumbers.voiceEnd(minusOne))
    }

    @Test
    fun `unsigned view never goes negative`() {
        assertEquals(4_294_967_295L, SequenceNumbers.toUnsignedLong(minusOne))
        assertEquals(0L, SequenceNumbers.toUnsignedLong(0))
    }
}
