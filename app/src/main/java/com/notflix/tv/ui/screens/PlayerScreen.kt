package com.notflix.tv.ui.screens

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.notflix.tv.R
import com.notflix.tv.container
import com.notflix.tv.data.EngineTrack
import com.notflix.tv.data.NotflixApi
import com.notflix.tv.playback.PlayRequest
import com.notflix.tv.playback.PlayerPhase
import com.notflix.tv.playback.PlayerUiState
import com.notflix.tv.playback.PlayerViewModel
import com.notflix.tv.ui.components.Badge
import com.notflix.tv.ui.hideKeyboard
import com.notflix.tv.ui.components.ErrorBlock
import com.notflix.tv.ui.components.NotflixButton
import com.notflix.tv.ui.components.Spinner
import com.notflix.tv.ui.components.formatTime
import com.notflix.tv.ui.theme.NotflixColors
import com.notflix.tv.ui.theme.SubtitleStyle
import kotlinx.coroutines.delay

private enum class Panel { None, Audio, Subtitles, Sources, Info }

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(request: PlayRequest, onExit: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val vm: PlayerViewModel = viewModel { PlayerViewModel(app, context.applicationContext, request) }
    val ui by vm.ui.collectAsState()
    var controls by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(Panel.None) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val rootFocus = remember { FocusRequester() }
    val scrubFocus = remember { FocusRequester() }
    val upNextFocus = remember { FocusRequester() }
    val playing = ui.phase == PlayerPhase.Playing

    fun exit() {
        vm.stop()
        onExit()
    }

    fun poke() {
        lastInput = System.currentTimeMillis()
        controls = true
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onBackground() }
    val view = LocalView.current
    LaunchedEffect(Unit) { hideKeyboard(view) }

    // Hide the controls after 5 s without input while playing.
    LaunchedEffect(controls, lastInput, ui.isPlaying, panel, ui.phase) {
        if (controls && ui.isPlaying && panel == Panel.None && playing) {
            delay(5_000)
            controls = false
        }
    }
    LaunchedEffect(controls, panel, ui.phase, ui.upNext != null) {
        when {
            ui.upNext != null -> runCatching { upNextFocus.requestFocus() }
            ui.phase != PlayerPhase.Playing -> {}
            !controls -> runCatching { rootFocus.requestFocus() }
            panel == Panel.None -> runCatching { scrubFocus.requestFocus() }
        }
    }
    LaunchedEffect(ui.toast) {
        if (ui.toast != null) {
            delay(5_000)
            vm.dismissToast()
        }
    }

    BackHandler {
        when {
            panel != Panel.None -> panel = Panel.None
            ui.upNext != null -> vm.cancelUpNext()
            controls && playing && ui.isPlaying -> controls = false
            else -> exit()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = e.key.nativeKeyCode
                val repeat = e.nativeKeyEvent.repeatCount
                when (code) {
                    NativeKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { vm.togglePlay(); poke(); return@onPreviewKeyEvent true }
                    NativeKeyEvent.KEYCODE_MEDIA_PLAY -> { vm.player.play(); poke(); return@onPreviewKeyEvent true }
                    NativeKeyEvent.KEYCODE_MEDIA_PAUSE -> { vm.player.pause(); poke(); return@onPreviewKeyEvent true }
                    NativeKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { vm.seekBy(30.0); poke(); return@onPreviewKeyEvent true }
                    NativeKeyEvent.KEYCODE_MEDIA_REWIND -> { vm.seekBy(-30.0); poke(); return@onPreviewKeyEvent true }
                }
                if (!playing || panel != Panel.None || ui.upNext != null) return@onPreviewKeyEvent false
                if (controls) {
                    lastInput = System.currentTimeMillis()
                    return@onPreviewKeyEvent false
                }
                when (code) {
                    NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER, NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> { vm.togglePlay(); poke(); true }
                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> { vm.seekBy(-seekStep(repeat)); poke(); true }
                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> { vm.seekBy(seekStep(repeat)); poke(); true }
                    NativeKeyEvent.KEYCODE_DPAD_UP, NativeKeyEvent.KEYCODE_DPAD_DOWN -> { poke(); true }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    player = vm.player
                    keepScreenOn = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    subtitleView?.visibility = android.view.View.GONE
                    isFocusable = false
                }
            },
            update = { it.player = vm.player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        // Subtitles (engine WebVTT, drawn by the app so they look the same in every playback mode).
        ui.cueText?.let { text ->
            Text(
                text,
                style = SubtitleStyle.copy(shadow = Shadow(Color.Black, Offset(0f, 2f), 6f)),
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (controls && playing) 190.dp else 56.dp, start = 120.dp, end = 120.dp)
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }

        if (playing && ui.buffering) Spinner(Modifier.align(Alignment.Center))

        when (ui.phase) {
            PlayerPhase.Starting -> StartingOverlay(request, ui)
            PlayerPhase.Error -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f))) {
                val retryFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
                ErrorBlock("Can't play this title", ui.error, details = ui.errorDetails) {
                    NotflixButton("Try again", onClick = vm::retry, primary = true, modifier = Modifier.focusRequester(retryFocus))
                    NotflixButton("Choose another source", onClick = { panel = Panel.Sources; vm.loadSources() })
                    NotflixButton("Back", onClick = ::exit)
                }
            }
            PlayerPhase.Playing -> {}
        }

        AnimatedVisibility(visible = controls && playing && panel == Panel.None && ui.upNext == null, enter = fadeIn(), exit = fadeOut()) {
            Controls(
                ui = ui,
                request = request,
                scrubFocus = scrubFocus,
                onSeek = { vm.seekBy(it); lastInput = System.currentTimeMillis() },
                onTogglePlay = { vm.togglePlay(); lastInput = System.currentTimeMillis() },
                onPanel = { panel = it; if (it == Panel.Sources) vm.loadSources() },
                onNextEpisode = vm::skipToNextEpisode,
            )
        }

        ui.upNext?.let { next ->
            Column(
                Modifier.align(Alignment.BottomEnd).padding(48.dp).width(420.dp)
                    .background(NotflixColors.Surface.copy(alpha = 0.95f), RoundedCornerShape(12.dp)).padding(24.dp),
            ) {
                Text("Next episode in ${next.secondsLeft} s", color = NotflixColors.Ink3, style = MaterialTheme.typography.bodyMedium)
                Text("S${next.season}:E${next.episode} · ${next.name}", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NotflixButton("Play now", onClick = vm::playNext, primary = true, icon = R.drawable.ic_play, modifier = Modifier.focusRequester(upNextFocus))
                    NotflixButton("Cancel", onClick = vm::cancelUpNext)
                }
            }
        }

        if (panel != Panel.None) {
            SidePanel(panel, ui, vm, onClose = { panel = Panel.None })
        }

        ui.toast?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp)
                    .background(NotflixColors.Surface.copy(alpha = 0.95f), RoundedCornerShape(8.dp)).padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

