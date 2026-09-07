package com.saikai.ptt.discovery

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections

/**
 * Where a DISCOVERY broadcast is sent.
 *
 * `docs/ADR/ADR-001` requires the subnet-directed broadcast address, computed
 * from the interface address and its mask, falling back to `255.255.255.255`.
 * The order matters: many access points and Android builds drop the limited
 * broadcast while forwarding the directed one, so treating the fallback as the
 * primary would make discovery unreliable on exactly the networks this product
 * is for.
 *
 * The address is computed rather than read from
 * `InterfaceAddress.getBroadcast()`, which returns null for some interfaces on
 * Android. Computing it is three lines and always works.
 */
object BroadcastAddresses {

    /** Limited broadcast. Never routed, and dropped by some access points. */
    val FALLBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))

    /**
     * `address | ~mask`, the directed broadcast for the subnet [address] is in.
     *
     * @return the four bytes, or null when this is not an IPv4 subnet that has a
     *   broadcast address at all. A /31 point-to-point link and a /32 host route
     *   both have none, and a device on one has no peers to find.
     */
    fun directedBroadcast(address: ByteArray, prefixLength: Int): ByteArray? {
        if (address.size != IPV4_BYTES) return null
        if (prefixLength !in 1..30) return null

        val result = ByteArray(IPV4_BYTES)
        for (index in 0 until IPV4_BYTES) {
            val bitsHere = (prefixLength - index * 8).coerceIn(0, 8)
            val mask = if (bitsHere == 0) 0 else (0xFF shl (8 - bitsHere)) and 0xFF
            result[index] = (address[index].toInt() or mask.inv()).toByte()
        }
        return result
    }

    /**
     * Every directed broadcast address this device currently has.
     *
     * All usable interfaces, not just the one named `wlan0`: a device can be on
     * WiFi and Ethernet at once, vendors do not agree on interface names, and
     * sending one extra 81-byte datagram costs nothing. Point-to-point
     * interfaces are skipped -- cellular and VPN links have no broadcast domain,
     * and a broadcast sent over one is silently discarded at best.
     */
    fun current(): List<InetAddress> {
        val found = mutableListOf<InetAddress>()
        try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback || nif.isPointToPoint) continue
                for (interfaceAddress in nif.interfaceAddresses) {
                    val address = interfaceAddress.address as? Inet4Address ?: continue
                    val broadcast = directedBroadcast(
                        address.address,
                        interfaceAddress.networkPrefixLength.toInt(),
                    ) ?: continue
                    found += InetAddress.getByAddress(broadcast)
                }
            }
        } catch (_: SocketException) {
            // No interfaces readable. The fallback below is all that is left.
        }
        return found.ifEmpty { listOf(FALLBACK) }
    }

    private const val IPV4_BYTES = 4
}
