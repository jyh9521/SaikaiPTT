package com.saikai.ptt.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the packet type numbers to `docs/ADR/ADR-003-Wire-Format.md` section 3.
 *
 * Renumbering a type is a silent, unrecoverable break: the device that upgrades
 * first starts speaking a different protocol under the same version byte.
 */
class PacketTypeTest {

    @Test
    fun `codes match the ADR table`() {
        assertEquals(0x01, PacketType.DISCOVERY.code)
        assertEquals(0x02, PacketType.DISCOVERY_RESPONSE.code)
        assertEquals(0x10, PacketType.HEARTBEAT.code)
        assertEquals(0x11, PacketType.PING.code)
        assertEquals(0x12, PacketType.PONG.code)
        assertEquals(0x20, PacketType.VOICE_START.code)
        assertEquals(0x21, PacketType.VOICE_ACCEPT.code)
        assertEquals(0x22, PacketType.VOICE_DATA.code)
        assertEquals(0x23, PacketType.VOICE_END.code)
        assertEquals(0x24, PacketType.BUSY.code)
        assertEquals(0x25, PacketType.SESSION_TERMINATE.code)
        assertEquals(0x30, PacketType.FORCE_INTERRUPT.code)
        assertEquals(0x31, PacketType.ERROR.code)
        assertEquals(0x32, PacketType.GOODBYE.code)
        assertEquals(0x33, PacketType.CAPABILITIES.code)
    }

    @Test
    fun `v1 implements eleven types and reserves four`() {
        assertEquals(11, PacketType.implemented.size)
        assertEquals(4, PacketType.reserved.size)
        assertEquals(
            listOf(
                PacketType.FORCE_INTERRUPT,
                PacketType.ERROR,
                PacketType.GOODBYE,
                PacketType.CAPABILITIES,
            ),
            PacketType.reserved,
        )
    }

    @Test
    fun `no two types share a code`() {
        val codes = PacketType.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `every code fits in the single header byte`() {
        assertTrue(PacketType.entries.all { it.code in 0..0xFF })
    }

    @Test
    fun `fromCode round trips every declared type`() {
        for (type in PacketType.entries) {
            assertEquals(type, PacketType.fromCode(type.code))
        }
    }

    @Test
    fun `fromCode returns null for unknown and out-of-range codes`() {
        assertNull(PacketType.fromCode(0x00))
        assertNull(PacketType.fromCode(0x03))
        assertNull(PacketType.fromCode(0xFF))
        assertNull(PacketType.fromCode(-1))
        assertNull(PacketType.fromCode(256))
    }
}