private fun seekStep(repeat: Int): Double = when {
    repeat > 20 -> 60.0
    repeat > 6 -> 30.0
    else -> 10.0
}

@Composable
private fun StartingOverlay(request: PlayRequest, ui: PlayerUiState) {
    Box(Modifier.fillMaxSize()) {
        AsyncImage(model = NotflixApi.tmdbImage(request.backdropPath, "w1280"), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(request.title, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            ui.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = NotflixColors.Ink2, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(32.dp))
            Spinner()
            Text(ui.status, color = NotflixColors.Ink3, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 18.dp))
        }
    }
}

@Composable
private fun Controls(
    ui: PlayerUiState,
    request: PlayRequest,
    scrubFocus: FocusRequester,
    onSeek: (Double) -> Unit,
    onTogglePlay: () -> Unit,
    onPanel: (Panel) -> Unit,
    onNextEpisode: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(200.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent))))
        Box(Modifier.fillMaxWidth().height(260.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)))))

        Column(Modifier.padding(start = 56.dp, top = 40.dp, end = 56.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(request.title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(16.dp))
                modeBadge(ui)?.let { (label, color) -> Badge(label, color = color) }
            }
            ui.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = NotflixColors.Ink2, modifier = Modifier.padding(top = 4.dp)) }
            ui.notices.take(2).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = NotflixColors.Ink3, modifier = Modifier.padding(top = 4.dp)) }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 56.dp, vertical = 36.dp)) {
            val playFocus = remember { FocusRequester() }
            Scrubber(ui, scrubFocus, onSeek, onTogglePlay, Modifier.focusProperties { down = playFocus })
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ControlButton(if (ui.isPlaying) R.drawable.ic_pause else R.drawable.ic_play, if (ui.isPlaying) "Pause" else "Play", Modifier.focusRequester(playFocus), onTogglePlay)
                ControlButton(R.drawable.ic_rewind, "Back 10 seconds") { onSeek(-10.0) }
                ControlButton(R.drawable.ic_forward, "Forward 10 seconds") { onSeek(10.0) }
                if (request.isTv) ControlButton(R.drawable.ic_next, "Next episode", onClick = onNextEpisode)
                Spacer(Modifier.weight(1f))
                if ((ui.session?.tracks?.audio?.size ?: 0) > 1) ControlButton(R.drawable.ic_audio, "Audio") { onPanel(Panel.Audio) }
                if (ui.session?.tracks?.subtitles?.isNotEmpty() == true) ControlButton(R.drawable.ic_subtitles, "Subtitles") { onPanel(Panel.Subtitles) }
                ControlButton(R.drawable.ic_source, "Change video source") { onPanel(Panel.Sources) }
                ControlButton(R.drawable.ic_info, "Stream information") { onPanel(Panel.Info) }
            }
        }
    }
}

