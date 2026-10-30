package com.notflix.tv.discovery

import com.notflix.tv.data.ServerAddress
import kotlinx.coroutines.flow.Flow
import okhttp3.HttpUrl
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** One resolved DNS-SD service. Everything in it comes from the network and is untrusted. */
data class AdvertisedService(
    /** The DNS-SD instance name, e.g. "Notflix on LIVINGROOM-PC". */
    val serviceName: String,
    val addresses: List<InetAddress>,
    val port: Int,
    /** TXT record entries (keys lowercased, values decoded as UTF-8 and size-capped). */
    val txt: Map<String, String> = emptyMap(),
)

sealed interface BrowseEvent {
    data class Found(val service: AdvertisedService) : BrowseEvent
    data class Lost(val serviceName: String) : BrowseEvent

    /** Discovery couldn't run at all (for example multicast is unavailable). */
    data class Failed(val reason: String) : BrowseEvent
}

/** Browses the local network for a DNS-SD service type until the collector stops collecting. */
fun interface ServiceBrowser {
    fun browse(serviceType: String): Flow<BrowseEvent>
}

object NotflixService {
    /** DNS-SD type the server advertises (NsdManager wants it without the trailing ".local."). */
    const val TYPE = "_notflix._tcp"

    /**
     * The URL to verify for an advertised service: the TXT "scheme" (http unless it says https),
     * the first usable address (IPv4 first, then routable IPv6; loopback, wildcard and link-local
     * IPv6, which needs an interface scope that URLs can't carry, are skipped) and the port.
     */
    fun urlFor(service: AdvertisedService): String? {
        if (service.port !in 1..65535) return null
        val scheme = if (service.txt["scheme"].equals("https", ignoreCase = true)) "https" else "http"
        val usable = service.addresses.filter { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isMulticastAddress }
        val address = usable.firstOrNull { it is Inet4Address }
            ?: usable.firstOrNull { it is Inet6Address && !it.isLinkLocalAddress }
            ?: return null
        // HttpUrl brackets and compresses IPv6 and leaves out the scheme's default port.
        val url = HttpUrl.Builder().scheme(scheme).host(address.hostAddress!!.substringBefore('%')).port(service.port).build()
        return ServerAddress.canonical(url)
    }
}
