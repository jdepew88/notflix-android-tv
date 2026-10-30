package com.notflix.tv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The signed-in account and the active profile's state (My List, Continue Watching), shared with
 * the website through /api/user/state. Writes are read-modify-write on the active profile only,
 * exactly like the web store, so the TV and a browser see the same lists and resume points.
 */
class UserRepository(private val api: NotflixApi) {

    private val _account = MutableStateFlow<AccountUser?>(null)
    val account: StateFlow<AccountUser?> = _account.asStateFlow()

    private val _state = MutableStateFlow(UserState())
    val state: StateFlow<UserState> = _state.asStateFlow()

    private val writeLock = Mutex()

    val activeProfile: Profile? get() = _state.value.let { s -> s.profiles.firstOrNull { it.id == s.activeProfileId } }

    /** Refreshes the account; null when signed out. */
    suspend fun refreshMe(): MeResponse? {
        val me = api.me()
        _account.value = me?.user
        me?.state?.let { _state.value = it }
        return me
    }

    suspend fun refreshState() {
        _state.value = api.userState()
    }

    fun applyState(state: UserState?) {
        if (state != null) _state.value = state
    }

    fun clear() {
        _account.value = null
        _state.value = UserState()
    }

    // --- My List ---------------------------------------------------------------------------

    fun myList(): List<String> = _state.value.activeProfileId?.let { _state.value.myListByProfile[it] } ?: emptyList()

    fun inMyList(itemId: String): Boolean = itemId in myList()

    suspend fun toggleMyList(itemId: String): Boolean = writeLock.withLock {
        val pid = _state.value.activeProfileId ?: return false
        val raw = api.userStateRaw()
        val current = raw.profileMap("myListByProfile", pid)?.let { el ->
            runCatching { el.jsonArray.map { it.jsonPrimitive.content } }.getOrNull()
        } ?: emptyList()
        val adding = itemId !in current
        val next = if (adding) current + itemId else current - itemId
        val patch = buildJsonObject {
            put("myListByProfile", buildJsonObject { put(pid, api.json.encodeToJsonElement(next)) })
        }
        applyState(api.putUserState(patch))
        adding
    }

    // --- Watch progress --------------------------------------------------------------------

    /** Resume entry for a title (movie id or series id). */
    fun lastWatched(watchId: String): LastWatchedEntry? =
        _state.value.activeProfileId?.let { _state.value.lastWatchedByProfile[it]?.get(watchId) }

    fun progress(key: String): Double? =
        _state.value.activeProfileId?.let { _state.value.continueWatchingByProfile[it]?.get(key) }

    /** Continue Watching, newest first (entries with card metadata, 0 < progress < 95). */
    fun continueWatching(): List<Pair<MediaItem, LastWatchedEntry>> {
        val pid = _state.value.activeProfileId ?: return emptyList()
        return (_state.value.lastWatchedByProfile[pid] ?: emptyMap())
            .filter { (_, e) -> e.item != null && e.progress > 0 && e.progress < 95 }
            .entries
            .sortedByDescending { it.value.updatedAt }
            .map { (_, e) -> e.item!!.toMediaItem() to e }
    }

    /**
     * Mirrors updateProgress() in src/lib/store.ts: percent progress under the title (and episode)
     * key, plus a lastWatched entry with an absolute resume position. ≥ 95 % counts as finished.
     */
    suspend fun saveProgress(
        item: MediaItem,
        season: Int?,
        episode: Int?,
        positionSeconds: Double,
        durationSeconds: Double,
    ) = writeLock.withLock {
        val pid = _state.value.activeProfileId ?: return@withLock
        if (durationSeconds <= 0 || positionSeconds < 0) return@withLock
        val progress = (positionSeconds / durationSeconds * 100).coerceIn(0.0, 100.0)
        val raw = api.userStateRaw()
        val progressMap = (raw.profileMap("continueWatchingByProfile", pid) as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val lastMap = (raw.profileMap("lastWatchedByProfile", pid) as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val (newProgress, newLast) = ProgressRules.apply(progressMap, lastMap, item, season, episode, progress, positionSeconds, durationSeconds)
        val patch = buildJsonObject {
            put("continueWatchingByProfile", buildJsonObject { put(pid, JsonObject(newProgress)) })
            put("lastWatchedByProfile", buildJsonObject { put(pid, JsonObject(newLast)) })
        }
        applyState(api.putUserState(patch))
    }

    private fun JsonObject.profileMap(field: String, pid: String): JsonElement? = (this[field] as? JsonObject)?.get(pid)
}

/** Pure progress-map rules (unit tested). */
object ProgressRules {
    /** Keeps default values (e.g. item.type = "movie", which the website reads) and omits nulls. */
    private val writer = kotlinx.serialization.json.Json { encodeDefaults = true; explicitNulls = false }

    fun episodeKey(seriesId: String, season: Int, episode: Int) = "$seriesId:S${season}E$episode"

    fun apply(
        progressMap: MutableMap<String, JsonElement>,
        lastMap: MutableMap<String, JsonElement>,
        item: MediaItem,
        season: Int?,
        episode: Int?,
        progress: Double,
        positionSeconds: Double,
        durationSeconds: Double,
    ): Pair<Map<String, JsonElement>, Map<String, JsonElement>> {
        val seriesId = item.watchId
        val key = if (season != null && episode != null) episodeKey(seriesId, season, episode) else seriesId
        if (progress >= 95) {
            progressMap.remove(key)
            if (season != null) {
                val existing = lastMap[seriesId] as? JsonObject
                val sameEpisode = existing?.get("season")?.jsonPrimitive?.content == season.toString() &&
                    existing["episode"]?.jsonPrimitive?.content == episode.toString()
                if (sameEpisode) lastMap.remove(seriesId)
            } else {
                lastMap.remove(seriesId)
            }
        } else if (progress > 0) {
            val rounded = JsonPrimitive(Math.round(progress * 10) / 10.0)
            progressMap[key] = rounded
            progressMap[seriesId] = rounded
            val entry = LastWatchedEntry(
                season = season,
                episode = episode,
                progress = Math.round(progress * 10) / 10.0,
                updatedAt = System.currentTimeMillis(),
                item = WatchedItemMeta(
                    id = seriesId,
                    title = item.title,
                    type = if (item.isSeries) "series" else "movie",
                    tmdbId = item.tmdbId,
                    mediaType = item.tmdbMedia,
                    posterPath = item.posterPath,
                    backdropPath = item.backdropPath,
                    releaseDate = item.releaseDate,
                ),
                positionSeconds = Math.round(positionSeconds * 10) / 10.0,
                durationSeconds = Math.round(durationSeconds * 10) / 10.0,
            )
            lastMap[seriesId] = writer.encodeToJsonElement(LastWatchedEntry.serializer(), entry)
        }
        return progressMap to lastMap
    }
}