private fun modeBadge(ui: PlayerUiState): Pair<String, Color>? {
    val s = ui.session ?: return if (ui.info?.modeLabel?.isNotEmpty() == true) "Direct" to NotflixColors.Surface3 else null
    val color = when (s.mode) {
        "DirectPlay" -> Color(0xFF1F7A3A)
        "Remux" -> Color(0xFF1E5AA8)
        "AudioTranscode" -> Color(0xFF8A6D1D)
        else -> NotflixColors.BrandDeep
    }
    return s.modeLabel.ifBlank { s.mode } to color
}

@Composable
private fun Scrubber(ui: PlayerUiState, focus: FocusRequester, onSeek: (Double) -> Unit, onTogglePlay: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shown = ui.seekPreview ?: ui.position
    val duration = ui.duration
    val fraction = if (duration > 0) (shown / duration).coerceIn(0.0, 1.0) else 0.0
    val buffered = if (duration > 0) (ui.buffered / duration).coerceIn(0.0, 1.0) else 0.0
    Column(
        modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key.nativeKeyCode) {
                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> { onSeek(-seekStep(e.nativeKeyEvent.repeatCount)); true }
                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> { onSeek(seekStep(e.nativeKeyEvent.repeatCount)); true }
                    NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER -> { onTogglePlay(); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.CenterStart) {
            val barHeight = if (focused) 6.dp else 4.dp
            Box(Modifier.fillMaxWidth().height(barHeight).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.25f)))
            Box(Modifier.fillMaxWidth(buffered.toFloat()).height(barHeight).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.4f)))
            Box(Modifier.fillMaxWidth(fraction.toFloat()).height(barHeight).clip(RoundedCornerShape(3.dp)).background(NotflixColors.Brand))
            if (focused) {
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier.offset(x = maxWidth * fraction.toFloat() - 9.dp).size(18.dp).clip(CircleShape).background(Color.White)
                            .border(2.dp, NotflixColors.Brand, CircleShape)
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(formatTime(shown), style = MaterialTheme.typography.titleSmall, color = if (ui.seekPreview != null) NotflixColors.Ink else NotflixColors.Ink2)
            Spacer(Modifier.weight(1f))
            if (duration > 0) Text("-" + formatTime(duration - shown), style = MaterialTheme.typography.titleSmall, color = NotflixColors.Ink3)
        }
    }
}

