package com.saikai.ptt.network

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/**
 * This device's own IPv4 addresses, read straight from the interfaces.
 *
 * Deliberately independent of [NetworkMonitor], which is service scope and
 * reports what `ConnectivityManager` says about the active network. This is for
 * the developer information page, and the moment that page is worth opening is
 * usually the moment the service is *not* running -- so an answer that requires
 * a running service would be missing exactly when it is wanted.
 *
 * Loopback is excluded and interfaces that are down are skipped. Nothing here
 * is cached: an address can change under the app at any time, which is half the
 * reason `docs/03_Protocol.md` section 11 keeps identity and address apart.
 */
object LocalAddresses {

    /** Every IPv4 address on an interface that is up, sorted. Empty on failure. */
    fun ipv4(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            .orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .sorted()
            .toList()
    } catch (_: SocketException) {
        // Some ROMs refuse the enumeration when no network is up. Not knowing is
        // a normal answer for a diagnostic; it is not worth a crash.
        emptyList()
    }
}
