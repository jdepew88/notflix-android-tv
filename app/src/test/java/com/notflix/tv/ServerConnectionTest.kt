package com.notflix.tv

import com.notflix.tv.data.NotflixApi
import com.notflix.tv.data.PersistentCookieJar
import com.notflix.tv.data.SavedServer
import com.notflix.tv.data.SavedServerCheck
import com.notflix.tv.data.ServerConfig
import com.notflix.tv.data.ServerConnection
import com.notflix.tv.data.ServerIdentity
import com.notflix.tv.data.ServerOrigin
import com.notflix.tv.data.ServerVerifier
import com.notflix.tv.data.UserRepository
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Choosing, reconnecting to and switching servers, and keeping each server's session separate. */
class ServerConnectionTest {
    private val a = MockWebServer()
    private val b = MockWebServer()
    private lateinit var prefs: FakePrefs
    private lateinit var config: ServerConfig
    private lateinit var jar: PersistentCookieJar
    private lateinit var api: NotflixApi
    private lateinit var user: UserRepository
    private lateinit var connection: ServerConnection

    @Before fun setUp() {
        a.start()
        b.start()
        boot(FakePrefs())
    }

    @After fun tearDown() {
        a.close()
        b.close()
    }

    /** A (re)started app over [p], wired like AppContainer. */
    private fun boot(p: FakePrefs) {
        prefs = p
        config = ServerConfig(prefs)
        jar = PersistentCookieJar(prefs)
        api = NotflixApi(OkHttpClient.Builder().cookieJar(jar).addNetworkInterceptor(config.cleartextGuard()).build(), config, jar)
        user = UserRepository(api)
        connection = ServerConnection(config, jar, user, ServerVerifier("test", 1_000, 1_000), now = { 42 })
    }

    private fun urlOf(s: MockWebServer) = s.url("/").toString().trimEnd('/')
    private fun health(extra: String = "") =
        MockResponse.Builder().body("""{"ok":true,"service":"notflix"$extra}""").addHeader("Content-Type", "application/json").build()

    private fun signInAt(s: MockWebServer) = runBlocking {
        s.enqueue(
            MockResponse.Builder().body("""{"user":{"id":"u1","name":"joe","role":"owner"}}""")
                .addHeader("Set-Cookie", "notflix_session=from-${s.port}; Path=/; HttpOnly; Max-Age=2592000").build()
        )
        api.login("joe", "pw")
        s.takeRequest()
    }

    @Test fun freshInstallStartsInTheWizard() = runBlocking {
        assertEquals(SavedServerCheck.NoneSelected, connection.checkSaved())
        assertNull(connection.previousServerSuggestion())
    }

