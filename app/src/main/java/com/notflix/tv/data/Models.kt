package com.notflix.tv.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire models for the existing Notflix server API (the routes under src/app/api in the web app and the media
 * engine's Api/Views.cs). Only the fields the TV app uses are declared; unknown fields are ignored.
 */

@Serializable
data class MediaItem(
    val id: String,
    val title: String = "",
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val releaseDate: String? = null,
    val runtime: Int? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    val tmdbId: Int? = null,
    /** TMDB media type: "movie" or "tv". */
    val mediaType: String? = null,
    val originalLanguage: String? = null,
    val certification: String? = null,
    val logoPath: String? = null,
    /** "movie", "series" or "episode". */
    val type: String = "movie",
    val season: Int? = null,
    val episode: Int? = null,
    val seriesId: String? = null,
) {
    val isSeries: Boolean get() = type == "series" || mediaType == "tv"
    val tmdbMedia: String get() = if (isSeries) "tv" else "movie"
    val year: String? get() = releaseDate?.take(4)?.takeIf { it.length == 4 }

    /** Same rule as watchIdForItem() in src/lib/watch-url.ts. */
    val watchId: String get() = if (type == "episode" && seriesId != null) seriesId else id
}

@Serializable
data class CatalogList(val items: List<MediaItem> = emptyList(), val totalPages: Int? = null)

@Serializable
data class TitlePerson(val name: String, val role: String? = null, val profilePath: String? = null)

@Serializable
data class TitleSeason(
    val season: Int,
    val name: String = "",
    val episodeCount: Int = 0,
    val airDate: String? = null,
    val posterPath: String? = null,
)

@Serializable
data class TitleDetails(
    val item: MediaItem,
    val tagline: String? = null,
    val year: String? = null,
    val logoPath: String? = null,
    val trailerKey: String? = null,
    val directors: List<TitlePerson> = emptyList(),
    val creators: List<TitlePerson> = emptyList(),
    val cast: List<TitlePerson> = emptyList(),
    val seasons: List<TitleSeason> = emptyList(),
    val numberOfSeasons: Int? = null,
    val numberOfEpisodes: Int? = null,
    val recommendations: List<MediaItem> = emptyList(),
)

@Serializable
data class SeasonEpisode(
    val season: Int,
    val episode: Int,
    val name: String = "",
    val overview: String? = null,
    val stillPath: String? = null,
    val airDate: String? = null,
    val runtime: Int? = null,
    val rating: Double? = null,
)

@Serializable
data class SeasonDetails(
    val season: Int,
    val name: String = "",
    val overview: String? = null,
    val episodes: List<SeasonEpisode> = emptyList(),
)

// --- Accounts and profiles -------------------------------------------------------------------

@Serializable
data class AccountUser(
    val id: String,
    val name: String,
    val role: String = "user",
    val isAdmin: Boolean = false,
)

@Serializable
data class Profile(
    val id: String,
    val name: String,
    /** Emoji, `builtin:<avatar id>` or `upload:<file>` (see src/lib/avatars.ts). */
    val avatar: String = "",
    val isKids: Boolean = false,
    val maturity: String? = null,
    val hasPin: Boolean = false,
)

@Serializable
data class WatchedItemMeta(
    val id: String,
    val title: String = "",
    val type: String = "movie",
    val tmdbId: Int? = null,
    val mediaType: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val releaseDate: String? = null,
) {
    fun toMediaItem() = MediaItem(
        id = id, title = title, type = type, tmdbId = tmdbId, mediaType = mediaType,
        posterPath = posterPath, backdropPath = backdropPath, releaseDate = releaseDate,
    )
}

@Serializable
data class LastWatchedEntry(
    val season: Int? = null,
    val episode: Int? = null,
    val progress: Double = 0.0,
    val updatedAt: Long = 0,
    val item: WatchedItemMeta? = null,
    val positionSeconds: Double? = null,
    val durationSeconds: Double? = null,
)

@Serializable
data class UserState(
    val profiles: List<Profile> = emptyList(),
    val activeProfileId: String? = null,
    val myListByProfile: Map<String, List<String>> = emptyMap(),
    val continueWatchingByProfile: Map<String, Map<String, Double>> = emptyMap(),
    val lastWatchedByProfile: Map<String, Map<String, LastWatchedEntry>> = emptyMap(),
)

@Serializable
data class MeResponse(
    val user: AccountUser? = null,
    val state: UserState? = null,
    val maturity: String? = null,
)

@Serializable
data class LoginResponse(val user: AccountUser? = null, val error: String? = null)

@Serializable
data class StateResponse(val state: UserState? = null, val profile: Profile? = null, val error: String? = null, val code: String? = null)

@Serializable
data class Backdrop(val path: String? = null, val title: String? = null)

@Serializable
data class BackdropsResponse(val backdrops: List<Backdrop> = emptyList())

// --- Playback (POST /api/playback/start and the engine session relay) -----------------------

@Serializable
data class EngineTrack(
    val index: Int,
    val codec: String = "",
    val language: String? = null,
    val languageCode: String? = null,
    val title: String? = null,
    val channels: Int? = null,
    @SerialName("default") val isDefault: Boolean = false,
    val forced: Boolean = false,
    /** Subtitles only: "text", "image" or "other". */
    val kind: String? = null,
)

@Serializable
data class EngineTracks(
    val audio: List<EngineTrack> = emptyList(),
    val subtitles: List<EngineTrack> = emptyList(),
    val selectedAudio: Int? = null,
    val selectedSubtitle: Int? = null,
    val selectedAudioLanguage: String? = null,
    val audioLanguageMatched: Boolean? = null,
    val availableAudioLanguages: List<String> = emptyList(),
)

@Serializable
data class EngineDecision(
    val video: String = "",
    val audio: String = "",
    val outputVideoCodec: String = "",
    val outputAudioCodec: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val targetVideoKbps: Int? = null,
    val pipeline: String? = null,
    val hardwareDecode: Boolean? = null,
    val hardwareEncode: Boolean? = null,
    val tonemap: String? = null,
    val burnSubtitleIndex: Int? = null,
    val reasons: List<String> = emptyList(),
)

@Serializable
data class EngineSourceVideo(
    val codec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitDepth: Int? = null,
    val frameRate: Double? = null,
    val dynamicRange: String? = null,
)

@Serializable
data class EngineSource(
    val provider: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val container: String? = null,
    val bitrateKbps: Long? = null,
    val video: EngineSourceVideo? = null,
)

@Serializable
data class EngineSession(
    val id: String,
    /** DirectPlay, Remux, AudioTranscode or VideoTranscode. */
    val mode: String = "",
    val modeLabel: String = "",
    /** "progressive" (original bytes, range requests) or "hls" (engine-produced fMP4 segments). */
    val output: String = "progressive",
    val generation: Int = 0,
    val generationStart: Double = 0.0,
    val durationSeconds: Double? = null,
    val sourceStartTime: Double = 0.0,
    val startupMs: Double? = null,
    val probeMs: Double? = null,
    val state: String = "",
    val decision: EngineDecision = EngineDecision(),
    val tracks: EngineTracks = EngineTracks(),
    val source: EngineSource? = null,
) {
    val isHls: Boolean get() = output == "hls"
}

@Serializable
data class PlaybackUrls(val manifest: String? = null, val stream: String? = null)

@Serializable
data class PlaybackFallback(val kind: String = "direct", val url: String = "", val reason: String = "")

@Serializable
data class AudioOutcome(
    val selectedLanguage: String? = null,
    val matched: Boolean? = null,
    val available: List<String> = emptyList(),
    val preferred: List<String> = emptyList(),
)

@Serializable
data class StartPlaybackResponse(
    val ok: Boolean = false,
    val engine: String? = null,
    val session: EngineSession? = null,
    val urls: PlaybackUrls? = null,
    val fallback: PlaybackFallback? = null,
    val item: MediaItem? = null,
    val watchId: String? = null,
    val provider: String? = null,
    val streamLabel: String? = null,
    val quality: String? = null,
    val candidateIndex: Int? = null,
    val candidates: Int? = null,
    val defaultSubtitleIndex: Int? = null,
    val audio: AudioOutcome? = null,
    val notices: List<String> = emptyList(),
    val error: String? = null,
    val code: String? = null,
    val details: List<String> = emptyList(),
)

@Serializable
data class SeekResponse(val native: Boolean = false, val session: EngineSession? = null)

@Serializable
data class EngineStatus(
    val id: String = "",
    val mode: String = "",
    val generation: Int = 0,
    val generationStart: Double = 0.0,
    val state: String = "",
    val producedUntil: Double? = null,
    val ended: Boolean = false,
    val speed: Double? = null,
    val error: String? = null,
)

@Serializable
data class VerifiedSource(
    val container: String? = null,
    val video: EngineSourceVideo? = null,
    val audioLanguages: List<String> = emptyList(),
    val subtitleLanguages: List<String> = emptyList(),
)

@Serializable
data class SourceCandidate(
    val index: Int,
    val label: String = "",
    val detail: String? = null,
    val quality: String? = null,
    val cached: Boolean = false,
    val verified: VerifiedSource? = null,
    val verifyError: String? = null,
)

@Serializable
data class SourcesResponse(
    val provider: String? = null,
    val preferred: List<String> = emptyList(),
    val candidates: List<SourceCandidate> = emptyList(),
    val error: String? = null,
    val code: String? = null,
)

/** Error body most routes return (`{ error, code }`). */
@Serializable
data class ApiError(val error: String? = null, val code: String? = null)

