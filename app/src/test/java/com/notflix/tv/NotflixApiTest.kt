package com.notflix.tv

import com.notflix.tv.data.MediaItem
import com.notflix.tv.data.NotflixApi
import com.notflix.tv.data.PersistentCookieJar
import com.notflix.tv.data.SelectProfileResult
import com.notflix.tv.data.SavedServer
import com.notflix.tv.data.ServerConfig
import com.notflix.tv.data.ServerOrigin
import com.notflix.tv.data.UserRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The client against recorded server answers (shapes from src/app/api and the engine's Views.cs). */
class NotflixApiTest {
    private val server = MockWebServer()
    private lateinit var prefs: FakePrefs
    private lateinit var api: NotflixApi

    @Before fun setUp() {
        server.start()
        prefs = FakePrefs()
        val url = server.url("/").toString().trimEnd('/')
        val config = ServerConfig(prefs).apply { save(SavedServer(url)) }
        val jar = PersistentCookieJar(prefs).apply { bindTo(ServerOrigin.of(url)!!) }
        api = NotflixApi(OkHttpClient.Builder().cookieJar(jar).build(), config, jar)
    }

    @After fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200, headers: List<Pair<String, String>> = emptyList()) =
        MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
        }.build()

    @Test fun loginStoresSessionCookieAndSendsItBack() = runTest {
        server.enqueue(
            json(
                """{"user":{"id":"u1","name":"joe","role":"owner"}}""",
                headers = listOf(
                    "Set-Cookie" to "notflix_session=u1.123.0.sig; Path=/; HttpOnly; Max-Age=2592000; SameSite=Lax",
                    "Set-Cookie" to "notflix_profile=; Path=/; Max-Age=0",
                ),
            )
        )
        server.enqueue(json("""{"user":{"id":"u1","name":"joe","role":"owner","isAdmin":true},"state":{"profiles":[{"id":"p1","name":"Joe","avatar":"builtin:v2-space-astronaut","hasPin":true}],"activeProfileId":null},"maturity":"all"}"""))

        val user = api.login("joe", "secret-password")
        assertEquals("u1", user.id)
        val login = server.takeRequest()
        assertEquals("/api/auth/login", login.url.encodedPath)
        val body = Json.parseToJsonElement(login.body!!.utf8()).jsonObject
        assertEquals("\"joe\"", body["name"].toString())
        assertEquals("true", body["remember"].toString())
        assertTrue(api.hasSession())

        val me = api.me()!!
        assertEquals("joe", me.user!!.name)
        assertTrue(me.state!!.profiles.single().hasPin)
        assertEquals("notflix_session=u1.123.0.sig", server.takeRequest().headers["Cookie"])

        // The cookie survives an app restart (a new jar over the same storage), still bound to this server.
        val restarted = PersistentCookieJar(prefs)
        assertTrue(restarted.hasCookie("notflix_session"))
        assertEquals(ServerOrigin.of(server.url("/")), restarted.origin)
    }

    @Test fun meReturnsNullWhenSignedOut() = runTest {
        server.enqueue(json("""{"user":null}""", 401))
        assertNull(api.me())
    }

    @Test fun pinRequiredIsReportedNotThrown() = runTest {
        server.enqueue(json("""{"error":"This profile is locked with a PIN.","code":"pin_required"}""", 403))
        val r = api.selectProfile("p1")
        assertTrue(r is SelectProfileResult.PinRequired)
        val req = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("\"select\"", req["action"].toString())
        assertFalse(req.containsKey("pin"))
    }

    @Test fun playbackRefusalIsReturnedWithItsMessage() = runTest {
        server.enqueue(json("""{"ok":false,"error":"No Real-Debrid sources found for this title.","code":"no-source","details":["a","b"]}""", 404))
        val res = api.startPlayback(buildJsonObject { put("tmdbId", 1) })
        assertFalse(res.ok)
        assertEquals("no-source", res.code)
        assertEquals(2, res.details.size)
    }

    @Test fun playbackSessionParses() = runTest {
        server.enqueue(
            json(
                """
                {"ok":true,"engine":"online","session":{"id":"0123456789abcdef01234567","token":null,"mode":"Remux","modeLabel":"Direct Stream",
                 "output":"hls","generation":2,"generationStart":120.5,"durationSeconds":7200.0,"sourceStartTime":0,"startupMs":800,"probeMs":300,
                 "state":"Running","decision":{"video":"Copy","audio":"Copy","outputVideoCodec":"hevc","outputAudioCodec":"eac3","width":3840,"height":2160,
                 "targetVideoKbps":null,"audioChannels":null,"pipeline":null,"hardwareDecode":null,"hardwareEncode":null,"tonemap":null,"burnSubtitleIndex":null,
                 "reasons":["MKV container is not playable directly in browsers"]},
                 "tracks":{"audio":[{"index":1,"codec":"eac3","language":"eng","title":null,"channels":6,"channelLayout":"5.1(side)","default":true,"sampleRate":48000,"bitrateKbps":640,"languageCode":"en"}],
                 "subtitles":[{"index":3,"codec":"subrip","language":"eng","title":null,"forced":false,"default":false,"kind":"text","languageCode":"en"}],
                 "selectedAudio":1,"selectedAudioLanguage":"en","audioLanguageMatched":true,"availableAudioLanguages":["en"],"selectedSubtitle":null},
                 "source":{"provider":"real-debrid","title":"X","fileName":"x.mkv","fileSize":123,"container":"matroska","formatName":"matroska,webm","durationSeconds":7200,"bitrateKbps":40000,
                 "video":{"codec":"hevc","profile":"Main 10","level":153,"width":3840,"height":2160,"pixelFormat":"yuv420p10le","bitDepth":10,"frameRate":23.976,"bitrateKbps":0,"dynamicRange":"HDR10","colorTransfer":"smpte2084","colorPrimaries":"bt2020","dolbyVisionProfile":null}}},
                 "urls":{"manifest":"/api/playback/0123456789abcdef01234567/manifest?generation=2","cast":"/x?ct=1"},"provider":"real-debrid","candidateIndex":0,
                 "candidatesTried":1,"defaultSubtitleIndex":3,"audio":{"selectedLanguage":"en","matched":true,"available":["en"],"preferred":["en"]},
                 "timing":{"resolveMs":1,"linkMs":2,"engineMs":3,"totalMs":6},"notices":[]}
                """.trimIndent()
            )
        )
        val res = api.startPlayback(JsonObject(emptyMap()))
        val s = res.session!!
        assertTrue(s.isHls)
        assertEquals(120.5, s.generationStart, 1e-9)
        assertTrue(s.tracks.audio.single().isDefault)
        assertEquals("text", s.tracks.subtitles.single().kind)
        assertEquals(10, s.source!!.video!!.bitDepth)
        assertEquals(3, res.defaultSubtitleIndex)
    }

    @Test fun progressPatchTouchesOnlyTheActiveProfile() = runTest {
        val state = """{"state":{"profiles":[{"id":"p1","name":"A"},{"id":"p2","name":"B"}],"activeProfileId":"p1",
            "myListByProfile":{"p1":["tmdb-1"]},"continueWatchingByProfile":{"p1":{"tmdb-9":12.5,"custom":1}},
            "lastWatchedByProfile":{"p1":{"tmdb-9":{"progress":12.5,"updatedAt":1,"futureField":"kept"}}}}}"""
        repeat(3) { server.enqueue(json(state)) } // refreshState, the read inside saveProgress, the PUT answer
        val repo = UserRepository(api)
        repo.refreshState()
        repo.saveProgress(MediaItem(id = "tmdb-603", title = "The Matrix", tmdbId = 603, mediaType = "movie"), null, null, 600.0, 6000.0)
        server.takeRequest()
        server.takeRequest()
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        val patch = Json.parseToJsonElement(put.body!!.utf8()).jsonObject
        assertEquals(setOf("continueWatchingByProfile", "lastWatchedByProfile"), patch.keys)
        val progress = patch["continueWatchingByProfile"]!!.jsonObject
        assertEquals(setOf("p1"), progress.keys)
        val p1 = progress["p1"]!!.jsonObject
        assertEquals("10.0", p1["tmdb-603"].toString())
        assertEquals("12.5", p1["tmdb-9"].toString()) // existing entries are kept
        val last = patch["lastWatchedByProfile"]!!.jsonObject["p1"]!!.jsonObject
        assertEquals("\"kept\"", last["tmdb-9"]!!.jsonObject["futureField"].toString()) // unknown fields survive
    }

    @Test fun avatarUrlsFollowTheWebsite() {
        val base = server.url("/").toString().trimEnd('/')
        assertEquals("$base/avatars/v2/v2-space-astronaut.webp", api.avatarUrl("builtin:v2-space-astronaut"))
        assertEquals("$base/avatars/abstract-orbs.svg", api.avatarUrl("builtin:abstract-orbs"))
        assertEquals("$base/api/user/avatar/photo.webp", api.avatarUrl("upload:photo.webp"))
        assertNull(api.avatarUrl("🦊"))
    }
}
