package com.saikai.ptt.core.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdTest {

    @Test
    fun `a generated id is a version 4 UUID`() {
        repeat(50) {
            val id = DeviceId.random()
            assertTrue("Not version 4: $id", id.isVersion4)
            assertFalse(id.isZero)
        }
    }

    @Test
    fun `generated ids differ`() {
        assertNotEquals(DeviceId.random(), DeviceId.random())
    }

    @Test
    fun `text form round trips`() {
        val id = DeviceId.random()
        assertEquals(id, DeviceId.parse(id.value))
    }

    @Test
    fun `binary form round trips`() {
        val id = DeviceId.random()
        val bytes = id.toBytes()
        assertEquals(16, bytes.size)
        assertEquals(id, DeviceId.fromBytes(bytes))
    }

    @Test
    fun `binary form is big-endian and exactly 16 bytes`() {
        // Pinned against a known value: the wire format is normative
        // (docs/ADR/ADR-003-Wire-Format.md) and a silent endianness change would
        // make two builds unable to talk to each other.
        val id = DeviceId.parse("00112233-4455-6677-8899-aabbccddeeff")!!
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                0x88.toByte(), 0x99.toByte(), 0xaa.toByte(), 0xbb.toByte(),
                0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte(),
            ),
            id.toBytes(),
        )
    }

    @Test
    fun `writeTo respects the offset and touches nothing else`() {
        // Packet encoding writes ids into a shared header buffer at fixed
        // offsets; spilling outside the range would corrupt neighbouring fields.
        val id = DeviceId.random()
        val buffer = ByteArray(32) { 0x7F }

        id.writeTo(buffer, offset = 8)

        assertEquals(id, DeviceId.fromBytes(buffer, offset = 8))
        (0 until 8).forEach { assertEquals("byte $it changed", 0x7F.toByte(), buffer[it]) }
        (24 until 32).forEach { assertEquals("byte $it changed", 0x7F.toByte(), buffer[it]) }
    }

    @Test
    fun `writeTo rejects a buffer that is too small`() {
        val id = DeviceId.random()
        listOf(ByteArray(15) to 0, ByteArray(20) to 8, ByteArray(16) to -1).forEach { (buf, off) ->
            try {
                id.writeTo(buf, off)
                throw AssertionError("Expected rejection for size ${buf.size} at offset $off")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `fromBytes returns null for a short range instead of throwing`() {
        assertNull(DeviceId.fromBytes(ByteArray(15)))
        assertNull(DeviceId.fromBytes(ByteArray(20), offset = 8))
        assertNull(DeviceId.fromBytes(ByteArray(16), offset = -1))
    }

    @Test
    fun `parse returns null for damaged input rather than throwing`() {
        // Stored data can be truncated by a process kill mid-write.
        listOf(
            null,
            "",
            "   ",
            "not-a-uuid",
            "00112233-4455-6677-8899",
            "00112233445566778899aabbccddeeff",
            "zz112233-4455-6677-8899-aabbccddeeff",
        ).forEach { assertNull("Should not parse: '$it'", DeviceId.parse(it)) }
    }

    @Test
    fun `parse rejects a shortened form that would not re-serialise identically`() {
        // UUID.fromString is lenient about short groups. Accepting "1-2-3-4-5"
        // would mean the stored text and the transmitted bytes disagree.
        assertNull(DeviceId.parse("1-2-3-4-5"))
    }

    @Test
    fun `parse is case insensitive and trims surrounding space`() {
        val id = DeviceId.parse("00112233-4455-6677-8899-AABBCCDDEEFF")
        assertEquals(DeviceId.parse(" 00112233-4455-6677-8899-aabbccddeeff "), id)
    }

    @Test
    fun `the zero id is recognised and is not a real device`() {
        // The protocol uses all-zero for "no target" on broadcast packets.
        assertTrue(DeviceId.ZERO.isZero)
        assertArrayEquals(ByteArray(16), DeviceId.ZERO.toBytes())
        assertEquals(DeviceId.ZERO, DeviceId.fromBytes(ByteArray(16)))
        assertFalse(DeviceId.random().isZero)
    }

    @Test
    fun `equality is by value so ids can key a peer table`() {
        val a = DeviceId.parse("00112233-4455-6677-8899-aabbccddeeff")!!
        val b = DeviceId.fromBytes(a.toBytes())!!
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(1, setOf(a, b).size)
    }
}
