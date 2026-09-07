package com.saikai.ptt.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

/**
 * Watches for the network appearing, disappearing and being renumbered.
 *
 * A callback, never a poll (`docs/01_PRD.md` section 42): a timer that checks
 * the network every few seconds costs battery all day to notice something the
 * platform will tell us about for free.
 *
 * Only WiFi and Ethernet are requested. Cellular is deliberately not a network
 * this product can work over -- it has no broadcast domain and no LAN peers --
 * so treating its arrival as a recovery event would tear down and rebuild the
 * sockets every time a phone left the building.
 */
class NetworkMonitor(
    context: Context,
    private val logger: Logger,
) : NetworkLinkSource {

    private val connectivity: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _link = MutableStateFlow(NetworkLink.NONE)
    override val link: StateFlow<NetworkLink> = _link.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun start() {
        val manager = connectivity ?: run {
            logger.w(LogCategory.NETWORK) {
                "no ConnectivityManager; network changes will not be noticed"
            }
            return
        }
        if (callback != null) return

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            // Deliberately no NET_CAPABILITY_INTERNET: a WiFi network with no
            // route to the internet is the normal case for this product, and
            // requiring it would make the app blind on exactly the isolated
            // site networks it was built for.
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                publish(manager.getLinkProperties(network))
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) {
                publish(linkProperties)
            }

            override fun onLost(network: Network) {
                logger.i(LogCategory.NETWORK) { "network lost" }
                _link.value = NetworkLink.NONE
            }
        }

        try {
            manager.registerNetworkCallback(request, networkCallback)
            callback = networkCallback
        } catch (error: SecurityException) {
            logger.e(LogCategory.NETWORK, error) { "could not watch for network changes" }
        }
    }

    override fun stop() {
        val manager = connectivity
        val registered = callback ?: return
        callback = null
        try {
            manager?.unregisterNetworkCallback(registered)
        } catch (_: IllegalArgumentException) {
            // Already gone. Unregistering twice is not worth a crash on the way
            // out of a service that is being destroyed anyway.
        }
        _link.value = NetworkLink.NONE
    }

    private fun publish(properties: LinkProperties?) {
        val addresses = properties?.linkAddresses
            .orEmpty()
            .mapNotNull { (it.address as? Inet4Address)?.hostAddress }
            .sorted()
        val next = NetworkLink(available = true, addresses = addresses)
        if (_link.value != next) {
            logger.i(LogCategory.NETWORK) { "network available at $addresses" }
        }
        _link.value = next
    }
}
