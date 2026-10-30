package com.notflix.tv.data

/** Result of reconnecting to the saved server on launch. */
sealed interface SavedServerCheck {
    data class Ok(val server: SavedServer) : SavedServerCheck
    data object NoneSelected : SavedServerCheck
    data class Failed(val server: SavedServer, val problem: ServerProblem, val message: String) : SavedServerCheck

    /** The saved address now answers with a different server instance than the one chosen. */
    data class DifferentServer(val server: SavedServer, val found: ServerIdentity) : SavedServerCheck
}

/**
 * Choosing, remembering and switching servers. Server settings are kept apart from accounts: a
 * different server always starts signed out, with no cookies, account or profile state carried over.
 */
class ServerConnection(
    private val config: ServerConfig,
    private val cookies: PersistentCookieJar,
    private val user: UserRepository,
    val verifier: ServerVerifier,
    private val now: () -> Long = System::currentTimeMillis,
) {
    init {
        // A Phase 5 install that had saved an address: its cookies came from that server (Phase 5
        // cleared them whenever the address changed), so bind them to it and stay signed in.
        val saved = config.origin
        if (saved != null && cookies.origin == null && cookies.isLegacySessionFor(saved.host)) cookies.bindTo(saved)
    }

    val current: SavedServer? get() = config.current

    /**
     * A Phase 5 install that used the old built-in address never saved it, but its session cookies
     * name the host. That host on the default port is offered (not used) as "previously used"; it is
     * still verified, and nothing happens until the user picks it.
     */
    fun previousServerSuggestion(): String? {
        if (config.isConfigured) return null
        val host = cookies.legacyHosts().singleOrNull() ?: return null
        // Phase 5 always used port 4119 unless an address was saved (and then this isn't reached).
        val authority = if (host.contains(':')) "[$host]" else host
        return (ServerAddress.parse("$authority:${ServerAddress.LEGACY_PORT}") as? ParsedAddress.Valid)?.url
    }

    /** Re-checks the saved server; never looks for or switches to another one. */
    suspend fun checkSaved(): SavedServerCheck {
        val saved = config.current ?: return SavedServerCheck.NoneSelected
        return when (val v = verifier.verify(saved.url)) {
            is Verification.Failed -> SavedServerCheck.Failed(saved, v.problem, v.message)
            is Verification.Ok -> {
                val expected = saved.instanceId
                val found = v.identity.instanceId
                if (expected != null && found != null && expected != found) {
                    SavedServerCheck.DifferentServer(saved, v.identity)
                } else {
                    config.markConnected(v.identity, now())
                    SavedServerCheck.Ok(config.current!!)
                }
            }
        }
    }

    /** True when [identity] is the server already selected (same origin, and same instance when both report one). */
    fun isCurrent(identity: ServerIdentity): Boolean {
        val saved = config.current ?: return false
        val sameOrigin = ServerOrigin.of(saved.url) == ServerOrigin.of(identity.url)
        val sameInstance = saved.instanceId == null || identity.instanceId == null || saved.instanceId == identity.instanceId
        return sameOrigin && sameInstance
    }

    /**
     * Makes a verified server the selected one. Returns true when the existing sign-in was kept
     * (re-selecting the same server, or confirming the server a Phase 5 session belongs to);
     * otherwise the previous server's cookies, account and profile state are dropped first.
     */
    fun select(identity: ServerIdentity): Boolean {
        val origin = ServerOrigin.of(identity.url) ?: return false
        val keep = if (config.isConfigured) {
            isCurrent(identity) && cookies.origin == origin
        } else {
            // Only the exact suggested address (the Phase 5 default form http://host:4119) inherits a Phase 5 session.
            identity.url == previousServerSuggestion() && cookies.isLegacySessionFor(origin.host)
        }
        if (!keep) {
            cookies.clear()
            user.clear()
        }
        cookies.bindTo(origin)
        val previous = config.current?.takeIf { keep }
        config.save(
            SavedServer(
                url = identity.url,
                name = identity.name ?: previous?.name,
                instanceId = identity.instanceId ?: previous?.instanceId,
                apiVersion = identity.apiVersion ?: previous?.apiVersion,
                lastConnectedAt = now(),
            )
        )
        return keep
    }
}
