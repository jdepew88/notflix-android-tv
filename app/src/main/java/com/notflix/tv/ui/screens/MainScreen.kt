package com.notflix.tv.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.notflix.tv.BuildConfig
import com.notflix.tv.R
import com.notflix.tv.container
import com.notflix.tv.data.MediaItem
import com.notflix.tv.data.NotflixApi
import com.notflix.tv.playback.DeviceCapabilities
import com.notflix.tv.playback.PlayRequest
import com.notflix.tv.ui.Tab
import com.notflix.tv.ui.components.ErrorBlock
import com.notflix.tv.ui.components.LoadingBlock
import com.notflix.tv.ui.components.MediaCard
import com.notflix.tv.ui.components.NotflixButton
import com.notflix.tv.ui.components.ProfileAvatar
import com.notflix.tv.ui.components.TvTextField
import com.notflix.tv.ui.components.Wordmark
import com.notflix.tv.ui.theme.NotflixColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val RAIL_COLLAPSED = 76.dp

@Composable
fun MainScreen(
    tab: Tab,
    onTab: (Tab) -> Unit,
    onOpen: (MediaItem) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onSwitchProfile: () -> Unit,
    onSignedOut: () -> Unit,
    onSearchServers: () -> Unit,
    onEnterServerManually: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(start = RAIL_COLLAPSED)) {
            when (tab) {
                Tab.Home, Tab.Movies, Tab.Shows -> BrowseTab(tab, onOpen, onPlay)
                Tab.Search -> SearchTab(onOpen)
                Tab.MyList -> MyListTab(onOpen)
                Tab.Settings -> SettingsTab(onSwitchProfile, onSignedOut, onSearchServers, onEnterServerManually)
            }
        }
        SideRail(tab, onTab, onSwitchProfile)
    }
}

// --- Side rail -----------------------------------------------------------------------------

@Composable
private fun SideRail(selected: Tab, onTab: (Tab) -> Unit, onSwitchProfile: () -> Unit) {
    val app = LocalContext.current.container
    val state by app.user.state.collectAsState()
    val profile = state.profiles.firstOrNull { it.id == state.activeProfileId }
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) 240.dp else RAIL_COLLAPSED, label = "rail")
    val selectedFocus = remember { FocusRequester() }
    val entries = listOf(
        Triple(Tab.Search, "Search", R.drawable.ic_search),
        Triple(Tab.Home, "Home", R.drawable.ic_home),
        Triple(Tab.Shows, "TV Shows", R.drawable.ic_tv),
        Triple(Tab.Movies, "Movies", R.drawable.ic_movie),
        Triple(Tab.MyList, "My List", R.drawable.ic_add),
        Triple(Tab.Settings, "Settings", R.drawable.ic_settings),
    )
    Column(
        Modifier
            .fillMaxHeight()
            .width(width)
            .background(
                if (expanded) Brush.horizontalGradient(0f to Color.Black, 0.85f to Color.Black.copy(alpha = 0.97f), 1f to Color.Black.copy(alpha = 0.9f))
                else Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))
            )
            .onFocusChanged { expanded = it.hasFocus }
            .focusRestorer(selectedFocus)
            .focusGroup()
            .padding(horizontal = 12.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (profile != null) {
            RailItem(
                label = profile.name, expanded = expanded, selected = false, onClick = onSwitchProfile,
                leading = { ProfileAvatar(profile, app.api, 32.dp) },
            )
        }
        Spacer(Modifier.weight(1f))
        entries.forEach { (tab, label, icon) ->
            RailItem(
                label = label, expanded = expanded, selected = tab == selected, onClick = { onTab(tab) },
                modifier = if (tab == selected) Modifier.focusRequester(selectedFocus) else Modifier,
                icon = icon,
            )
        }
        Spacer(Modifier.weight(1.4f))
    }
}

@Composable
private fun RailItem(
    label: String,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(48.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) NotflixColors.Ink else NotflixColors.Ink3,
            focusedContainerColor = NotflixColors.Ink,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (leading != null) leading() else if (icon != null) Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(24.dp))
            }
            if (expanded) {
                Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.padding(start = 18.dp))
            }
            if (selected && !expanded) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(width = 3.dp, height = 18.dp).background(NotflixColors.Brand, RoundedCornerShape(2.dp)))
            }
        }
    }
}

// --- Browse (Home / Movies / TV Shows) -----------------------------------------------------