@Composable
private fun ControlButton(@DrawableRes icon: Int, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(52.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = NotflixColors.Ink,
            focusedContainerColor = NotflixColors.Ink,
            focusedContentColor = Color.Black,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(28.dp))
        }
    }
}

// --- Side panels -----------------------------------------------------------------------------

@Composable
private fun SidePanel(panel: Panel, ui: PlayerUiState, vm: PlayerViewModel, onClose: () -> Unit) {
    val first = remember(panel) { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(460.dp)
                .background(NotflixColors.Surface.copy(alpha = 0.97f)).padding(start = 28.dp, end = 28.dp, top = 40.dp, bottom = 24.dp),
        ) {
            val title = when (panel) {
                Panel.Audio -> "Audio"
                Panel.Subtitles -> "Subtitles"
                Panel.Sources -> "Change video source"
                Panel.Info -> "Stream information"
                Panel.None -> ""
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 18.dp))
            val tracks = ui.session?.tracks
            when (panel) {
                Panel.Audio -> OptionList(
                    first,
                    (tracks?.audio ?: emptyList()).map { t -> Option(trackLabel(t, audio = true), null, t.index == ui.selectedAudio) { vm.selectAudio(t.index); onClose() } },
                )
                Panel.Subtitles -> OptionList(
                    first,
                    listOf(Option("Off", null, ui.selectedSubtitle == null) { vm.selectSubtitle(null); onClose() }) +
                        (tracks?.subtitles ?: emptyList()).filter { it.kind != "other" }.map { t ->
                            Option(trackLabel(t, audio = false), if (t.kind == "image") "Drawn into the picture by the server" else null, t.index == ui.selectedSubtitle) {
                                vm.selectSubtitle(t.index); onClose()
                            }
                        },
                )
                Panel.Sources -> when {
                    ui.sourcesLoading -> Text("Looking up releases…", color = NotflixColors.Ink3)
                    ui.sourcesError != null && ui.sources.isNullOrEmpty() -> Text(ui.sourcesError, color = NotflixColors.BrandHover)
                    ui.sources.isNullOrEmpty() -> Text("No other releases were found.", color = NotflixColors.Ink3)
                    else -> OptionList(first, ui.sources.map { c ->
                        val v = c.verified
                        val facts = if (v != null) {
                            listOfNotNull(
                                v.container?.uppercase(),
                                v.video?.codec?.uppercase(),
                                v.video?.height?.let { "${it}p" },
                                v.video?.dynamicRange?.takeIf { it != "SDR" },
                                v.audioLanguages.takeIf { it.isNotEmpty() }?.joinToString("/") { it.uppercase() }?.let { "Audio $it" },
                            ).joinToString(" · ")
                        } else {
                            listOfNotNull(c.quality, c.detail?.take(80), if (c.cached) "Cached" else null).joinToString(" · ")
                        }
                        Option(c.label.take(90), facts.ifBlank { null }, c.index == ui.currentSourceIndex) { vm.selectSource(c.index); onClose() }
                    })
                }
                Panel.Info -> InfoList(ui, first, onClose)
                Panel.None -> {}
            }
        }
    }
    LaunchedEffect(panel, ui.sources, ui.sourcesLoading) { runCatching { first.requestFocus() } }
}

private data class Option(val label: String, val detail: String?, val selected: Boolean, val onClick: () -> Unit)

