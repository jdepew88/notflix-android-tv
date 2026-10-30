package com.notflix.tv.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.Inet4Address

/** The TV's own connection, shown on the wizard and the troubleshooting screen. */
data class NetworkInfo(
    val connected: Boolean,
    /** "Ethernet", "Wi-Fi", … or null when unknown. */
    val transport: String? = null,
    /** This TV's own addresses on that network (IPv4 first). */
    val addresses: List<String> = emptyList(),
    val vpn: Boolean = false,
) {
    val summary: String
        get() = if (!connected) "Not connected to a network" else listOfNotNull(transport, addresses.firstOrNull()).joinToString(" · ").ifEmpty { "Connected" }
}

/** Follows the default network so discovery can stop when it drops and search again when it returns. */
class NetworkMonitor(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun current(): NetworkInfo {
        val cm = connectivity ?: return NetworkInfo(connected = true)
        val network = cm.activeNetwork ?: return NetworkInfo(connected = false)
        return describe(cm.getNetworkCapabilities(network), cm.getLinkProperties(network))
    }

    fun changes(): Flow<NetworkInfo> = callbackFlow {
        val cm = connectivity
        if (cm == null) {
            trySend(NetworkInfo(connected = true))
            awaitClose {}
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(current()) }
            override fun onLost(network: Network) { trySend(current()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(current()) }
            override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) { trySend(current()) }
        }
        trySend(current())
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    private fun describe(caps: NetworkCapabilities?, link: LinkProperties?): NetworkInfo {
        val transport = when {
            caps == null -> null
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> null
        }
        val addresses = link?.linkAddresses.orEmpty()
            .map { it.address }
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .sortedBy { if (it is Inet4Address) 0 else 1 }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
        return NetworkInfo(
            connected = true,
            transport = transport,
            addresses = addresses,
            vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
        )
    }
}
