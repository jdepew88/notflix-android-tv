package com.notflix.tv.data

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.Interceptor
import java.io.IOException

/** The server the user chose, with what it reported about itself when it was verified. */
@Serializable
data class SavedServer(
    val url: String,
    val name: String? = null,
    val instanceId: String? = null,
    val apiVersion: Int? = null,
    val lastConnectedAt: Long? = null,
) {
    fun identity() = ServerIdentity(url, name, instanceId, apiVersion)
}

/** No server has been chosen yet (fresh install): the connection wizard runs first. */
class NoServerSelectedException : IOException("No Notflix server has been selected.")

/**
 * The Notflix server this TV talks to. There is no built-in default: a fresh install starts with
 * none and the connection wizard picks one. The URL stays under the Phase 5 key "server_url", so an
 * address saved by an earlier version is kept as is after an upgrade.
 */
class ServerConfig(private val prefs: SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    var current: SavedServer? = load()
        private set

    val isConfigured: Boolean get() = current != null

    /** The selected server's URL, or "" when none is selected. */
    val baseUrl: String get() = current?.url.orEmpty()

    val origin: ServerOrigin? get() = ServerOrigin.of(current?.url)

    /** Plain HTTP is allowed only to the server the user selected (HTTPS anywhere is fine). */
    fun allowsCleartext(url: HttpUrl): Boolean = origin?.matches(url) == true

    fun save(server: SavedServer) {
        current = server
        prefs.edit {
            putString(KEY_URL, server.url)
            putString(KEY_META, json.encodeToString(SavedServer.serializer(), server))
        }
    }

    /** Records a successful reconnect, adopting identity details the server reports for the first time. */
    fun markConnected(identity: ServerIdentity, at: Long = System.currentTimeMillis()) {
        val saved = current ?: return
        save(
            saved.copy(
                name = identity.name ?: saved.name,
                instanceId = saved.instanceId ?: identity.instanceId,
                apiVersion = identity.apiVersion ?: saved.apiVersion,
                lastConnectedAt = at,
            )
        )
    }

    /**
     * Plain HTTP only to the selected server. The manifest has to permit cleartext in general (a
     * user-chosen LAN address can't be listed there in advance), so the app enforces it here.
     */
    fun cleartextGuard() = Interceptor { chain ->
        val url = chain.request().url
        if (!url.isHttps && !allowsCleartext(url)) {
            throw IOException("Plain-HTTP request to ${url.host} blocked: only the selected Notflix server may be reached without HTTPS.")
        }
        chain.proceed(chain.request())
    }

    fun resolve(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = current?.url ?: throw NoServerSelectedException()
        return base + path
    }

    private fun load(): SavedServer? {
        val url = prefs.getString(KEY_URL, null)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val meta = prefs.getString(KEY_META, null)?.let { runCatching { json.decodeFromString(SavedServer.serializer(), it) }.getOrNull() }
        // The URL key is authoritative; metadata saved for another URL is ignored.
        return if (meta != null && meta.url == url) meta else SavedServer(url)
    }

    private companion object {
        const val KEY_URL = "server_url"
        const val KEY_META = "server_meta"
    }
}