@Composable
private fun OptionList(first: FocusRequester, options: List<Option>) {
    val initial = options.indexOfFirst { it.selected }.coerceAtLeast(0)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(options.size) { i ->
            val o = options[i]
            Surface(
                onClick = o.onClick,
                modifier = Modifier.fillMaxWidth().then(if (i == initial) Modifier.focusRequester(first) else Modifier),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (o.selected) Color.White.copy(alpha = 0.1f) else Color.Transparent,
                    contentColor = NotflixColors.Ink,
                    focusedContainerColor = NotflixColors.Ink,
                    focusedContentColor = Color.Black,
                ),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(o.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        o.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
                    }
                    if (o.selected) Icon(painterResource(R.drawable.ic_check), contentDescription = "Selected", modifier = Modifier.size(20.dp).padding(start = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun InfoList(ui: PlayerUiState, first: FocusRequester, onClose: () -> Unit) {
    val info = ui.info
    val rows = buildList {
        if (info != null) {
            add("Playback" to info.modeLabel.ifBlank { "—" })
            info.provider?.let { add("Source" to it) }
            info.release?.let { add("Release" to it) }
            info.sourceVideo?.let { add("Original video" to it) }
            info.outputVideo?.let { add("Decoding" to it) }
            info.videoDecoder?.let { add("Video decoder" to it) }
            info.audioFormat?.let { add("Audio" to it) }
            info.audioDecoder?.let { add("Audio decoder" to it) }
            info.pipeline?.let { add("Server conversion" to it) }
            info.reasons.forEach { add("Why" to it) }
            add("This TV" to info.deviceSummary)
        }
        ui.notices.forEach { add("Note" to it) }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(rows) { (k, v) ->
            Column {
                Text(k, style = MaterialTheme.typography.labelLarge, color = NotflixColors.Ink4)
                Text(v, style = MaterialTheme.typography.bodyMedium, color = NotflixColors.Ink2)
            }
        }
        item {
            // A focus target so the panel can be dismissed with OK as well as Back.
            Box(Modifier.padding(top = 12.dp)) {
                NotflixButton("Close", onClick = onClose, modifier = Modifier.focusRequester(first))
            }
        }
    }
}

/** Same wording as trackLabel() in src/lib/playback-client.ts. */
private fun trackLabel(t: EngineTrack, audio: Boolean): String {
    val lang = t.languageCode?.let(::languageName) ?: t.language?.takeIf { it != "und" }?.let(::languageName) ?: "Unknown language"
    val parts = mutableListOf(lang)
    if (t.title != null && !t.title.lowercase().contains(lang.lowercase())) parts += t.title
    if (audio) {
        parts += t.codec.uppercase().replace("EAC3", "E-AC-3").replace("AC3", "AC-3")
        t.channels?.let { parts += if (it >= 8) "7.1" else if (it >= 6) "5.1" else if (it == 1) "Mono" else "Stereo" }
    } else {
        if (t.forced) parts += "Forced"
        if (t.kind == "image") parts += "(image)"
    }
    return parts.joinToString(" · ")
}

private val LANGS = mapOf(
    "en" to "English", "eng" to "English", "es" to "Spanish", "spa" to "Spanish", "fr" to "French", "fre" to "French", "fra" to "French",
    "de" to "German", "ger" to "German", "deu" to "German", "it" to "Italian", "ita" to "Italian", "ja" to "Japanese", "jpn" to "Japanese",
    "ko" to "Korean", "kor" to "Korean", "zh" to "Chinese", "chi" to "Chinese", "zho" to "Chinese", "pt" to "Portuguese", "por" to "Portuguese",
    "ru" to "Russian", "rus" to "Russian", "hi" to "Hindi", "hin" to "Hindi", "nl" to "Dutch", "dut" to "Dutch", "sv" to "Swedish", "swe" to "Swedish",
    "und" to "Unknown",
)

private fun languageName(code: String) = LANGS[code.lowercase()] ?: code.uppercase()
