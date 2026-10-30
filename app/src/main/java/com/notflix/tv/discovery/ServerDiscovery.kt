package com.notflix.tv.discovery

import com.notflix.tv.data.ServerIdentity
import com.notflix.tv.data.Verification
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A server seen on the network, and whether it checked out as a compatible Notflix server. */
data class DiscoveredServer(
    /** DNS-SD instance name: unique on the network, and the key for updates. */
    val serviceName: String,
    val url: String,
    val status: Status,
) {
    sealed interface Status {
        data object Checking : Status
        data class Available(val identity: ServerIdentity) : Status
        data class Unavailable(val message: String) : Status
    }

    /** host:port as shown on the card. */
    val address: String get() = url.substringAfter("://")
    val https: Boolean get() = url.startsWith("https://")
    val identity: ServerIdentity? get() = (status as? Status.Available)?.identity
}

data class DiscoveryState(
    val searching: Boolean = true,
    val servers: List<DiscoveredServer> = emptyList(),
    /** Set when discovery itself failed (no multicast, NSD unavailable). */
    val error: String? = null,
)

/**
 * One search: browses for [NotflixService.TYPE] for [windowMs], verifies every service it resolves
 * (each one is untrusted until GET /api/health says it's a compatible Notflix server), then stops
 * browsing and reports the final list once the last check is done. Nothing is ever connected to
 * automatically; the caller shows the list and the user chooses.
 */
class ServerDiscovery(
    private val browser: ServiceBrowser,
    private val verify: suspend (String) -> Verification,
    private val windowMs: Long = 10_000,
) {
    fun search(): Flow<DiscoveryState> = channelFlow {
        val lock = Mutex()
        var state = DiscoveryState()
        suspend fun update(change: (DiscoveryState) -> DiscoveryState) {
            val next = lock.withLock { change(state).also { state = it } }
            send(next)
        }
        send(state)

        coroutineScope {
            // Checks run in this scope, so the ones still going when the window closes finish.
            val checks = this
            withTimeoutOrNull(windowMs) {
                browser.browse(NotflixService.TYPE).collect { event ->
                    when (event) {
                        is BrowseEvent.Failed -> update { it.copy(error = event.reason) }
                        is BrowseEvent.Lost -> update { s -> s.copy(servers = s.servers.filterNot { it.serviceName == event.serviceName }) }
                        is BrowseEvent.Found -> {
                            val url = NotflixService.urlFor(event.service) ?: return@collect
                            val name = event.service.serviceName
                            val known = lock.withLock { state.servers.firstOrNull { it.serviceName == name } }
                            // Re-announcements of the same address need no new check.
                            if (known != null && known.url == url && known.status !is DiscoveredServer.Status.Unavailable) return@collect
                            update { s -> s.copy(servers = s.servers.upsert(DiscoveredServer(name, url, DiscoveredServer.Status.Checking))) }
                            checks.launch {
                                val status = when (val v = verify(url)) {
                                    is Verification.Ok -> DiscoveredServer.Status.Available(v.identity)
                                    is Verification.Failed -> DiscoveredServer.Status.Unavailable(v.message)
                                }
                                update { s ->
                                    val current = s.servers.firstOrNull { it.serviceName == name }
                                    if (current == null || current.url != url) s else s.copy(servers = s.servers.upsert(current.copy(status = status)).dedupe())
                                }
                            }
                        }
                    }
                }
            }
        }
        // Browsing has stopped (window over or discovery failed) and every check has finished.
        update { it.copy(searching = false) }
    }

    private fun List<DiscoveredServer>.upsert(server: DiscoveredServer): List<DiscoveredServer> {
        val i = indexOfFirst { it.serviceName == server.serviceName }
        return if (i < 0) this + server else toMutableList().also { it[i] = server }
    }

    /** One card per server instance, even if it's advertised under two names or addresses. */
    private fun List<DiscoveredServer>.dedupe(): List<DiscoveredServer> {
        val seen = mutableSetOf<String>()
        return filter { s ->
            val id = s.identity?.instanceId ?: s.identity?.url ?: return@filter true
            seen.add(id)
        }
    }
}