@Composable
private fun BrowseTab(tab: Tab, onOpen: (MediaItem) -> Unit, onPlay: (PlayRequest) -> Unit) {
    val app = LocalContext.current.container
    val vm: BrowseViewModel = viewModel(key = "browse-$tab") { BrowseViewModel(app, tab) }
    val state by vm.state.collectAsState()
    var focused by remember { mutableStateOf<MediaItem?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshPersonal() }

    val hero = focused ?: state.rows.firstOrNull()?.items?.firstOrNull()
    val restoreFocus = remember { FocusRequester() }
    val targetKey = vm.focusKey ?: state.rows.firstOrNull()?.let { r -> r.items.firstOrNull()?.let { "${r.id}/${it.id}" } }
    // Start on the content (not the side rail) and come back to the card that was open.
    LaunchedEffect(state.rows.isNotEmpty()) {
        if (state.rows.isNotEmpty()) {
            delay(50)
            runCatching { restoreFocus.requestFocus() }
        }
    }
    when {
        state.rows.isEmpty() && state.loading -> LoadingBlock()
        state.rows.isEmpty() -> ErrorBlock("Nothing to show", state.error ?: "The catalog is empty.") {
            NotflixButton("Try again", onClick = vm::load, primary = true)
        }
        else -> Box(Modifier.fillMaxSize()) {
            HeroBackdrop(hero)
            Column(Modifier.fillMaxSize()) {
                HeroInfo(hero, Modifier.fillMaxWidth().height(260.dp))
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.rows, key = { it.id }) { row ->
                        Column {
                            Text(row.title, style = MaterialTheme.typography.titleLarge, color = NotflixColors.Ink, modifier = Modifier.padding(start = 48.dp, bottom = 10.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.focusRestorer(),
                            ) {
                                items(row.items, key = { "${row.id}-${it.id}" }) { item ->
                                    val resume = row.resume[item.id]
                                    val key = "${row.id}/${item.id}"
                                    MediaCard(
                                        item = item,
                                        focusRequester = if (key == targetKey) restoreFocus else null,
                                        progress = resume?.progress,
                                        caption = resume?.let { r ->
                                            if (r.season != null && r.episode != null) "S${r.season}:E${r.episode}" else null
                                        },
                                        onFocused = { focused = item; vm.focusKey = key },
                                        onClick = {
                                            if (resume != null && item.tmdbId != null) {
                                                onPlay(
                                                    PlayRequest(
                                                        tmdbId = item.tmdbId, media = item.tmdbMedia, title = item.title,
                                                        year = item.year?.toIntOrNull(), season = resume.season, episode = resume.episode,
                                                        posterPath = item.posterPath, backdropPath = item.backdropPath, releaseDate = item.releaseDate,
                                                    )
                                                )
                                            } else {
                                                onOpen(item)
                                            }
                                        },
                                    )
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
private fun HeroBackdrop(item: MediaItem?) {
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = NotflixApi.tmdbImage(item?.backdropPath, "w1280"),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopEnd,
            modifier = Modifier.fillMaxWidth(0.72f).fillMaxHeight(0.62f).align(Alignment.TopEnd),
        )
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.0f to NotflixColors.Canvas, 0.38f to NotflixColors.Canvas.copy(alpha = 0.92f), 0.7f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.0f to Color.Transparent, 0.35f to Color.Transparent, 0.6f to NotflixColors.Canvas)))
    }
}

@Composable
private fun HeroInfo(item: MediaItem?, modifier: Modifier) {
    Column(modifier.padding(start = 48.dp, top = 40.dp, end = 48.dp).animateContentSize(), verticalArrangement = Arrangement.Top) {
        if (item == null) return@Column
        Text(item.title, style = MaterialTheme.typography.displayMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(720.dp))
        val meta = listOfNotNull(
            item.year,
            if (item.isSeries) "Series" else "Film",
            item.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
            item.genres.take(3).joinToString(" · ").ifEmpty { null },
        ).joinToString("   ")
        Text(meta, style = MaterialTheme.typography.titleSmall, color = NotflixColors.Ink2, modifier = Modifier.padding(top = 10.dp))
        Text(
            item.overview ?: "",
            style = MaterialTheme.typography.bodyLarge,
            color = NotflixColors.Ink2,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp).width(620.dp),
        )
    }
}

// --- Search --------------------------------------------------------------------------------

@Composable
private fun SearchTab(onOpen: (MediaItem) -> Unit) {
    val app = LocalContext.current.container
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (query.isEmpty()) runCatching { focus.requestFocus() } }
    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = null
            return@LaunchedEffect
        }
        delay(450)
        error = null
        results = try {
            app.api.search(query.trim()).filter { it.tmdbId != null }
        } catch (e: Exception) {
            error = e.message
            emptyList()
        }
    }
    Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 40.dp, end = 48.dp)) {
        TvTextField(
            value = query, onValueChange = { query = it }, label = "Search", imeAction = ImeAction.Search,
            placeholder = "Titles, people, genres", modifier = Modifier.width(560.dp).focusRequester(focus),
        )
        Spacer(Modifier.height(24.dp))
        when {
            error != null -> Text(error!!, color = NotflixColors.BrandHover)
            results == null -> Text("Type at least two letters.", color = NotflixColors.Ink4)
            results!!.isEmpty() -> Text("No titles match \"$query\".", color = NotflixColors.Ink3)
            else -> ItemGrid(results!!, onOpen)
        }
    }
}

