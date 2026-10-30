package com.notflix.tv

import com.notflix.tv.data.NoServerSelectedException
import com.notflix.tv.data.SavedServer
import com.notflix.tv.data.ServerConfig
import com.notflix.tv.data.ServerIdentity
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ServerConfigTest {
    @Test fun freshInstallHasNoServer() {
        val config = ServerConfig(FakePrefs())
        assertFalse(config.isConfigured)
        assertNull(config.current)
        assertEquals("", config.baseUrl)
        try {
            config.resolve("/api/health")
            fail("resolve() must not invent a server")
        } catch (_: NoServerSelectedException) {
        }
        // Absolute URLs (TMDB artwork) don't need a server.
        assertEquals("https://image.tmdb.org/t/p/w780/x.jpg", config.resolve("https://image.tmdb.org/t/p/w780/x.jpg"))
    }

    @Test fun keepsAnAddressSavedByPhase5() {
        // Phase 5 wrote only "server_url".
        val prefs = FakePrefs().apply { values["server_url"] = "http://192.168.7.20:4119" }
        val config = ServerConfig(prefs)
        assertEquals(SavedServer("http://192.168.7.20:4119"), config.current)
        assertEquals("http://192.168.7.20:4119/api/health", config.resolve("/api/health"))
    }

    @Test fun persistsServerAndIdentity() {
        val prefs = FakePrefs()
        ServerConfig(prefs).save(SavedServer("http://10.1.2.3:4119", name = "Den", instanceId = "abc", apiVersion = 1, lastConnectedAt = 5))
        val reloaded = ServerConfig(prefs).current!!
        assertEquals("Den", reloaded.name)
        assertEquals("abc", reloaded.instanceId)
        assertEquals(5L, reloaded.lastConnectedAt)
        assertEquals("http://10.1.2.3:4119", prefs.values["server_url"])
    }

    @Test fun metadataForAnotherUrlIsIgnored() {
        val prefs = FakePrefs()
        ServerConfig(prefs).save(SavedServer("http://10.1.2.3:4119", name = "Den"))
        prefs.values["server_url"] = "http://10.9.9.9:4119"
        assertEquals(SavedServer("http://10.9.9.9:4119"), ServerConfig(prefs).current)
    }

    @Test fun markConnectedAdoptsReportedIdentityButNeverReplacesTheInstance() {
        val config = ServerConfig(FakePrefs()).apply { save(SavedServer("http://h:4119")) }
        config.markConnected(ServerIdentity("http://h:4119", name = "Den", instanceId = "first"), at = 10)
        assertEquals("first", config.current!!.instanceId)
        config.markConnected(ServerIdentity("http://h:4119", instanceId = "second"), at = 20)
        assertEquals("first", config.current!!.instanceId)
        assertEquals("Den", config.current!!.name)
        assertEquals(20L, config.current!!.lastConnectedAt)
    }

    @Test fun plainHttpOnlyToTheSelectedOrigin() {
        val config = ServerConfig(FakePrefs()).apply { save(SavedServer("http://10.0.0.5:4119")) }
        assertTrue(config.allowsCleartext("http://10.0.0.5:4119/api/me".toHttpUrl()))
        assertFalse(config.allowsCleartext("http://10.0.0.5:4120/".toHttpUrl()))
        assertFalse(config.allowsCleartext("http://10.0.0.6:4119/".toHttpUrl()))
        assertFalse(ServerConfig(FakePrefs()).allowsCleartext("http://10.0.0.5:4119/".toHttpUrl()))
    }

    @Test fun guardBlocksRedirectToAnotherPlainHttpHost() {
        val selected = MockWebServer().apply { start() }
        val other = MockWebServer().apply { start() }
        try {
            val config = ServerConfig(FakePrefs()).apply { save(SavedServer(selected.url("/").toString().trimEnd('/'))) }
            // Same host, other port: still another origin.
            selected.enqueue(MockResponse.Builder().code(302).addHeader("Location", other.url("/elsewhere").toString()).build())
            val client = OkHttpClient.Builder().addNetworkInterceptor(config.cleartextGuard()).build()
            try {
                client.newCall(Request.Builder().url(selected.url("/api/x")).build()).execute().close()
                fail("redirect to another plain-HTTP origin must be blocked")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("blocked"))
            }
            assertEquals(0, other.requestCount)
        } finally {
            selected.close()
            other.close()
        }
    }
}
