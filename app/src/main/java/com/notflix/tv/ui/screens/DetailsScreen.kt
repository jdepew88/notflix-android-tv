package com.notflix.tv.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.notflix.tv.R
import com.notflix.tv.container
import com.notflix.tv.data.ApiException
import com.notflix.tv.data.MediaItem
import com.notflix.tv.data.NotflixApi
import com.notflix.tv.data.ProgressRules
import com.notflix.tv.data.SeasonEpisode
import com.notflix.tv.data.TitleDetails
import com.notflix.tv.playback.PlayRequest
import com.notflix.tv.ui.components.Badge
import com.notflix.tv.ui.components.ErrorBlock
import com.notflix.tv.ui.components.LoadingBlock
import com.notflix.tv.ui.components.MediaCard
import com.notflix.tv.ui.components.MinimalBringIntoView
import com.notflix.tv.ui.components.NotflixButton
import com.notflix.tv.ui.components.ProgressLine
import com.notflix.tv.ui.components.formatRuntime
import com.notflix.tv.ui.theme.NotflixColors
import kotlinx.coroutines.launch
import java.io.IOException

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DetailsScreen(
    tmdbId: Int,
    media: String,
    onPlay: (PlayRequest) -> Unit,
    onOpen: (MediaItem) -> Unit,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf<TitleDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val userState by app.user.state.collectAsState()
    var season by remember { mutableStateOf<Int?>(null) }
    var episodes by remember { mutableStateOf<List<SeasonEpisode>>(emptyList()) }
    var episodesLoading by remember { mutableStateOf(false) }
    var listBusy by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(tmdbId, media, attempt) {
        error = null
        details = try {
            app.api.titleDetails(tmdbId, media)
        } catch (e: ApiException) {
            error = if (e.status == 403) "This title isn't available on this profile." else e.message
            null
        } catch (e: IOException) {
            error = "Can't reach the Notflix server."
            null
        }
        details?.let { d ->
            if (d.item.isSeries) {
                val last = app.user.lastWatched(d.item.watchId)
                season = last?.season ?: d.seasons.firstOrNull { it.season > 0 }?.season ?: d.seasons.firstOrNull()?.season
            }
        }
    }
    LaunchedEffect(season) {
        val s = season ?: return@LaunchedEffect
        episodesLoading = true
        episodes = runCatching { app.api.season(tmdbId, s).episodes }.getOrDefault(emptyList())
        episodesLoading = false
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { runCatching { app.user.refreshState() } } }
    LaunchedEffect(details) { if (details != null) runCatching { playFocus.requestFocus() } }

    val d = details
    when {
        error != null -> ErrorBlock("Can't open this title", error) {
            NotflixButton("Try again", onClick = { attempt++ }, primary = true)
            NotflixButton("Back", onClick = onBack)
        }
        d == null -> LoadingBlock()
        else -> {
            val item = d.item
            fun request(season: Int? = null, episode: Int? = null, name: String? = null, start: Double? = null) = PlayRequest(
                tmdbId = tmdbId, media = media, title = item.title, year = (d.year ?: item.year)?.toIntOrNull(),
                season = season, episode = episode, episodeName = name, startSeconds = start,
                posterPath = item.posterPath, backdropPath = item.backdropPath, releaseDate = item.releaseDate,
            )
            val last = userState.activeProfileId?.let { userState.lastWatchedByProfile[it]?.get(item.watchId) }
            val resumable = last != null && last.progress > 0.5 && last.progress < 95
            val inList = userState.activeProfileId?.let { userState.myListByProfile[it]?.contains(item.id) } == true

            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = NotflixApi.tmdbImage(item.backdropPath, "w1280"),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f).align(Alignment.TopEnd),
                )
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to NotflixColors.Canvas, 0.45f to NotflixColors.Canvas.copy(alpha = 0.85f), 1f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 0.8f to NotflixColors.Canvas)))

                CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalBringIntoView) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 48.dp, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    item {
                        Column(Modifier.padding(horizontal = 64.dp).heightIn(min = 380.dp)) {
                            val logo = NotflixApi.tmdbImage(d.logoPath ?: item.logoPath, "w500")
                            if (logo != null) {
                                AsyncImage(model = logo, contentDescription = item.title, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, modifier = Modifier.height(110.dp).width(460.dp))
                            } else {
                                Text(item.title, style = MaterialTheme.typography.displayMedium, maxLines = 2, modifier = Modifier.width(720.dp))
                            }
                            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                (d.year ?: item.year)?.let { Text(it, color = NotflixColors.Ink2) }
                                item.certification?.let { Badge(it) }
                                if (item.isSeries) d.numberOfSeasons?.let { Text("$it season${if (it == 1) "" else "s"}", color = NotflixColors.Ink2) }
                                else formatRuntime(item.runtime)?.let { Text(it, color = NotflixColors.Ink2) }
                                item.rating?.takeIf { it > 0 }?.let { Text("★ %.1f".format(it), color = NotflixColors.Ink2) }
                                if (item.genres.isNotEmpty()) Text(item.genres.take(3).joinToString(" · "), color = NotflixColors.Ink3)
                            }
                            d.tagline?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.titleMedium, color = NotflixColors.Ink2, modifier = Modifier.padding(top = 14.dp))
                            }
                            Text(item.overview ?: "", style = MaterialTheme.typography.bodyLarge, color = NotflixColors.Ink2, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp).width(680.dp))
                            if (last != null && resumable) {
                                Column(Modifier.padding(top = 14.dp).width(320.dp)) {
                                    ProgressLine(last.progress / 100.0)
                                    val label = if (last.season != null) "S${last.season}:E${last.episode} · ${last.progress.toInt()}% watched" else "${last.progress.toInt()}% watched"
                                    Text(label, style = MaterialTheme.typography.bodySmall, color = NotflixColors.Ink3, modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                            Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                val playLabel = when {
                                    resumable && last?.season != null -> "Resume S${last.season}:E${last.episode}"
                                    resumable -> "Resume"
                                    else -> "Play"
                                }
                                NotflixButton(playLabel, icon = R.drawable.ic_play, primary = true, modifier = Modifier.focusRequester(playFocus), onClick = {
                                    if (item.isSeries) {
                                        val s = if (resumable) last?.season else null
                                        val e = if (resumable) last?.episode else null
                                        val firstSeason = d.seasons.firstOrNull { it.season > 0 }?.season ?: 1
                                        onPlay(request(s ?: firstSeason, e ?: 1))
                                    } else {
                                        onPlay(request())
                                    }
                                })
                                if (resumable) {
                                    NotflixButton("Start over", icon = R.drawable.ic_restart, onClick = {
                                        onPlay(if (item.isSeries) request(last?.season, last?.episode, start = 0.0) else request(start = 0.0))
                                    })
                                }
                                NotflixButton(
                                    if (inList) "In My List" else "My List",
                                    icon = if (inList) R.drawable.ic_check else R.drawable.ic_add,
                                    enabled = !listBusy,
                                    onClick = {
                                        listBusy = true
                                        scope.launch {
                                            runCatching { app.user.toggleMyList(item.id) }
                                            listBusy = false
                                        }
                                    },
                                )
                            }
                            val people = (if (item.isSeries) d.creators else d.directors).joinToString(", ") { it.name }
                            val cast = d.cast.take(5).joinToString(", ") { it.name }
                            Column(Modifier.padding(top = 20.dp).width(680.dp)) {
                                if (people.isNotEmpty()) Text("${if (item.isSeries) "Created by" else "Director"}: $people", color = NotflixColors.Ink3, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (cast.isNotEmpty()) Text("Cast: $cast", color = NotflixColors.Ink3, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (item.isSeries && d.seasons.isNotEmpty()) {
                        item {
                            LazyRow(contentPadding = PaddingValues(horizontal = 64.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.focusRestorer()) {
                                items(d.seasons, key = { it.season }) { s ->
                                    val selected = s.season == season
                                    Surface(
                                        onClick = { season = s.season },
                                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
                                        colors = ClickableSurfaceDefaults.colors(
                                            containerColor = if (selected) NotflixColors.Ink.copy(alpha = 0.18f) else NotflixColors.Surface2.copy(alpha = 0.7f),
                                            contentColor = NotflixColors.Ink,
                                            focusedContainerColor = NotflixColors.Ink,
                                            focusedContentColor = Color.Black,
                                        ),
                                        border = ClickableSurfaceDefaults.border(
                                            border = if (selected) Border(BorderStroke(1.dp, NotflixColors.LineStrong), shape = RoundedCornerShape(20.dp)) else Border.None,
                                        ),
                                    ) {
                                        Text(s.name.ifBlank { "Season ${s.season}" }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp))
                                    }
                                }
                            }
                        }
                        item {
                            if (episodesLoading && episodes.isEmpty()) {
                                Text("Loading episodes…", color = NotflixColors.Ink3, modifier = Modifier.padding(horizontal = 64.dp))
                            } else {
                                LazyRow(contentPadding = PaddingValues(horizontal = 64.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.focusRestorer()) {
                                    items(episodes, key = { "${it.season}-${it.episode}" }) { ep ->
                                        val key = ProgressRules.episodeKey(item.watchId, ep.season, ep.episode)
                                        val progress = userState.activeProfileId?.let { userState.continueWatchingByProfile[it]?.get(key) }
                                        EpisodeCard(ep, progress, onClick = { onPlay(request(ep.season, ep.episode, ep.name)) })
                                    }
                                }
                            }
                        }
                    }
                    if (d.recommendations.isNotEmpty()) {
                        item {
                            Column {
                                Text("More like this", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 64.dp, bottom = 10.dp))
                                LazyRow(contentPadding = PaddingValues(horizontal = 64.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.focusRestorer()) {
                                    items(d.recommendations.filter { it.tmdbId != null }, key = { it.id }) { rec ->
                                        MediaCard(rec, onClick = { onOpen(rec) })
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(ep: SeasonEpisode, progress: Double?, onClick: () -> Unit) {
    Column(Modifier.width(300.dp)) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = CardDefaults.shape(RoundedCornerShape(6.dp)),
            scale = CardDefaults.scale(focusedScale = 1.06f),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, NotflixColors.Focus), shape = RoundedCornerShape(6.dp))),
            colors = CardDefaults.colors(containerColor = NotflixColors.Surface2),
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(model = NotflixApi.tmdbImage(ep.stillPath, "w780"), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxWidth().height(56.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
                Text("E${ep.episode}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
                if (progress != null && progress > 0) ProgressLine(progress / 100.0, Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        Text(ep.name.ifBlank { "Episode ${ep.episode}" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        val meta = listOfNotNull(formatRuntime(ep.runtime), ep.airDate).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = NotflixColors.Ink4)
        ep.overview?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = NotflixColors.Ink3, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