@Composable
private fun ItemGrid(items: List<MediaItem>, onOpen: (MediaItem) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { item ->
            MediaCard(item = item, onClick = { onOpen(item) }, width = 220.dp, caption = listOfNotNull(item.year, if (item.isSeries) "Series" else "Film").joinToString(" · "))
        }
    }
}

// --- My List -------------------------------------------------------------------------------

@Composable
private fun MyListTab(onOpen: (MediaItem) -> Unit) {
    val app = LocalContext.current.container
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            app.user.refreshState()
            items = app.api.lookup(app.user.myList().reversed())
        } catch (e: Exception) {
            error = e.message ?: "Could not load My List."
            items = emptyList()
        }
    }
    Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 40.dp, end = 48.dp)) {
        Text("My List", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(bottom = 24.dp))
        when {
            items == null -> LoadingBlock()
            error != null -> Text(error!!, color = NotflixColors.BrandHover)
            items!!.isEmpty() -> Text("Titles you add to My List appear here — on the TV and on the website.", color = NotflixColors.Ink3)
            else -> ItemGrid(items!!, onOpen)
        }
    }
}

// --- Settings ------------------------------------------------------------------------------

@Composable
private fun SettingsTab(onSwitchProfile: () -> Unit, onSignedOut: () -> Unit, onSearchServers: () -> Unit, onEnterServerManually: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val account by app.user.account.collectAsState()
    val state by app.user.state.collectAsState()
    val profile = state.profiles.firstOrNull { it.id == state.activeProfileId }
    val caps = remember { DeviceCapabilities.get(context) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    LazyColumn(Modifier.fillMaxSize().padding(start = 48.dp, top = 40.dp, end = 64.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        item { Wordmark(height = 30.dp) }
        item {
            Section("Account") {
                Text("${account?.name ?: "—"}  ·  ${account?.role ?: ""}", style = MaterialTheme.typography.titleMedium)
                Text("Profile: ${profile?.name ?: "none"}${if (profile?.isKids == true) " (Kids)" else ""}", color = NotflixColors.Ink3, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    NotflixButton("Switch profile", onClick = onSwitchProfile, modifier = Modifier.focusRequester(first), icon = R.drawable.ic_person)
                    NotflixButton("Sign out", onClick = {
                        scope.launch {
                            app.api.logout()
                            app.user.clear()
                            onSignedOut()
                        }
                    })
                }
            }
        }
        item {
            Section("Server Connection") {
                ServerConnectionSettings(onSearch = onSearchServers, onManual = onEnterServerManually)
            }
        }
        item {
            Section("Playback on this TV") {
                Text("Video decoders (hardware): ${caps.directVideo.joinToString(", ") { it.uppercase() }}", style = MaterialTheme.typography.bodyLarge)
                Text("10-bit: ${caps.tenBit.joinToString(", ") { it.uppercase() }.ifEmpty { "none" }}", style = MaterialTheme.typography.bodyLarge)
                Text("Audio (decode or passthrough): ${caps.directAudio.joinToString(", ") { it.uppercase() }}", style = MaterialTheme.typography.bodyLarge)
                Text("Display: up to ${caps.maxHeight}p${if (caps.hdrDisplay) " · " + caps.hdrTypes.joinToString(", ") else " · SDR"}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Notflix plays the original file whenever this TV can decode it (Direct Play). The server's media engine only repackages or converts what this TV can't play.",
                    color = NotflixColors.Ink3, modifier = Modifier.padding(top = 8.dp).width(760.dp),
                )
            }
        }
        item {
            Text("Notflix for Android TV ${BuildConfig.VERSION_NAME} · TMDB artwork and metadata", color = NotflixColors.Ink4, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 48.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = NotflixColors.Ink, modifier = Modifier.padding(bottom = 10.dp))
        content()
    }
}
