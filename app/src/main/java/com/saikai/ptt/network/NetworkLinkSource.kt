package com.saikai.ptt.network

import kotlinx.coroutines.flow.StateFlow

/**
 * The local network as this device currently has it.
 *
 * [addresses] is what makes an address change visible. A network can stay
 * "available" across a DHCP renewal that moves this device to a different
 * subnet, and every peer's idea of where to send voice becomes wrong without a
 * single callback saying anything was lost.
 */
data class NetworkLink(
    val available: Boolean,
    /** IPv4 addresses on the link, sorted, so two readings compare by value. */
    val addresses: List<String>,
) {
    companion object {
        val NONE = NetworkLink(available = false, addresses = emptyList())
    }
}

/**
 * Where [NetworkLink] readings come from.
 *
 * An interface so that recovery can be driven by a test instead of by unplugging
 * a phone. Everything interesting about network recovery is in the ordering --
 * what is released, in what order, and what is started again -- and none of it
 * is reachable from a JVM test through `ConnectivityManager`.
 */
interface NetworkLinkSource {
    val link: StateFlow<NetworkLink>
    fun start()
    fun stop()
}
