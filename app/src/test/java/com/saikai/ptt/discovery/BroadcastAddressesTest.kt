package com.saikai.ptt.discovery

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * The `address | ~mask` computation `docs/ADR/ADR-001` asks for.
 *
 * Worth its own test because it is arithmetic that is easy to get subtly wrong
 * and impossible to notice: a broadcast address that is off by one bit is not an
 * error, it is a datagram nobody receives, on a network that looks fine.
 */
class BroadcastAddressesTest {

    private fun ip(text: String): ByteArray = InetAddress.getByName(text).address

    private fun broadcast(text: String, prefix: Int): String? =
        BroadcastAddresses.directedBroadcast(ip(text), prefix)
            ?.let { InetAddress.getByAddress(it).hostAddress }

    @Test
    fun `the common home network`() {
        assertEquals4("192.168.1.255", broadcast("192.168.1.42", 24))
    }

    @Test
    fun `a mask that does not land on a byte boundary`() {
        // /20 leaves the top four bits of the third byte, so the broadcast keeps
        // 192.168.0001____ and fills the rest.
        assertEquals4("192.168.15.255", broadcast("192.168.1.42", 20))
        assertEquals4("10.0.7.255", broadcast("10.0.4.9", 21))
        assertEquals4("172.16.255.255", broadcast("172.16.3.9", 16))
    }

    @Test
    fun `a mask inside the first byte`() {
        assertEquals4("10.255.255.255", broadcast("10.1.2.3", 8))
        assertEquals4("127.255.255.255", broadcast("127.0.0.1", 8))
    }

    @Test
    fun `the narrowest subnet that still has a broadcast address`() {
        assertEquals4("192.168.1.43", broadcast("192.168.1.42", 30))
    }

    @Test
    fun `a point-to-point link or a host route has none`() {
        // /31 has two hosts and no broadcast; /32 is a single address. A device
        // on either has no broadcast domain to announce into.
        assertNull(broadcast("192.168.1.42", 31))
        assertNull(broadcast("192.168.1.42", 32))
        assertNull(broadcast("192.168.1.42", 0))
    }

    @Test
    fun `IPv6 is not an IPv4 subnet`() {
        assertNull(BroadcastAddresses.directedBroadcast(ip("::1"), 64))
    }

    @Test
    fun `the fallback is the limited broadcast address`() {
        assertArrayEquals(
            byteArrayOf(-1, -1, -1, -1),
            BroadcastAddresses.FALLBACK.address,
        )
    }

    @Test
    fun `enumerating never returns nothing to send to`() {
        // Whatever this machine's interfaces look like, there is always
        // somewhere to broadcast, because the fallback is the floor.
        assertTrue(BroadcastAddresses.current().isNotEmpty())
    }

    private fun assertEquals4(expected: String, actual: String?) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
