package com.notflix.tv.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.notflix.tv.AppContainer
import com.notflix.tv.data.ApiException
import com.notflix.tv.data.EngineSession
import com.notflix.tv.data.MediaItem
import com.notflix.tv.data.SeasonEpisode
import com.notflix.tv.data.SourceCandidate
import com.notflix.tv.data.StartPlaybackResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/** What to play. Also the navigation route of the player screen. */
@Serializable
data class PlayRequest(
    val tmdbId: Int,
    /** "movie" or "tv". */
    val media: String,
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeName: String? = null,
    /** Explicit start (e.g. "Start over" = 0). Null resumes from the profile's saved position. */
    val startSeconds: Double? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val releaseDate: String? = null,
) {
    val isTv: Boolean get() = media == "tv"
    val watchId: String get() = if (isTv) "tmdb-tv-$tmdbId" else "tmdb-$tmdbId"
}

enum class PlayerPhase { Starting, Playing, Error }

data class UpNext(val season: Int, val episode: Int, val name: String, val secondsLeft: Int)

data class StreamInfo(
    val modeLabel: String,
    val mode: String,
    val reasons: List<String>,
    val provider: String?,
    val release: String?,
    val quality: String?,
    val sourceVideo: String?,
    val outputVideo: String?,
    val videoDecoder: String?,
    val audioDecoder: String?,
    val audioFormat: String?,
    val pipeline: String?,
    val deviceSummary: String,
)

data class PlayerUiState(
    val phase: PlayerPhase = PlayerPhase.Starting,
    val title: String = "",
    val subtitle: String? = null,
    val status: String = "Finding the best source…",
    val error: String? = null,
    val errorDetails: List<String> = emptyList(),
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val buffered: Double = 0.0,
    val isPlaying: Boolean = false,
    val buffering: Boolean = true,
    val seekPreview: Double? = null,
    val cueText: String? = null,
    val session: EngineSession? = null,
    val selectedAudio: Int? = null,
    val selectedSubtitle: Int? = null,
    val notices: List<String> = emptyList(),
    val toast: String? = null,
    val sources: List<SourceCandidate>? = null,
    val sourcesLoading: Boolean = false,
    val sourcesError: String? = null,
    val currentSourceIndex: Int? = null,
    val upNext: UpNext? = null,
    val info: StreamInfo? = null,
)

