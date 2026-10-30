package com.notflix.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * What a server said about itself on GET /api/health. Today's servers answer only
 * `{ ok: true, service: "notflix" }`; [name], [instanceId] and [apiVersion] are filled in only when
 * a server actually reports them (see docs/server-discovery.md), never guessed.
 */
data class ServerIdentity(
    val url: String,
    val name: String? = null,
    val instanceId: String? = null,
    val apiVersion: Int? = null,
)

enum class ServerProblem {
    InvalidAddress, UnknownHost, Refused, Unreachable, Timeout, Tls, Redirected, NotNotflix, ServerError, Unsupported, Network,
}

sealed interface Verification {
    data class Ok(val identity: ServerIdentity) : Verification
    data class Failed(val problem: ServerProblem, val message: String) : Verification
}

/**
 * Checks that an address is a compatible Notflix server before the app trusts it with anything.
 *
 * It uses its own client: no cookie jar, no connection reuse (no session cookie or credential is ever sent to a server
 * the user hasn't chosen yet), no redirects (a redirect is reported, not followed), bounded
 * timeouts, and the platform's normal certificate validation for HTTPS. Answering as "notflix" is
 * a compatibility check, not proof of authenticity: anything on the network can claim that.
 */
class ServerVerifier(
    userAgent: String,
    connectTimeoutMs: Long = 5_000,
    // Generous: a `next dev` server compiles a route on its first request.
    readTimeoutMs: Long = 10_000,
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(connectTimeoutMs + readTimeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        // A fresh connection per check: a pooled keep-alive connection the server has since closed
        // would otherwise fail the check (no retries here) and look like a broken server.
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
        .build()

    private val timeoutSeconds = ((connectTimeoutMs + readTimeoutMs) / 1000).coerceAtLeast(1)

    @Serializable
    private data class Health(
        val ok: Boolean = false,
        val service: String? = null,
        val name: String? = null,
        val instanceId: String? = null,
        val apiVersion: Int? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = false; coerceInputValues = true }

    suspend fun verify(url: String): Verification = withContext(Dispatchers.IO) {
        val base = url.toHttpUrlOrNull() ?: return@withContext Verification.Failed(ServerProblem.InvalidAddress, "That isn't a valid address.")
        val target = base.newBuilder().addPathSegments("api/health").build()
        val request = Request.Builder().url(target).get().header("Accept", "application/json").build()
        try {
            client.newCall(request).await().use { res ->
                val where = displayOf(base)
                when {
                    res.isRedirect -> {
                        val location = res.header("Location")?.let { target.resolve(it) }
                        val hint = location?.let { " to ${ServerAddress.canonical(it.newBuilder().encodedPath("/").build())}" }.orEmpty()
                        Verification.Failed(ServerProblem.Redirected, "The server at $where redirected$hint. If that is your Notflix server, enter that address instead.")
                    }
                    res.code >= 500 -> Verification.Failed(ServerProblem.ServerError, "The server at $where answered with an error (HTTP ${res.code}). It may still be starting; try again in a moment.")
                    !res.isSuccessful -> notNotflix(where, "HTTP ${res.code}")
                    else -> {
                        // Bounded read: a health answer is a few dozen bytes.
                        val body = res.peekBody(MAX_BODY).string()
                        val health = runCatching { json.decodeFromString(Health.serializer(), body) }.getOrNull()
                        when {
                            health == null || !health.ok || health.service != SERVICE_ID -> notNotflix(where, null)
                            health.apiVersion != null && health.apiVersion > MAX_API_VERSION -> Verification.Failed(
                                ServerProblem.Unsupported,
                                "This Notflix server uses API version ${health.apiVersion}; this app supports up to $MAX_API_VERSION. Update Notflix for Android TV.",
                            )
                            health.apiVersion != null && health.apiVersion < 1 -> notNotflix(where, null)
                            else -> Verification.Ok(
                                ServerIdentity(
                                    url = ServerAddress.canonical(base),
                                    name = cleanName(health.name),
                                    instanceId = health.instanceId?.takeIf { INSTANCE_ID.matches(it) },
                                    apiVersion = health.apiVersion,
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: IOException) {
            classify(e, displayOf(base), base.host, base.port, base.isHttps, timeoutSeconds)
        }
    }

    private fun notNotflix(where: String, detail: String?) = Verification.Failed(
        ServerProblem.NotNotflix,
        "Something answered at $where${detail?.let { " ($it)" }.orEmpty()}, but it isn't a Notflix server. Check the port: Notflix normally uses ${ServerAddress.DEFAULT_PORT} (older versions ${ServerAddress.LEGACY_PORT}).",
    )

    companion object {
        const val SERVICE_ID = "notflix"

        /** Highest server API version this build understands. Servers that don't report one are treated as version 1. */
        const val MAX_API_VERSION = 1

        private const val MAX_BODY = 16L * 1024
        private val INSTANCE_ID = Regex("^[A-Za-z0-9._-]{1,128}$")

        fun displayOf(url: okhttp3.HttpUrl): String = ServerAddress.canonical(url).substringAfter("://")

        /** Printable, single-line, at most 60 characters; null when nothing useful remains. */
        fun cleanName(raw: String?): String? =
            raw?.filter { !it.isISOControl() }?.trim()?.take(60)?.takeIf { it.isNotEmpty() }

        /** Turns a connection failure into a problem the user can act on. */
        fun classify(e: IOException, where: String, host: String, port: Int, https: Boolean, timeoutSeconds: Long): Verification.Failed {
            // OkHttp tries each address of a host in turn and attaches the earlier failures as
            // suppressed exceptions, so look at all of them (a TLS failure on the first address
            // matters more than "refused" on the last).
            val chain = mutableListOf<Throwable>()
            fun collect(t: Throwable?) {
                if (t == null || chain.size >= 16 || chain.any { it === t }) return
                chain += t
                t.suppressed.forEach(::collect)
                collect(t.cause)
            }
            collect(e)
            val text = chain.joinToString(" ") { it.message.orEmpty() }
            fun has(vararg needles: String) = needles.any { text.contains(it, ignoreCase = true) }
            return when {
                chain.any { it is UnknownHostException } -> Verification.Failed(
                    ServerProblem.UnknownHost,
                    "This TV couldn't find \"$host\". Check the spelling, or use the server's IP address (.local names don't resolve on every TV).",
                )
                chain.any { it is SSLPeerUnverifiedException || it is CertificateException } || (chain.any { it is SSLHandshakeException } && has("certificate", "trust anchor", "CertPath")) ->
                    Verification.Failed(
                        ServerProblem.Tls,
                        "The HTTPS certificate for $host isn't valid or isn't trusted by this TV, so Notflix won't connect. Fix the certificate, or use the server's http:// address on your home network.",
                    )
                chain.any { it is SSLException } -> Verification.Failed(
                    ServerProblem.Tls,
                    if (https) "A secure (HTTPS) connection to $where failed. If the server only speaks plain HTTP, use http:// instead." else "The connection to $where failed during the secure handshake.",
                )
                chain.any { it is NoRouteToHostException || it is PortUnreachableException } || has("EHOSTUNREACH", "ENETUNREACH", "No route to host", "Network is unreachable") ->
                    Verification.Failed(
                        ServerProblem.Unreachable,
                        "This TV can't reach $host. Check that the TV and the server are on the same network and that the address is right.",
                    )
                chain.any { it is ConnectException } && has("ECONNREFUSED", "Connection refused") -> Verification.Failed(
                    ServerProblem.Refused,
                    "$host refused the connection on port $port. Notflix isn't listening there: check the port (normally ${ServerAddress.DEFAULT_PORT}, or ${ServerAddress.LEGACY_PORT} on older versions) and that the server is running.",
                )
                chain.any { it is InterruptedIOException } || has("timeout", "timed out", "ETIMEDOUT") -> Verification.Failed(
                    ServerProblem.Timeout,
                    "$where didn't answer within $timeoutSeconds seconds. Check the address, that the server is running, and that Windows Firewall allows port $port.",
                )
                chain.any { it is UnknownServiceException } -> Verification.Failed(
                    ServerProblem.Network,
                    "This connection to $where was blocked by the app's network policy.",
                )
                chain.any { it is ConnectException } -> Verification.Failed(
                    ServerProblem.Unreachable,
                    "This TV couldn't connect to $where. Check the address and that the server is running.",
                )
                has("unexpected end of stream", "Unexpected status line", "Expected leading") -> Verification.Failed(
                    ServerProblem.NotNotflix,
                    "Something answered at $where, but not like a Notflix server. Check the port: Notflix normally uses ${ServerAddress.DEFAULT_PORT} (older versions ${ServerAddress.LEGACY_PORT}).",
                )
                else -> Verification.Failed(ServerProblem.Network, "Couldn't connect to $where (${e.message ?: e.javaClass.simpleName}).")
            }
        }
    }
}

/**
 * Checks manual-entry candidates in order (each a single [verify], i.e. one GET /api/health without
 * cookies or redirects) and stops at the first Notflix server. When none is one, the first
 * candidate's problem is reported, naming the others that were tried. No other address is guessed.
 */
suspend fun firstNotflix(candidates: List<String>, verify: suspend (String) -> Verification, onTrying: (String) -> Unit = {}): Verification {
    var first: Verification.Failed? = null
    for (url in candidates) {
        onTrying(url)
        when (val v = verify(url)) {
            is Verification.Ok -> return v
            is Verification.Failed -> if (first == null) first = v
        }
    }
    val failed = first ?: return Verification.Failed(ServerProblem.Network, "Enter the server's address.")
    if (candidates.size == 1) return failed
    return failed.copy(message = "${failed.message} (Also tried ${candidates.drop(1).joinToString()}.)")
}
