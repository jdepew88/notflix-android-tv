package com.notflix.tv.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notflix.tv.AppContainer
import com.notflix.tv.data.ApiException
import com.notflix.tv.data.LastWatchedEntry
import com.notflix.tv.data.MediaItem
import com.notflix.tv.ui.Tab
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

data class BrowseRow(
    val id: String,
    val title: String,
    val items: List<MediaItem>,
    /** Continue Watching: progress per item id (and the resume entry). */
    val resume: Map<String, LastWatchedEntry> = emptyMap(),
)

data class BrowseState(
    val rows: List<BrowseRow> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/** Rows for Home / Movies / TV Shows, from the same catalog endpoints the website's home uses. */
class BrowseViewModel(private val app: AppContainer, private val tab: Tab) : ViewModel() {

    private data class RowDef(val id: String, val title: String, val query: String)

    /** Key ("rowId/itemId") of the card that had focus, restored when the viewer comes back. */
    var focusKey: String? = null

    private val _state = MutableStateFlow(BrowseState())
    val state: StateFlow<BrowseState> = _state.asStateFlow()

    private val defs: List<RowDef> = when (tab) {
        Tab.Movies -> listOf(
            RowDef("popular", "Popular Movies", "type=popular"),
            RowDef("now", "New in Theaters", "type=now_playing"),
            RowDef("digital", "New on Digital", "type=digital"),
            RowDef("top", "Top Rated Movies", "type=top_rated"),
            RowDef("upcoming", "Coming Soon", "type=upcoming"),
        ) + listOf("action", "comedy", "science-fiction", "horror", "thriller", "animation", "drama", "family", "documentary")
            .map { genreDef(it, "movie") }
        Tab.Shows -> listOf(
            RowDef("popular_tv", "Popular TV Shows", "type=popular_tv"),
            RowDef("trending_tv", "Trending TV This Week", "type=trending_tv"),
            RowDef("top_tv", "Top Rated TV Shows", "type=top_rated_tv"),
            RowDef("airing", "New Episodes Today", "type=airing_today"),
            RowDef("on_air", "Recently Aired Episodes", "type=on_the_air"),
        ) + listOf("action", "comedy", "science-fiction", "animation", "crime", "drama", "mystery", "documentary")
            .map { genreDef(it, "tv") }
        // Same order as HOME_TMDB_ROWS_INITIAL / _MORE in src/lib/home-rows.ts.
        else -> listOf(
            RowDef("trending-today", "Trending Today", "type=trending_day"),
            RowDef("trending", "Trending This Week", "type=trending"),
            RowDef("popular", "Popular Movies", "type=popular"),
            RowDef("popular_tv", "Popular TV Shows", "type=popular_tv"),
            RowDef("now", "New in Theaters", "type=now_playing"),
            RowDef("digital", "New on Digital", "type=digital"),
            RowDef("trending_tv", "Trending TV This Week", "type=trending_tv"),
            genreDef("action", "all"),
            genreDef("comedy", "all"),
            RowDef("top", "Top Rated Movies", "type=top_rated"),
            genreDef("science-fiction", "all"),
            genreDef("horror", "all"),
            genreDef("thriller", "all"),
            genreDef("animation", "all"),
            RowDef("airing", "New Episodes Today", "type=airing_today"),
            RowDef("upcoming", "Coming Soon", "type=upcoming"),
            RowDef("top_tv", "Top Rated TV Shows", "type=top_rated_tv"),
        )
    }

    private fun genreDef(slug: String, media: String): RowDef {
        val name = slug.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        return RowDef("genre-$slug-$media", name, "type=genre&g=$slug&media=$media")
    }

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val personal = if (tab == Tab.Home) personalRows() else emptyList()
            if (personal.isNotEmpty()) _state.update { it.copy(rows = personal) }
            val gate = Semaphore(4)
            val results = arrayOfNulls<BrowseRow>(defs.size)
            var firstError: String? = null
            defs.mapIndexed { i, def ->
                async {
                    gate.withPermit {
                        try {
                            results[i] = BrowseRow(def.id, def.title, app.api.catalog("${def.query}&page=1").items)
                        } catch (e: ApiException) {
                            firstError = firstError ?: e.message
                        } catch (e: IOException) {
                            firstError = firstError ?: "Can't reach the Notflix server."
                        }
                        publish(personal, results)
                    }
                }
            }.awaitAll()
            _state.update { it.copy(loading = false, error = if (it.rows.isEmpty()) firstError else null) }
        }
    }

    /** Re-reads Continue Watching / My List (after playback or a list change). */
    fun refreshPersonal() {
        if (tab != Tab.Home) return
        viewModelScope.launch {
            runCatching { app.user.refreshState() }
            val personal = personalRows()
            _state.update { s -> s.copy(rows = personal + s.rows.filter { r -> r.id != CONTINUE && r.id != MY_LIST }) }
        }
    }

    private suspend fun personalRows(): List<BrowseRow> {
        val rows = mutableListOf<BrowseRow>()
        val resume = app.user.continueWatching()
        if (resume.isNotEmpty()) {
            rows += BrowseRow(CONTINUE, "Continue Watching", resume.map { it.first }, resume.associate { it.first.id to it.second })
        }
        val list = app.user.myList()
        if (list.isNotEmpty()) {
            val items = runCatching { app.api.lookup(list.reversed()) }.getOrDefault(emptyList())
            if (items.isNotEmpty()) rows += BrowseRow(MY_LIST, "My List", items)
        }
        return rows
    }

    /** Rows in definition order; a title shows in the first row that has it (as on the website). */
    private fun publish(personal: List<BrowseRow>, results: Array<BrowseRow?>) {
        val seen = mutableSetOf<String>()
        val rows = results.filterNotNull().mapNotNull { row ->
            val fresh = row.items.filter { it.id !in seen && (it.backdropPath != null || it.posterPath != null) }
            seen += fresh.map { it.id }
            row.copy(items = fresh).takeIf { fresh.size >= 4 }
        }
        _state.update { it.copy(rows = personal + rows) }
    }

    companion object {
        const val CONTINUE = "continue"
        const val MY_LIST = "mylist"
    }
}