@OptIn(UnstableApi::class)
class PlayerViewModel(
    private val app: AppContainer,
    context: Context,
    initial: PlayRequest,
) : ViewModel() {

    private val api = app.api
    private val caps = DeviceCapabilities.get(context)

    private var request = initial
    private var session: EngineSession? = null
    /** Source-timeline seconds at player position 0 (an HLS generation's start). */
    private var offset = 0.0
    private var sourceStartTime = 0.0
    private var cues: List<Cue> = emptyList()
    private var subtitleJob: Job? = null
    private var startJob: Job? = null
    private var seekJob: Job? = null
    private var upNextJob: Job? = null
    private var recoverAttempts = 0
    private var lastErrorAt = 0.0
    /** Set when this TV failed to decode the stream: the engine is then asked to convert it. */
    private var forceConversion = false
    private var candidateIndex: Int? = null
    private var provider: String? = null
    private var streamLabel: String? = null
    private var quality: String? = null
    private var videoDecoder: String? = null
    private var audioDecoder: String? = null
    private var videoFormat: Format? = null
    private var audioFormat: Format? = null
    private var stopped = false
    private var episodes: List<SeasonEpisode> = emptyList()

    private val _ui = MutableStateFlow(PlayerUiState(title = initial.title, subtitle = episodeLabel(initial)))
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    private val trackSelector = DefaultTrackSelector(context).apply {
        // Subtitles are drawn by the app from the engine's WebVTT extraction (same in every mode).
        parameters = buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
    }

    val player: ExoPlayer = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context)
                .setDataSourceFactory(OkHttpDataSource.Factory(app.http).setUserAgent(AppContainer.USER_AGENT))
                .setLoadErrorHandlingPolicy(EngineLoadErrorPolicy())
        )
        .setTrackSelector(trackSelector)
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 60_000, 2_500, 5_000).build())
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
        .build()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                viewModelScope.launch { recover(error) }
            }

            override fun onPlaybackStateChanged(state: Int) {
                _ui.update { it.copy(buffering = state == Player.STATE_BUFFERING) }
                if (state == Player.STATE_READY) _ui.update { it.copy(phase = PlayerPhase.Playing) }
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _ui.update { it.copy(isPlaying = isPlaying) }
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                videoDecoder = decoderName
                refreshInfo()
            }

            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                audioDecoder = decoderName
                refreshInfo()
            }

            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                videoFormat = format
                refreshInfo()
            }

            override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                audioFormat = format
                refreshInfo()
            }
        })
        start()
        tickLoop()
    }

    // --- Starting playback -----------------------------------------------------------------

    /** Asks the server for a stream. [streamIndex] picks a specific release (Change video source). */
    fun start(streamIndex: Int? = null, startAt: Double? = null, audioIndex: Int? = null, switching: Boolean = false) {
        startJob?.cancel()
        upNextJob?.cancel()
        startJob = viewModelScope.launch {
            _ui.update {
                it.copy(
                    phase = if (switching) it.phase else PlayerPhase.Starting,
                    status = if (switching) "Switching source…" else "Finding the best source…",
                    error = null, errorDetails = emptyList(), upNext = null,
                    toast = if (switching) "Preparing the new source…" else it.toast,
                )
            }
            val resume = startAt ?: request.startSeconds ?: resumePosition()
            val body = buildJsonObject {
                put("tmdbId", request.tmdbId)
                put("type", if (request.isTv) "series" else "movie")
                put("title", request.title)
                request.year?.let { put("year", it) }
                request.season?.let { put("season", it) }
                request.episode?.let { put("episode", it) }
                put("watchId", request.watchId)
                put("client", (if (forceConversion) conservative() else caps).toJson())
                if (resume > 1) put("startSeconds", resume)
                streamIndex?.let { put("streamIndex", it) }
                audioIndex?.let { put("audioIndex", it) }
            }
            val res = try {
                api.startPlayback(body)
            } catch (e: ApiException) {
                fail(e.message ?: "Playback failed", emptyList(), switching)
                return@launch
            } catch (e: IOException) {
                fail("Can't reach the Notflix server at ${api.baseUrl}.", listOfNotNull(e.message), switching)
                return@launch
            }
            if (!res.ok) {
                fail(res.error ?: "No playable source was found for this title.", res.details, switching)
                return@launch
            }
            val previous = session
            applyStart(res, resume)
            if (previous != null && previous.id != session?.id) {
                val id = previous.id
                app.appScope.launch { api.stopSession(id) }
            }
            if (request.isTv && episodes.isEmpty()) loadEpisodes()
        }
    }

    private fun fail(message: String, details: List<String>, switching: Boolean) {
        if (switching) {
            // The current stream keeps playing when a replacement fails (same as the website).
            _ui.update { it.copy(toast = "That source could not be played: $message") }
        } else {
            _ui.update { it.copy(phase = PlayerPhase.Error, error = message, errorDetails = details, buffering = false) }
        }
    }

    private fun applyStart(res: StartPlaybackResponse, resume: Double) {
        recoverAttempts = 0
        candidateIndex = res.candidateIndex
        provider = res.provider
        streamLabel = res.streamLabel
        quality = res.quality
        // Decoder names stay: ExoPlayer reuses a compatible decoder without a new init event.
        val s = res.session
        val notices = res.notices.toMutableList()
        if (forceConversion) notices += "This TV could not decode the original stream, so the server is converting it."
        if (s != null) {
            session = s
            sourceStartTime = s.sourceStartTime
            load(s, resume)
            _ui.update {
                it.copy(session = s, selectedAudio = s.tracks.selectedAudio, notices = notices, currentSourceIndex = res.candidateIndex, sources = null)
            }
            val sub = res.defaultSubtitleIndex
            if (sub != null && s.tracks.subtitles.any { it.index == sub && it.kind == "text" }) selectSubtitle(sub) else clearSubtitles()
        } else {
            // Media engine offline: the server proxies the original file. ExoPlayer plays most
            // containers natively, so this usually still works on a TV.
            val url = res.fallback?.url ?: res.urls?.stream
            if (url == null) {
                fail("The server returned no stream.", emptyList(), false)
                return
            }
            session = null
            offset = 0.0
            sourceStartTime = 0.0
            res.fallback?.reason?.let { notices += it }
            player.setMediaItem(ExoMediaItem.fromUri(api.absolute(url)), (resume * 1000).toLong())
            player.prepare()
            player.playWhenReady = true
            clearSubtitles()
            _ui.update { it.copy(session = null, notices = notices, currentSourceIndex = res.candidateIndex) }
        }
        refreshInfo()
    }

    /** Points the player at a session: original bytes (Direct Play) or the engine's HLS generation. */
    private fun load(s: EngineSession, at: Double) {
        if (s.isHls) {
            offset = s.generationStart
            val item = ExoMediaItem.Builder()
                .setUri(api.absolute("/api/playback/${s.id}/manifest?generation=${s.generation}"))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                // The engine's playlist grows while it converts (EVENT playlist). Never speed up to
                // "catch the live edge"; play at normal speed from where the viewer is.
                .setLiveConfiguration(ExoMediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
                .build()
            player.setMediaItem(item, ((at - s.generationStart).coerceAtLeast(0.0) * 1000).toLong())
        } else {
            offset = 0.0
            player.setMediaItem(ExoMediaItem.fromUri(api.absolute("/api/playback/${s.id}/stream")), (at * 1000).toLong())
        }
        // In Direct Play the file carries every audio track; pick the one the engine chose.
        val lang = s.tracks.selectedAudioLanguage
        trackSelector.parameters = trackSelector.buildUponParameters().setPreferredAudioLanguage(lang).build()
        player.prepare()
        player.playWhenReady = true
    }

    private fun resumePosition(): Double {
        val entry = app.user.lastWatched(request.watchId) ?: return 0.0
        if (request.isTv && (entry.season != request.season || entry.episode != request.episode)) return 0.0
        val pos = entry.positionSeconds ?: return 0.0
        return if (entry.progress in 0.5..95.0) pos else 0.0
    }

    // --- Position, seeking -----------------------------------------------------------------

    fun sourcePosition(): Double = offset + player.currentPosition.coerceAtLeast(0) / 1000.0

    private fun duration(): Double {
        session?.durationSeconds?.let { if (it > 0) return it }
        val d = player.duration
        return if (d != C.TIME_UNSET && d > 0) offset + d / 1000.0 else 0.0
    }

    /** D-pad seek: accumulates presses into one target, then seeks once the viewer stops pressing. */
    fun seekBy(deltaSeconds: Double) {
        val dur = duration()
        val base = _ui.value.seekPreview ?: sourcePosition()
        val target = (base + deltaSeconds).coerceIn(0.0, if (dur > 0) dur - 1 else Double.MAX_VALUE)
        _ui.update { it.copy(seekPreview = target) }
        seekJob?.cancel()
        seekJob = viewModelScope.launch {
            delay(750)
            commitSeek(target)
        }
    }

    fun seekTo(target: Double) {
        seekJob?.cancel()
        _ui.update { it.copy(seekPreview = target) }
        seekJob = viewModelScope.launch { commitSeek(target) }
    }

    private suspend fun commitSeek(target: Double) {
        val s = session
        if (s == null || !s.isHls) {
            player.seekTo((target * 1000).toLong())
        } else {
            // The engine knows what it has prepared: a native seek, or a new generation at the target.
            val r = try { api.seek(s.id, target) } catch (e: IOException) { null }
            val next = r?.session
            if (r == null || (r.native && (next == null || next.generation == s.generation))) {
                player.seekTo(((target - offset).coerceAtLeast(0.0) * 1000).toLong())
            } else if (next != null) {
                session = next
                load(next, target)
                _ui.update { it.copy(session = next) }
            }
        }
        _ui.update { it.copy(seekPreview = null) }
        upNextJob?.cancel()
        _ui.update { it.copy(upNext = null) }
    }

    fun togglePlay() {
        if (player.playbackState == Player.STATE_ENDED) seekTo(0.0)
        player.playWhenReady = !player.playWhenReady
    }

    private fun tickLoop() = viewModelScope.launch {
        var tick = 0
        while (isActive) {
            val pos = sourcePosition()
            val dur = duration()
            _ui.update {
                it.copy(
                    position = pos,
                    duration = dur,
                    buffered = offset + player.bufferedPosition / 1000.0,
                    cueText = if (it.selectedSubtitle != null) Vtt.active(cues, pos, sourceStartTime) else null,
                )
            }
            if (pos - lastErrorAt > 60) recoverAttempts = 0
            val s = session
            if (s != null && s.isHls && tick % 10 == 0) api.status(s.id, pos)
            if (tick % 60 == 59 && player.isPlaying) saveProgress()
            tick++
            delay(500)
        }
    }

    // --- Tracks ----------------------------------------------------------------------------

    fun selectAudio(index: Int) {
        val s = session ?: return
        if (s.tracks.selectedAudio == index) return
        changeTracks(index, s.decision.burnSubtitleIndex)
    }

    fun selectSubtitle(index: Int?) {
        subtitleJob?.cancel()
        cues = emptyList()
        val s = session
        if (s == null) {
            _ui.update { it.copy(selectedSubtitle = null, cueText = null) }
            return
        }
        val burned = s.decision.burnSubtitleIndex
        val track = s.tracks.subtitles.firstOrNull { it.index == index }
        _ui.update { it.copy(selectedSubtitle = index, cueText = null) }
        when {
            index == null || track == null -> if (burned != null) changeTracks(s.tracks.selectedAudio, null)
            track.kind == "image" -> changeTracks(s.tracks.selectedAudio, index) // the engine draws it into the picture
            else -> {
                if (burned != null) changeTracks(s.tracks.selectedAudio, null)
                val sessionId = s.id
                subtitleJob = viewModelScope.launch {
                    while (isActive) {
                        val loaded = try { api.subtitles(sessionId, index, sourcePosition()) } catch (e: IOException) { null }
                        if (loaded != null) {
                            cues = Vtt.parse(loaded.first)
                            if (loaded.second) break
                        }
                        delay(8_000)
                    }
                }
            }
        }
    }

    private fun clearSubtitles() {
        subtitleJob?.cancel()
        cues = emptyList()
        _ui.update { it.copy(selectedSubtitle = null, cueText = null) }
    }

    private fun changeTracks(audio: Int?, subtitle: Int?) = viewModelScope.launch {
        val s = session ?: return@launch
        val pos = sourcePosition()
        _ui.update { it.copy(toast = "Changing tracks…") }
        val next = try {
            api.changeTracks(s.id, audio, subtitle, pos)
        } catch (e: IOException) {
            _ui.update { it.copy(toast = "Could not change tracks: ${e.message}") }
            return@launch
        }
        session = next
        if (next.output != s.output || next.generation != s.generation) load(next, pos)
        _ui.update { it.copy(session = next, selectedAudio = next.tracks.selectedAudio, toast = null) }
        refreshInfo()
    }

    // --- Sources ---------------------------------------------------------------------------

    fun loadSources() {
        if (_ui.value.sourcesLoading) return
        _ui.update { it.copy(sourcesLoading = true, sourcesError = null) }
        viewModelScope.launch {
            val params = buildMap {
                put("tmdbId", request.tmdbId.toString())
                put("type", if (request.isTv) "series" else "movie")
                put("title", request.title)
                request.year?.let { put("year", it.toString()) }
                request.season?.let { put("season", it.toString()) }
                request.episode?.let { put("episode", it.toString()) }
                if ("hevc" in caps.mseVideo) put("hevc", "1")
            }
            val res = try { api.sources(params) } catch (e: IOException) { null }
            _ui.update {
                it.copy(
                    sourcesLoading = false,
                    sources = res?.candidates ?: emptyList(),
                    sourcesError = res?.error ?: if (res == null) "Can't reach the server." else null,
                )
            }
        }
    }

    fun selectSource(index: Int) {
        forceConversion = false
        start(streamIndex = index, startAt = sourcePosition(), switching = _ui.value.phase == PlayerPhase.Playing)
    }

    fun retry() {
        start(streamIndex = candidateIndex, startAt = sourcePosition().takeIf { it > 1 })
    }

    // --- Errors ----------------------------------------------------------------------------

    private suspend fun recover(error: PlaybackException) {
        val pos = sourcePosition()
        lastErrorAt = pos
        if (isDecoderError(error) && !forceConversion) {
            // This TV can't decode what it reported it could: let the engine convert instead.
            forceConversion = true
            start(streamIndex = candidateIndex, startAt = pos)
            return
        }
        if (recoverAttempts++ >= 3) {
            _ui.update {
                it.copy(phase = PlayerPhase.Error, error = "Playback stopped (${error.errorCodeName}).", errorDetails = listOfNotNull(error.cause?.message))
            }
            return
        }
        val s = session
        if (s == null) {
            player.prepare()
            return
        }
        val status = api.status(s.id, pos)
        if (status == null) {
            // The server forgot the session (restart, timeout): open a new one at the same spot.
            start(streamIndex = candidateIndex, startAt = pos)
            return
        }
        if (s.isHls) {
            val next = s.copy(generation = status.generation, generationStart = status.generationStart)
            session = next
            load(next, pos)
        } else {
            player.prepare()
        }
    }

    private fun isDecoderError(e: PlaybackException) = e.errorCode in setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    )

    private fun conservative() = caps.copy(
        directVideo = listOf("h264"),
        directAudio = listOf("aac", "mp3"),
        mseVideo = listOf("h264"),
        mseAudio = listOf("aac", "mp3"),
        tenBit = emptyList(),
        maxHeight = minOf(caps.maxHeight, 1080),
    )

    // --- Episodes --------------------------------------------------------------------------

    private suspend fun loadEpisodes() {
        val season = request.season ?: return
        episodes = runCatching { api.season(request.tmdbId, season).episodes }.getOrDefault(emptyList())
    }

    private suspend fun nextEpisode(): SeasonEpisode? {
        val season = request.season ?: return null
        val episode = request.episode ?: return null
        episodes.firstOrNull { it.season == season && it.episode == episode + 1 }?.let { return it }
        return runCatching { api.season(request.tmdbId, season + 1).episodes.firstOrNull() }.getOrNull()
    }

    private fun onEnded() {
        saveProgress(finished = true)
        if (!request.isTv) return
        upNextJob?.cancel()
        upNextJob = viewModelScope.launch {
            val next = nextEpisode() ?: return@launch
            for (left in 10 downTo 1) {
                _ui.update { it.copy(upNext = UpNext(next.season, next.episode, next.name, left)) }
                delay(1_000)
            }
            playNext()
        }
    }

    /** "Next episode" button: jumps to the following episode (also across a season boundary). */
    fun skipToNextEpisode() {
        if (!request.isTv) return
        viewModelScope.launch {
            val next = nextEpisode()
            if (next == null) {
                _ui.update { it.copy(toast = "This is the last episode.") }
                return@launch
            }
            saveProgress()
            _ui.update { it.copy(upNext = UpNext(next.season, next.episode, next.name, 0)) }
            playNext()
        }
    }

    fun playNext() {
        val next = _ui.value.upNext ?: return
        upNextJob?.cancel()
        request = request.copy(season = next.season, episode = next.episode, episodeName = next.name, startSeconds = 0.0)
        _ui.update { it.copy(upNext = null, subtitle = episodeLabel(request), title = request.title) }
        forceConversion = false
        start(startAt = 0.0)
        viewModelScope.launch { loadEpisodes() }
    }

    fun cancelUpNext() {
        upNextJob?.cancel()
        _ui.update { it.copy(upNext = null) }
    }

    // --- Progress and teardown -------------------------------------------------------------

    private fun progressItem() = MediaItem(
        id = request.watchId,
        title = request.title,
        type = if (request.isTv) "series" else "movie",
        tmdbId = request.tmdbId,
        mediaType = request.media,
        posterPath = request.posterPath,
        backdropPath = request.backdropPath,
        releaseDate = request.releaseDate,
    )

    fun saveProgress(finished: Boolean = false) {
        val dur = duration()
        val pos = if (finished) dur else sourcePosition()
        if (dur <= 0 || (!finished && pos < 10)) return
        val item = progressItem()
        val season = request.season
        val episode = request.episode
        app.appScope.launch {
            runCatching { app.user.saveProgress(item, season, episode, pos, dur) }
        }
    }

    fun onBackground() {
        player.pause()
        saveProgress()
    }

    fun dismissToast() = _ui.update { it.copy(toast = null) }

    /** Saves the resume point and closes the engine session (FFmpeg stops, temp files go). */
    fun stop() {
        if (stopped) return
        stopped = true
        saveProgress()
        player.stop()
        session?.id?.let { id -> app.appScope.launch { api.stopSession(id) } }
    }

    override fun onCleared() {
        stop()
        player.release()
    }

    private fun refreshInfo() {
        val s = session
        val v = s?.source?.video
        val sourceVideo = v?.let {
            listOfNotNull(
                it.codec?.uppercase(),
                if (it.width != null && it.height != null) "${it.width}×${it.height}" else null,
                it.bitDepth?.takeIf { d -> d > 8 }?.let { d -> "$d-bit" },
                it.dynamicRange?.takeIf { r -> r != "SDR" },
                it.frameRate?.let { f -> "%.3f".format(java.util.Locale.US, f).trimEnd('0').trimEnd('.') + " fps" },
            ).joinToString(" · ")
        }
        val vf = videoFormat
        val outputVideo = vf?.let {
            listOfNotNull(it.codecs ?: it.sampleMimeType, if (it.width > 0) "${it.width}×${it.height}" else null).joinToString(" · ")
        }
        val af = audioFormat
        val audio = af?.let { listOfNotNull(it.codecs ?: it.sampleMimeType, if (it.channelCount > 0) "${it.channelCount} ch" else null).joinToString(" · ") }
        val info = StreamInfo(
            modeLabel = s?.modeLabel ?: if (session == null && _ui.value.phase != PlayerPhase.Starting) "Direct (engine offline)" else "",
            mode = s?.mode ?: "",
            reasons = s?.decision?.reasons ?: emptyList(),
            provider = provider,
            release = streamLabel ?: s?.source?.fileName,
            quality = quality,
            sourceVideo = sourceVideo,
            outputVideo = outputVideo,
            videoDecoder = videoDecoder,
            audioDecoder = audioDecoder ?: if (af != null) "Passthrough to the TV / receiver" else null,
            audioFormat = audio,
            pipeline = s?.decision?.pipeline,
            deviceSummary = "Decodes ${caps.directVideo.joinToString(", ") { it.uppercase() }} · 10-bit ${caps.tenBit.joinToString(", ").ifEmpty { "none" }} · " +
                "audio ${caps.directAudio.joinToString(", ")} · up to ${caps.maxHeight}p${if (caps.hdrDisplay) " · " + caps.hdrTypes.joinToString("/") else ""}",
        )
        _ui.update { it.copy(info = info) }
    }

    companion object {
        fun episodeLabel(r: PlayRequest): String? =
            if (r.season != null && r.episode != null) "S${r.season}:E${r.episode}${r.episodeName?.let { " · $it" } ?: ""}" else null
    }
}

/**
 * The engine answers 503 while a stream is still starting or a segment is being produced, and 410
 * when a stream restarted (new generation). Keep retrying the former; never "exclude" the only
 * playlist (HLS fallback) — the view model handles 410 by loading the new generation.
 */
@OptIn(UnstableApi::class)
private class EngineLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount = */ 30) {
    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? = null

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val e = loadErrorInfo.exception
        if (e is HttpDataSource.InvalidResponseCodeException) {
            return when (e.responseCode) {
                502, 503, 504 -> 1_000L
                401, 403, 404, 409, 410 -> C.TIME_UNSET
                else -> super.getRetryDelayMsFor(loadErrorInfo)
            }
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}
