package com.notflix.tv

import com.notflix.tv.data.MediaItem
import com.notflix.tv.data.ProgressRules
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same rules as updateProgress() in src/lib/store.ts, so the website reads TV progress. */
class ProgressRulesTest {
    private val movie = MediaItem(id = "tmdb-603", title = "The Matrix", type = "movie", tmdbId = 603, mediaType = "movie", backdropPath = "/b.jpg")
    private val show = MediaItem(id = "tmdb-tv-1399", title = "Game of Thrones", type = "series", tmdbId = 1399, mediaType = "tv")

    @Test fun moviePartialProgressWritesPercentAndResumeEntry() {
        val progress = mutableMapOf<String, JsonElement>("tmdb-other" to JsonPrimitive(10))
        val (p, l) = ProgressRules.apply(progress, mutableMapOf(), movie, null, null, 42.0, 2520.0, 6000.0)
        assertEquals(42.0, p["tmdb-603"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(10, p["tmdb-other"]!!.jsonPrimitive.int) // untouched
        val entry = l["tmdb-603"]!!.jsonObject
        assertEquals(2520.0, entry["positionSeconds"]!!.jsonPrimitive.double, 1e-9)
        assertEquals("movie", entry["item"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("tmdb-603", entry["item"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertFalse(entry.containsKey("season")) // nulls are omitted, like the web store
        assertEquals(42.0, entry["progress"]!!.jsonPrimitive.double, 1e-9)
        assertEquals("movie", entry["item"]!!.jsonObject["mediaType"]!!.jsonPrimitive.content)
    }

    @Test fun episodeProgressUsesEpisodeKeyAndSeriesKey() {
        val (p, l) = ProgressRules.apply(mutableMapOf(), mutableMapOf(), show, 2, 5, 30.0, 900.0, 3000.0)
        assertEquals(30.0, p["tmdb-tv-1399:S2E5"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(30.0, p["tmdb-tv-1399"]!!.jsonPrimitive.double, 1e-9)
        val entry = l["tmdb-tv-1399"]!!.jsonObject
        assertEquals(2, entry["season"]!!.jsonPrimitive.int)
        assertEquals(5, entry["episode"]!!.jsonPrimitive.int)
        assertEquals("series", entry["item"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun finishingRemovesResumePoint() {
        val last = mutableMapOf<String, JsonElement>("tmdb-603" to JsonObject(mapOf("progress" to JsonPrimitive(50))))
        val progress = mutableMapOf<String, JsonElement>("tmdb-603" to JsonPrimitive(50))
        val (p, l) = ProgressRules.apply(progress, last, movie, null, null, 97.0, 5820.0, 6000.0)
        assertFalse(p.containsKey("tmdb-603"))
        assertFalse(l.containsKey("tmdb-603"))
    }

    @Test fun finishingAnEpisodeKeepsResumeForADifferentEpisode() {
        val other = JsonObject(mapOf("season" to JsonPrimitive(3), "episode" to JsonPrimitive(1), "progress" to JsonPrimitive(20)))
        val (_, l) = ProgressRules.apply(mutableMapOf(), mutableMapOf("tmdb-tv-1399" to other), show, 2, 5, 99.0, 2970.0, 3000.0)
        assertTrue(l.containsKey("tmdb-tv-1399"))
        val same = JsonObject(mapOf("season" to JsonPrimitive(2), "episode" to JsonPrimitive(5), "progress" to JsonPrimitive(20)))
        val (_, l2) = ProgressRules.apply(mutableMapOf(), mutableMapOf("tmdb-tv-1399" to same), show, 2, 5, 99.0, 2970.0, 3000.0)
        assertFalse(l2.containsKey("tmdb-tv-1399"))
    }
}
