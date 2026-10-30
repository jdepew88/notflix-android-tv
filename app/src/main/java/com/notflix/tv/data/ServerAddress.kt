package com.notflix.tv.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** What the user typed, turned into a server URL, or why it can't be one. */
sealed interface ParsedAddress {
    /**
     * [url] is exactly what will be contacted first. [suggested] is true when the app filled in the
     * scheme (http://) and possibly the port ([addedPort]), so the screen can show the proposal.
     * [alternatives] are tried in order if [url] isn't a Notflix server (only for an added port:
     * the legacy port 4119 after today's 8420).
     */
    data class Valid(
        val url: String,
        val suggested: Boolean,
        val addedPort: Boolean = false,
        val alternatives: List<String> = emptyList(),
    ) : ParsedAddress {
        val candidates: List<String> get() = listOf(url) + alternatives
    }
    data class Invalid(val reason: String) : ParsedAddress
}

/**
 * Parses a manually entered server address: an IPv4 or IPv6 address, a hostname (local or DNS),
 * optionally with a port, or a complete http:// / https:// URL. A complete URL or an explicit port
 * is used as typed; only a bare address gets http:// and the Notflix ports proposed: 8420 (today's
 * default), then 4119 (older installations). Nothing else is guessed and no other host is tried.
 */
object ServerAddress {
    /** Default web/API port of current Notflix servers. */
    const val DEFAULT_PORT = 8420

    /** Port of older Notflix installations (and the Phase 5 app's built-in server). */
    const val LEGACY_PORT = 4119

    private val OTHER_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val NUMERIC_HOST = Regex("^[0-9.]+$")

    fun parse(raw: String): ParsedAddress {
        val input = raw.trim()
        if (input.isEmpty()) return ParsedAddress.Invalid("Enter the server's IP address, hostname or URL.")
        if (input.any { it.isWhitespace() }) return ParsedAddress.Invalid("An address can't contain spaces.")
        val lower = input.lowercase()
        val hasScheme = lower.startsWith("http://") || lower.startsWith("https://")
        if (!hasScheme && OTHER_SCHEME.containsMatchIn(input)) {
            return ParsedAddress.Invalid("Only http:// and https:// addresses are supported.")
        }
        if (input.contains('%')) {
            return ParsedAddress.Invalid("IPv6 addresses with a zone (%) aren't supported. Use the server's IPv4 address, a routable IPv6 address or its hostname.")
        }

        val candidate: String
        val suggested: Boolean
        var addedPort = false
        var legacy: String? = null
        if (hasScheme) {
            candidate = input
            suggested = false
        } else {
            val authority = input.substringBefore('/')
            val path = input.removePrefix(authority)
            val (host, port) = splitAuthority(authority) ?: return ParsedAddress.Invalid("That isn't a valid address.")
            if (host.isEmpty()) return ParsedAddress.Invalid("Enter the server's IP address or hostname.")
            if (port != null) portProblem(port)?.let { return ParsedAddress.Invalid(it) }
            candidate = "http://$host:${port ?: DEFAULT_PORT}$path"
            suggested = true
            addedPort = port == null
            if (addedPort) legacy = "http://$host:$LEGACY_PORT$path"
        }

        val url = try {
            candidate.toHttpUrl()
        } catch (e: IllegalArgumentException) {
            val message = e.message.orEmpty()
            return ParsedAddress.Invalid(
                when {
                    message.contains("port", ignoreCase = true) -> "The port must be a number from 1 to 65535."
                    message.contains("host", ignoreCase = true) -> "That host name isn't valid."
                    else -> "That isn't a valid address."
                }
            )
        }
        hostProblem(url)?.let { return ParsedAddress.Invalid(it) }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return ParsedAddress.Invalid("Addresses with a user name or password aren't supported.")
        }
        if (url.query != null || url.fragment != null) {
            return ParsedAddress.Invalid("Enter only the server address, without ? or # parts.")
        }
        val alternatives = listOfNotNull(legacy?.let { canonical(it.toHttpUrl()) })
        return ParsedAddress.Valid(canonical(url), suggested, addedPort, alternatives)
    }

    /** scheme://host[:port][/path] without a trailing slash; the default port for the scheme is omitted. */
    fun canonical(url: HttpUrl): String = url.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')

    /** Host and optional port of "host", "host:port", "[v6]", "[v6]:port" or a bare IPv6 address. */
    private fun splitAuthority(authority: String): Pair<String, String?>? {
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return null
            val host = authority.substring(0, end + 1)
            val rest = authority.substring(end + 1)
            return when {
                rest.isEmpty() -> host to null
                rest.startsWith(":") -> host to rest.substring(1)
                else -> null
            }
        }
        // Two or more colons without brackets can only be an IPv6 address (which then has no port).
        if (authority.count { it == ':' } >= 2) return "[$authority]" to null
        if (authority.contains(':')) return authority.substringBefore(':') to authority.substringAfter(':')
        return authority to null
    }

    private fun portProblem(port: String): String? {
        val n = port.toIntOrNull()
        return if (port.isEmpty() || port.any { !it.isDigit() } || n == null || n !in 1..65535) "The port must be a number from 1 to 65535." else null
    }

    /** OkHttp accepts "999.1.1.1" as a host name; an all-numeric host has to be a real IPv4 address. */
    private fun hostProblem(url: HttpUrl): String? {
        val host = url.host
        if (NUMERIC_HOST.matches(host)) {
            val parts = host.split('.')
            val valid = parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.toInt() in 0..255 }
            if (!valid) return "$host isn't a valid IPv4 address."
        }
        if (host == "0.0.0.0" || host == "::") return "$host isn't a server address. Use the server PC's IP address."
        return null
    }
}

/** scheme + host + port: the unit the session cookie and plain-HTTP access are scoped to. */
data class ServerOrigin(val scheme: String, val host: String, val port: Int) {
    fun matches(url: HttpUrl): Boolean = url.scheme == scheme && url.host == host && url.port == port

    /** Round-trips through [of] (IPv6 hosts get their brackets back). */
    override fun toString(): String = "$scheme://${if (host.contains(':')) "[$host]" else host}:$port"

    companion object {
        fun of(url: HttpUrl) = ServerOrigin(url.scheme, url.host, url.port)

        fun of(url: String?): ServerOrigin? = url?.let { runCatching { of(it.toHttpUrl()) }.getOrNull() }
    }
}