    @Test fun savedServerReconnectsAndRecordsTheConnection() = runBlocking {
        connection.select(ServerIdentity(urlOf(a)))
        a.enqueue(health(""","name":"Den""""))
        val ok = connection.checkSaved() as SavedServerCheck.Ok
        assertEquals("Den", ok.server.name)
        assertEquals(42L, ok.server.lastConnectedAt)
        // No scanning, no other server: exactly one request, to the saved one.
        assertEquals(1, a.requestCount)
        assertEquals(0, b.requestCount)
    }

    @Test fun offlineServerIsReportedAndKept() = runBlocking {
        connection.select(ServerIdentity(urlOf(a)))
        a.close()
        val failed = connection.checkSaved() as SavedServerCheck.Failed
        assertEquals(urlOf(a), failed.server.url)
        assertEquals(urlOf(a), config.current!!.url)
    }

    @Test fun anotherInstanceAtTheSavedAddressIsNotSilentlyAccepted() = runBlocking {
        connection.select(ServerIdentity(urlOf(a), instanceId = "first"))
        a.enqueue(health(""","instanceId":"second""""))
        val different = connection.checkSaved() as SavedServerCheck.DifferentServer
        assertEquals("second", different.found.instanceId)
        assertEquals("first", config.current!!.instanceId)
    }

    @Test fun switchingServersDropsTheOldSessionAndProfileState() = runBlocking {
        connection.select(ServerIdentity(urlOf(a)))
        signInAt(a)
        assertTrue(api.hasSession())
        user.applyState(com.notflix.tv.data.UserState(activeProfileId = "p1"))

        val kept = connection.select(ServerIdentity(urlOf(b)))
        assertFalse(kept)
        assertFalse(api.hasSession())
        assertNull(user.state.value.activeProfileId)
        assertNull(user.account.value)
        assertEquals(ServerOrigin.of(urlOf(b)), jar.origin)

        // Server B never sees server A's cookie.
        b.enqueue(MockResponse.Builder().code(401).body("""{"user":null}""").build())
        assertNull(api.me())
        assertNull(b.takeRequest().headers["Cookie"])
    }

    @Test fun cookiesAreScopedToTheOriginEvenOnTheSameHost() {
        connection.select(ServerIdentity(urlOf(a)))
        signInAt(a)
        // Same host (localhost), different port: a browser would share the cookie, this jar doesn't.
        assertTrue(jar.loadForRequest(a.url("/api/auth/me")).isNotEmpty())
        assertTrue(jar.loadForRequest(b.url("/api/auth/me")).isEmpty())
        // And it doesn't accept cookies from there either.
        jar.saveFromResponse(b.url("/"), listOf(Cookie.parse(b.url("/"), "notflix_session=evil; Path=/")!!))
        assertEquals("from-${a.port}", jar.loadForRequest(a.url("/")).single().value)
    }

    @Test fun reselectingTheSameServerKeepsTheSession() {
        connection.select(ServerIdentity(urlOf(a), instanceId = "x"))
        signInAt(a)
        assertTrue(connection.select(ServerIdentity(urlOf(a), name = "Den", instanceId = "x")))
        assertTrue(api.hasSession())
        assertEquals("Den", config.current!!.name)
    }

    @Test fun sameAddressButDifferentInstanceRequiresANewSignIn() {
        connection.select(ServerIdentity(urlOf(a), instanceId = "x"))
        signInAt(a)
        assertFalse(connection.select(ServerIdentity(urlOf(a), instanceId = "y")))
        assertFalse(api.hasSession())
    }

    @Test fun cancellingASwitchChangesNothing() {
        connection.select(ServerIdentity(urlOf(a)))
        signInAt(a)
        val before = config.current
        // The wizard verifies candidates without selecting them; cancelling just leaves.
        runBlocking { b.enqueue(health()); connection.verifier.verify(urlOf(b)) }
        assertEquals(before, ServerConfig(prefs).current)
        assertTrue(api.hasSession())
        assertEquals(ServerOrigin.of(urlOf(a)), jar.origin)
    }

    @Test fun selectionSurvivesARestart() {
        connection.select(ServerIdentity(urlOf(a), name = "Den"))
        signInAt(a)
        boot(prefs)
        assertEquals("Den", connection.current!!.name)
        assertTrue(api.hasSession())
        assertEquals(ServerOrigin.of(urlOf(a)), jar.origin)
    }

    @Test fun phase5InstallWithASavedAddressStaysSignedIn() {
        val legacy = FakePrefs()
        legacyCookie(legacy, a.url("/").host)
        legacy.values["server_url"] = urlOf(a)
        boot(legacy)
        assertEquals(ServerOrigin.of(urlOf(a)), jar.origin)
        assertTrue(jar.loadForRequest(a.url("/api/auth/me")).isNotEmpty())
    }

    @Test fun phase5InstallOnTheOldDefaultIsOfferedItsServerButNotConnected() {
        val legacy = FakePrefs()
        legacyCookie(legacy, "10.20.0.11")
        boot(legacy)
        assertEquals(SavedServerCheck.NoneSelected, runBlocking { connection.checkSaved() })
        assertEquals("http://10.20.0.11:4119", connection.previousServerSuggestion())
        // Nothing is sent anywhere until a server is chosen.
        assertTrue(jar.loadForRequest("http://10.20.0.11:4119/api/auth/me".toHttpUrl()).isEmpty())
        // Choosing exactly that server keeps the session...
        assertTrue(connection.select(ServerIdentity("http://10.20.0.11:4119")))
        assertTrue(jar.loadForRequest("http://10.20.0.11:4119/api/auth/me".toHttpUrl()).isNotEmpty())
    }

    @Test fun phase5SessionIsDroppedWhenAnotherServerIsChosen() {
        val legacy = FakePrefs()
        legacyCookie(legacy, "10.20.0.11")
        boot(legacy)
        assertFalse(connection.select(ServerIdentity("http://10.20.0.11:4129")))
        assertFalse(api.hasSession())
    }

    /** A cookie stored the way Phase 5 stored it: no "cookies_origin". */
    private fun legacyCookie(p: FakePrefs, host: String) {
        val writer = PersistentCookieJar(p)
        writer.bindTo(ServerOrigin("http", host, 4119))
        writer.saveFromResponse("http://$host:4119/".toHttpUrl(), listOf(Cookie.parse("http://$host:4119/".toHttpUrl(), "notflix_session=old; Path=/; Max-Age=2592000")!!))
        p.values.remove("cookies_origin")
    }
}
