package com.notflix.tv.data

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Keeps the server's HttpOnly session cookies (notflix_session, notflix_profile) across app restarts
 * in the app's private storage, so the TV stays signed in the way a browser with "Remember me" does.
 * The cookies are opaque signed tokens; the app never sees a password after sign-in.
 *
 * The jar belongs to one server origin (scheme + host + port) at a time. It accepts and sends
 * cookies only for that origin, so a session can't reach another server, not even one on the same
 * host with a different port (browsers would share cookies across ports). Switching servers
 * [clear]s it and [bindTo]s the new origin.
 */
class PersistentCookieJar(private val prefs: SharedPreferences?) : CookieJar {

    @Serializable
    private data class Stored(
        val name: String,
        val value: String,
        val expiresAt: Long,
        val domain: String,
        val path: String,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val cookies = mutableListOf<Cookie>()

    /**
     * The origin these cookies belong to. Null either before any server was chosen or for cookies
     * written by Phase 5, which didn't record it (see [isLegacySessionFor]).
     */
    @Volatile
    var origin: ServerOrigin? = ServerOrigin.of(prefs?.getString(KEY_ORIGIN, null))
        private set

    init {
        prefs?.getString(KEY, null)?.let { raw ->
            runCatching { json.decodeFromString(ListSerializer(Stored.serializer()), raw) }.getOrNull()?.forEach { s ->
                val builder = Cookie.Builder().name(s.name).value(s.value).expiresAt(s.expiresAt).path(s.path)
                if (s.hostOnly) builder.hostOnlyDomain(s.domain) else builder.domain(s.domain)
                if (s.secure) builder.secure()
                if (s.httpOnly) builder.httpOnly()
                cookies += builder.build()
            }
        }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (origin?.matches(url) != true) return
        for (cookie in cookies) {
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies += cookie
        }
        persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (origin?.matches(url) != true) return emptyList()
        val now = System.currentTimeMillis()
        if (cookies.removeAll { it.expiresAt <= now }) persist()
        return cookies.filter { it.matches(url) }
    }

    @Synchronized
    fun clear() {
        cookies.clear()
        persist()
    }

    /** Makes [origin] the only one these cookies are accepted from and sent to. */
    @Synchronized
    fun bindTo(origin: ServerOrigin) {
        this.origin = origin
        prefs?.edit { putString(KEY_ORIGIN, origin.toString()) }
    }

    /** True for a Phase 5 session (no recorded origin) whose cookies all belong to [host]. */
    @Synchronized
    fun isLegacySessionFor(host: String): Boolean =
        origin == null && cookies.isNotEmpty() && cookies.all { it.domain.equals(host, ignoreCase = true) }

    /** Hosts a Phase 5 session's cookies were set by (empty once an origin is recorded). */
    @Synchronized
    fun legacyHosts(): Set<String> = if (origin == null) cookies.map { it.domain }.toSet() else emptySet()

    @Synchronized
    fun hasCookie(name: String): Boolean = cookies.any { it.name == name && it.expiresAt > System.currentTimeMillis() }

    private fun persist() {
        val stored = cookies.map {
            Stored(it.name, it.value, it.expiresAt, it.domain, it.path, it.secure, it.httpOnly, it.hostOnly)
        }
        prefs?.edit { putString(KEY, json.encodeToString(ListSerializer(Stored.serializer()), stored)) }
    }

    private companion object {
        const val KEY = "cookies"
        const val KEY_ORIGIN = "cookies_origin"
    }
}
