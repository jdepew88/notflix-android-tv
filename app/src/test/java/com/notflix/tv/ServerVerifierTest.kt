package com.notflix.tv

import com.notflix.tv.data.ServerProblem
import com.notflix.tv.data.ServerVerifier
import com.notflix.tv.data.Verification
import com.notflix.tv.data.firstNotflix
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ServerVerifierTest {
    private val server = MockWebServer()
    private val verifier = ServerVerifier("test", connectTimeoutMs = 1_000, readTimeoutMs = 800)

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private val base get() = server.url("/").toString().trimEnd('/')

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()

    private fun failed(v: Verification) = v as? Verification.Failed ?: throw AssertionError("expected failure, got $v")

    @Test fun acceptsTodaysHealthAnswerWithoutInventingMetadata() = runBlocking {
        server.enqueue(json("""{"ok":true,"service":"notflix"}"""))
        val v = verifier.verify(base) as Verification.Ok
        assertEquals(base, v.identity.url)
        assertNull(v.identity.name)
        assertNull(v.identity.instanceId)
        assertNull(v.identity.apiVersion)
        val req = server.takeRequest()
        assertEquals("/api/health", req.url.encodedPath)
        assertNull("no cookies before the user has chosen", req.headers["Cookie"])
    }

    @Test fun readsTheProposedIdentityFields() = runBlocking {
        server.enqueue(json("""{"ok":true,"service":"notflix","name":"Living Room\u0007","instanceId":"7f3a-91","apiVersion":1,"extra":"ignored"}"""))
        val id = (verifier.verify(base) as Verification.Ok).identity
        assertEquals("Living Room", id.name)
        assertEquals("7f3a-91", id.instanceId)
        assertEquals(1, id.apiVersion)
    }

    @Test fun rejectsAMalformedInstanceId() = runBlocking {
        server.enqueue(json("""{"ok":true,"service":"notflix","instanceId":"<script>"}"""))
        assertNull((verifier.verify(base) as Verification.Ok).identity.instanceId)
    }

    @Test fun nonNotflixHttpService() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("<html>router login</html>").build())
        assertEquals(ServerProblem.NotNotflix, failed(verifier.verify(base)).problem)
        server.enqueue(json("""{"ok":true,"service":"jellyfin"}"""))
        assertEquals(ServerProblem.NotNotflix, failed(verifier.verify(base)).problem)
        server.enqueue(json("""{"status":"healthy"}"""))
        assertEquals(ServerProblem.NotNotflix, failed(verifier.verify(base)).problem)
        server.enqueue(MockResponse.Builder().code(404).body("Not found").build())
        assertEquals(ServerProblem.NotNotflix, failed(verifier.verify(base)).problem)
    }

    @Test fun unsupportedApiVersion() = runBlocking {
        server.enqueue(json("""{"ok":true,"service":"notflix","apiVersion":7}"""))
        val f = failed(verifier.verify(base))
        assertEquals(ServerProblem.Unsupported, f.problem)
        assertTrue(f.message.contains("7"))
    }

    @Test fun redirectsAreReportedNotFollowed() = runBlocking {
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", "https://elsewhere.example.com/api/health").build())
        val f = failed(verifier.verify(base))
        assertEquals(ServerProblem.Redirected, f.problem)
        assertTrue(f.message.contains("https://elsewhere.example.com"))
        assertEquals(1, server.requestCount)
    }

    @Test fun serverErrors() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).build())
        assertEquals(ServerProblem.ServerError, failed(verifier.verify(base)).problem)
    }

    @Test fun timeout() = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        assertEquals(ServerProblem.Timeout, failed(verifier.verify(base)).problem)
    }

    @Test fun wrongPortIsConnectionRefused() = runBlocking {
        val port = ServerSocket(0).use { it.localPort } // free, nothing listening
        val f = failed(verifier.verify("http://127.0.0.1:$port"))
        assertEquals(ServerProblem.Refused, f.problem)
        assertTrue(f.message.contains("$port"))
    }

    @Test fun httpsToAPlainHttpServerIsATlsProblem() = runBlocking {
        server.enqueue(json("""{"ok":true,"service":"notflix"}"""))
        val f = failed(verifier.verify(base.replace("http://", "https://")))
        assertEquals(ServerProblem.Tls, f.problem)
    }

    @Test fun everyCheckUsesAFreshConnection() = runBlocking {
        // A server that drops idle keep-alive connections must not fail the second check.
        server.enqueue(json("""{"ok":true,"service":"notflix"}"""))
        server.enqueue(json("""{"ok":true,"service":"notflix"}"""))
        assertTrue(verifier.verify(base) is Verification.Ok)
        assertTrue(verifier.verify(base) is Verification.Ok)
        assertEquals(0, server.takeRequest().connectionIndex)
        assertEquals(1, server.takeRequest().connectionIndex)
    }

    @Test fun classifiesPlatformFailures() {
        fun problem(e: IOException) = ServerVerifier.classify(e, "h:4119", "h", 4119, false, 9).problem
        assertEquals(ServerProblem.UnknownHost, problem(UnknownHostException("Unable to resolve host \"notflix.local\"")))
        assertEquals(ServerProblem.Unreachable, problem(NoRouteToHostException("No route to host")))
        assertEquals(ServerProblem.Unreachable, problem(ConnectException("failed to connect to /10.9.9.9 (port 4119): connect failed: EHOSTUNREACH (No route to host)")))
        assertEquals(ServerProblem.Refused, problem(ConnectException("failed to connect: connect failed: ECONNREFUSED (Connection refused)")))
        assertEquals(ServerProblem.Timeout, problem(SocketTimeoutException("failed to connect to /10.9.9.9 (port 4119) after 4000ms")))
        assertEquals(ServerProblem.Tls, problem(SSLHandshakeException("java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.")))
        assertEquals(ServerProblem.Network, problem(IOException("something else")))
    }

    @Test fun acceptsThePhase52ServerAnswer() = runBlocking {
        // Exactly what the Windows server's /api/health returns since it advertises itself.
        server.enqueue(json("""{"ok":true,"service":"notflix","name":"MEDIA-PC","instanceId":"0b8f2c9e-5d0a-4a43-9a52-2f7c1f6d3e10","apiVersion":1,"auth":["password"]}"""))
        val id = (verifier.verify(base) as Verification.Ok).identity
        assertEquals("MEDIA-PC", id.name)
        assertEquals("0b8f2c9e-5d0a-4a43-9a52-2f7c1f6d3e10", id.instanceId)
        assertEquals(1, id.apiVersion)
    }

    @Test fun manualCandidatesStopAtTheFirstNotflixServerWithoutFollowingRedirects() = runBlocking {
        MockWebServer().use { legacy ->
            legacy.start()
            val legacyBase = legacy.url("/").toString().trimEnd('/')
            // The first candidate is something else that even redirects to the Notflix one: not followed.
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "$legacyBase/api/health").build())
            legacy.enqueue(json("""{"ok":true,"service":"notflix"}"""))
            val tried = mutableListOf<String>()
            val v = firstNotflix(listOf(base, legacyBase), verifier::verify) { tried += it } as Verification.Ok
            assertEquals(legacyBase, v.identity.url)
            assertEquals(listOf(base, legacyBase), tried)
            assertEquals(1, server.requestCount)
            assertEquals(1, legacy.requestCount)
            assertNull(legacy.takeRequest().headers["Cookie"])
        }
    }

    @Test fun manualCandidatesThatAllFailReportTheFirstProblemAndWhatElseWasTried() = runBlocking {
        server.enqueue(json("""{"status":"ok"}"""))
        val closed = ServerSocket(0).use { it.localPort }
        val other = "http://127.0.0.1:$closed"
        val v = failed(firstNotflix(listOf(base, other), verifier::verify))
        assertEquals(ServerProblem.NotNotflix, v.problem)
        assertTrue(v.message, v.message.endsWith("(Also tried $other.)"))
        // A single candidate (explicit port or URL) is reported as is.
        server.enqueue(json("""{"status":"ok"}"""))
        assertTrue(!failed(firstNotflix(listOf(base), verifier::verify)).message.contains("Also tried"))
    }
}
