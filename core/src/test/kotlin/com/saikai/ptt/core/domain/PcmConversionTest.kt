package com.saikai.ptt.core.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Byte order, in the one place it is decided.
 *
 * The reason this has its own test is that getting it wrong does not throw and
 * does not fail a round trip: bytes to shorts and back is symmetric even when
 * both halves are wrong the same way. It fails on the *device*, as audio that is
 * loud, wrong and vaguely rhythmic -- and it would sound identical on both
 * phones, because both would be wrong together. So the tests assert against
 * literal byte patterns, not against each other.
 */
class PcmConversionTest {

    @Test
    fun `bytes are read little-endian`() {
        // 0x0102 arrives as 02 01, not 01 02.
        val bytes = byteArrayOf(0x02, 0x01, 0x00.toByte(), 0x80.toByte())
        val samples = ShortArray(2)

        assertEquals(2, PcmConversion.bytesToShorts(bytes, 0, 4, samples, 0))

        assertEquals(0x0102.toShort(), samples[0])
        assertEquals(Short.MIN_VALUE, samples[1])
    }

    @Test
    fun `shorts are written little-endian`() {
        val samples = shortArrayOf(0x0102, Short.MIN_VALUE, Short.MAX_VALUE, -1)
        val bytes = ByteArray(8)

        assertEquals(8, PcmConversion.shortsToBytes(samples, 0, 4, bytes, 0))

        assertArrayEquals(
            byteArrayOf(
                0x02, 0x01,
                0x00, 0x80.toByte(),
                0xFF.toByte(), 0x7F,
                0xFF.toByte(), 0xFF.toByte(),
            ),
            bytes,
        )
    }

    @Test
    fun `the extremes survive a round trip`() {
        val samples = shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE)
        val bytes = ByteArray(samples.size * 2)
        val back = ShortArray(samples.size)

        PcmConversion.shortsToBytes(samples, 0, samples.size, bytes, 0)
        PcmConversion.bytesToShorts(bytes, 0, bytes.size, back, 0)

        assertArrayEquals(samples, back)
    }

    @Test
    fun `offsets are honoured in both buffers`() {
        val bytes = ByteArray(10) { 0x7F }
        // Two samples' worth, written four bytes in.
        PcmConversion.shortsToBytes(shortArrayOf(0, 0, 0x0102, 0x0304), 2, 2, bytes, 4)

        assertArrayEquals(
            byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F, 0x02, 0x01, 0x04, 0x03, 0x7F, 0x7F),
            bytes,
        )

        val samples = ShortArray(4) { 9 }
        assertEquals(2, PcmConversion.bytesToShorts(bytes, 4, 4, samples, 1))
        assertArrayEquals(shortArrayOf(9, 0x0102, 0x0304, 9), samples)
    }

    @Test
    fun `an odd byte count converts whole samples and leaves the rest`() {
        val samples = ShortArray(2)
        assertEquals(1, PcmConversion.bytesToShorts(byteArrayOf(0x02, 0x01, 0x03), 0, 3, samples, 0))
        assertEquals(0x0102.toShort(), samples[0])
        assertEquals(0.toShort(), samples[1])
    }

    @Test
    fun `a range outside a buffer is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) {
            PcmConversion.bytesToShorts(ByteArray(4), 0, 8, ShortArray(4), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PcmConversion.bytesToShorts(ByteArray(4), 0, 4, ShortArray(1), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PcmConversion.shortsToBytes(ShortArray(2), 0, 2, ByteArray(3), 0)
        }
    }
}
