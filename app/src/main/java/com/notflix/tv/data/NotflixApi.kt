package com.notflix.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A non-2xx answer from the server, with its `{ error, code }` body when present. */
class ApiException(val status: Int, message: String, val code: String? = null) : IOException(message)

sealed interface SelectProfileResult {
    data class Selected(val state: UserState?) : SelectProfileResult
    data class PinRequired(val message: String) : SelectProfileResult
}

/**
 * Typed client for the Notflix server. Authentication is the same cookie session a browser gets
 * from /api/auth/login; every call goes through the server's own authorization checks (roles,
 * profiles, maturity). No provider credential (Real-Debrid, Plex, TMDB key) ever reaches the TV.
 */
class NotflixApi(
    val client: OkHttpClient,
    private val server: ServerConfig,
    private val cookies: PersistentCookieJar,
) {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }

    val baseUrl: String get() = server.baseUrl

    fun hasSession(): Boolean = cookies.hasCookie("notflix_session")

    // --- Session ---------------------------------------------------------------------------

    suspend fun health(): Boolean = runCatching { send(get("/api/health")) { it.isSuccessful } }.getOrDefault(false)

    /** Null when not signed in (401). Network problems throw. */
    suspend fun me(): MeResponse? = send(get("/api/auth/me")) { res ->
        if (res.code == 401) null else decode(res, MeResponse.serializer())
    }

    suspend fun login(name: String, password: String): AccountUser {
        val body = buildJsonObject {
            put("name", name)
            put("password", password)
            put("remember", true)
        }
        return send(post("/api/auth/login", body)) { res -> decode(res, LoginResponse.serializer()).user ?: throw ApiException(res.code, "Sign-in failed") }
    }

    suspend fun logout() {
        runCatching { send(post("/api/auth/logout", JsonObject(emptyMap()))) {} }
        cookies.clear()
    }

    fun forgetSession() = cookies.clear()

    suspend fun backdrops(): List<Backdrop> =
        runCatching { send(get("/api/auth/backdrops")) { decode(it, BackdropsResponse.serializer()).backdrops } }.getOrDefault(emptyList())

    // --- Profiles and per-profile state ----------------------------------------------------

    suspend fun selectProfile(profileId: String, pin: String? = null): SelectProfileResult {
        val body = buildJsonObject {
            put("action", "select")
            put("profileId", profileId)
            if (pin != null) put("pin", pin)
        }
        return send(post("/api/user/profiles", body)) { res ->
            if (res.code == 403) {
                val err = errorOf(res)
                if (err.code != "pin_required") throw ApiException(403, err.error ?: "Not allowed", err.code)
                SelectProfileResult.PinRequired(err.error ?: "Enter this profile's PIN.")
            } else {
                SelectProfileResult.Selected(decode(res, StateResponse.serializer()).state)
            }
        }
    }

    suspend fun leaveProfile() {
        runCatching { send(post("/api/user/profiles", buildJsonObject { put("action", "leave") })) {} }
    }

    /** Raw state, so a read-modify-write keeps fields this app does not model. */
    suspend fun userStateRaw(): JsonObject = send(get("/api/user/state")) { res ->
        val text = bodyOrThrow(res)
        json.parseToJsonElement(text).let { (it as? JsonObject)?.get("state") as? JsonObject } ?: JsonObject(emptyMap())
    }

    suspend fun userState(): UserState = json.decodeFromJsonElement(UserState.serializer(), userStateRaw())

    suspend fun putUserState(patch: JsonObject): UserState? = send(put("/api/user/state", patch)) { res ->
        decode(res, StateResponse.serializer()).state
    }

    // --- Catalog ---------------------------------------------------------------------------

    suspend fun catalog(query: String): CatalogList = send(get("/api/catalog?$query")) { decode(it, CatalogList.serializer()) }

    suspend fun titleDetails(tmdbId: Int, media: String): TitleDetails =
        send(get("/api/catalog?type=title&id=$tmdbId&media=$media")) { decode(it, TitleDetails.serializer()) }

    suspend fun season(tmdbId: Int, season: Int): SeasonDetails =
        send(get("/api/catalog?type=season&id=$tmdbId&season=$season")) { decode(it, SeasonDetails.serializer()) }

    suspend fun search(query: String): List<MediaItem> =
        catalog("type=search&q=${java.net.URLEncoder.encode(query, "UTF-8")}").items

    /** Saved titles (My List) by watch id: tmdb-123 / tmdb-tv-456 (at most 60 per call). */
    suspend fun lookup(ids: List<String>): List<MediaItem> {
        val valid = ids.filter { Regex("^tmdb-(tv-)?\\d+$").matches(it) }.take(60)
        if (valid.isEmpty()) return emptyList()
        return catalog("type=lookup&ids=${valid.joinToString(",")}").items
    }

    // --- Playback --------------------------------------------------------------------------

    /** POST /api/playback/start. A refusal (ok=false) is returned, not thrown, so its message can be shown. */
    suspend fun startPlayback(body: JsonObject): StartPlaybackResponse = send(post("/api/playback/start", body)) { res ->
        val text = res.body.string()
        val parsed = runCatching { json.decodeFromString(StartPlaybackResponse.serializer(), text) }.getOrNull()
        when {
            parsed != null && (parsed.ok || parsed.error != null) -> parsed
            res.code == 401 -> throw ApiException(401, "Your session has expired. Sign in again.", "unauthorized")
            else -> StartPlaybackResponse(ok = false, error = "The server could not start playback (HTTP ${res.code}).", code = "http-${res.code}")
        }
    }

    suspend fun seek(sessionId: String, position: Double): SeekResponse =
        send(post("/api/playback/$sessionId/seek", buildJsonObject { put("position", position) })) { decode(it, SeekResponse.serializer()) }

    suspend fun changeTracks(sessionId: String, audioIndex: Int?, subtitleIndex: Int?, position: Double): EngineSession {
        val body = buildJsonObject {
            if (audioIndex != null) put("audioIndex", audioIndex)
            if (subtitleIndex != null) put("subtitleIndex", subtitleIndex)
            put("position", position)
        }
        return send(post("/api/playback/$sessionId/tracks", body)) { decode(it, EngineSession.serializer()) }
    }

    /** Tells the engine where the viewer is (keeps conversions just ahead) and recovers failed streams. */
    suspend fun status(sessionId: String, position: Double): EngineStatus? =
        runCatching {
            send(get("/api/playback/$sessionId/status?position=${"%.1f".format(java.util.Locale.US, position)}")) {
                if (it.isSuccessful) decode(it, EngineStatus.serializer()) else null
            }
        }.getOrNull()

    /** WebVTT for one subtitle track (cues on the source timeline) and whether extraction finished. */
    suspend fun subtitles(sessionId: String, index: Int, from: Double): Pair<String, Boolean> =
        send(get("/api/playback/$sessionId/subtitles/$index?from=${"%.1f".format(java.util.Locale.US, from)}")) { res ->
            val text = bodyOrThrow(res)
            text to (res.header("x-subtitle-complete") == "1")
        }

    suspend fun stopSession(sessionId: String) {
        runCatching { send(Request.Builder().url(server.resolve("/api/playback/$sessionId")).delete().build()) {} }
    }

    suspend fun sources(params: Map<String, String>): SourcesResponse {
        val url = server.resolve("/api/playback/sources").toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return send(Request.Builder().url(url).get().build()) { res ->
            val text = res.body.string()
            runCatching { json.decodeFromString(SourcesResponse.serializer(), text) }.getOrNull()
                ?: SourcesResponse(error = "Could not list releases (HTTP ${res.code}).")
        }
    }

    // --- URLs ------------------------------------------------------------------------------

    fun absolute(path: String): String = server.resolve(path)

    /** Same rules as resolveAvatar() in src/lib/avatars.ts. Null means "show the emoji/initial". */
    fun avatarUrl(avatar: String): String? = when {
        avatar.startsWith("builtin:v2-") -> server.resolve("/avatars/v2/${avatar.removePrefix("builtin:")}.webp")
        avatar.startsWith("builtin:") -> server.resolve("/avatars/${avatar.removePrefix("builtin:")}.svg")
        avatar.startsWith("upload:") -> server.resolve("/api/user/avatar/${java.net.URLEncoder.encode(avatar.removePrefix("upload:"), "UTF-8")}")
        else -> null
    }

    // --- Plumbing --------------------------------------------------------------------------

    private fun get(path: String) = Request.Builder().url(server.resolve(path)).get().build()

    private fun post(path: String, body: JsonObject) =
        Request.Builder().url(server.resolve(path)).post(body.toString().toRequestBody(JSON_TYPE)).build()

    private fun put(path: String, body: JsonObject) =
        Request.Builder().url(server.resolve(path)).put(body.toString().toRequestBody(JSON_TYPE)).build()

    private fun errorOf(res: Response): ApiError =
        runCatching { json.decodeFromString(ApiError.serializer(), res.body.string()) }.getOrDefault(ApiError())

    private fun bodyOrThrow(res: Response): String {
        val text = res.body.string()
        if (!res.isSuccessful) {
            val err = runCatching { json.decodeFromString(ApiError.serializer(), text) }.getOrNull()
            throw ApiException(res.code, err?.error ?: "Server error (HTTP ${res.code})", err?.code)
        }
        return text
    }

    private fun <T> decode(res: Response, serializer: KSerializer<T>): T = json.decodeFromString(serializer, bodyOrThrow(res))

    /**
     * Runs a request and reads its body on the IO dispatcher (Android forbids network I/O on the
     * main thread, and reading a body is network I/O). Cancelling the caller cancels the call.
     */
    private suspend fun <T> send(request: Request, block: (Response) -> T): T = withContext(Dispatchers.IO) {
        client.newCall(request).await().use(block)
    }

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        fun tmdbImage(path: String?, size: String = "w780"): String? =
            path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }
    }
}

/** Suspends on an OkHttp call; cancelling the coroutine cancels the request. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) { _, _, _ -> response.close() } else response.close()
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
