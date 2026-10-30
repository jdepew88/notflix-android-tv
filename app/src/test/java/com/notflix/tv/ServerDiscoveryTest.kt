package com.notflix.tv

import com.notflix.tv.data.ServerIdentity
import com.notflix.tv.data.ServerProblem
import com.notflix.tv.data.Verification
import com.notflix.tv.discovery.AdvertisedService
import com.notflix.tv.discovery.BrowseEvent
import com.notflix.tv.discovery.DiscoveredServer
import com.notflix.tv.discovery.NotflixService
import com.notflix.tv.discovery.ServerDiscovery
import com.notflix.tv.discovery.ServiceBrowser
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** Discovery against a scripted DNS-SD browser (deterministic: no real multicast). */
class ServerDiscoveryTest {
    private fun ip(s: String) = InetAddress.getByName(s)
    private fun service(name: String, vararg addresses: String, port: Int = 4119, txt: Map<String, String> = emptyMap()) =
        AdvertisedService(name, addresses.map(::ip), port, txt)

    /** Emits [events] (with optional delays), then keeps browsing until cancelled. */
    private class ScriptedBrowser(private vararg val events: Pair<Long, BrowseEvent>) : ServiceBrowser {
        var stopped = false
        var type: String? = null
        override fun browse(serviceType: String) = flow {
            type = serviceType
            for ((wait, e) in events) {
                delay(wait)
                emit(e)
            }
            awaitCancellation()
        }.onCompletion { stopped = true }
    }

    private val notflix: suspend (String) -> Verification = { url -> Verification.Ok(ServerIdentity(url)) }

    @Test fun oneServer() = runTest {
        val browser = ScriptedBrowser(0L to BrowseEvent.Found(service("Notflix on DEN", "192.168.1.33")))
        val states = ServerDiscovery(browser, notflix, windowMs = 5_000).search().toList()
        assertEquals("_notflix._tcp", browser.type)
        assertTrue(states.first().searching)
        val last = states.last()
        assertFalse(last.searching)
        val s = last.servers.single()
        assertEquals("http://192.168.1.33:4119", s.url)
        assertEquals("192.168.1.33:4119", s.address)
        assertTrue(s.status is DiscoveredServer.Status.Available)
        assertTrue("discovery listener released when the window ends", browser.stopped)
    }

    @Test fun multipleServersAreAllListedAndNoneIsChosen() = runTest {
        val browser = ScriptedBrowser(
            0L to BrowseEvent.Found(service("Living Room", "10.20.0.11")),
            100L to BrowseEvent.Found(service("Office", "172.16.4.20", port = 8080)),
            100L to BrowseEvent.Found(service("Basement", "100.64.0.9")),
        )
        val last = ServerDiscovery(browser, notflix, 5_000).search().last()
        assertEquals(listOf("Living Room", "Office", "Basement"), last.servers.map { it.serviceName })
        assertEquals("http://172.16.4.20:8080", last.servers[1].url)
    }

    @Test fun noServers() = runTest {
        val last = ServerDiscovery(ScriptedBrowser(), notflix, 3_000).search().last()
        assertFalse(last.searching)
        assertTrue(last.servers.isEmpty())
        assertNull(last.error)
    }

    @Test fun discoveryFailureIsReported() = runTest {
        val failing = ServiceBrowser { flow { emit(BrowseEvent.Failed("Network discovery couldn't start (error 0).")) } }
        val last = ServerDiscovery(failing, notflix, 3_000).search().last()
        assertFalse(last.searching)
        assertEquals("Network discovery couldn't start (error 0).", last.error)
    }

    @Test fun advertisedServicesAreUntrustedUntilVerified() = runTest {
        val browser = ScriptedBrowser(
            0L to BrowseEvent.Found(service("Real", "192.168.1.33")),
            0L to BrowseEvent.Found(service("Impostor", "192.168.1.66")),
        )
        val verify: suspend (String) -> Verification = { url ->
            if (url.contains(".33")) Verification.Ok(ServerIdentity(url)) else Verification.Failed(ServerProblem.NotNotflix, "not Notflix")
        }
        val states = ServerDiscovery(browser, verify, 2_000).search().toList()
        // Every card starts as Checking.
        assertTrue(states.any { st -> st.servers.any { it.serviceName == "Impostor" && it.status == DiscoveredServer.Status.Checking } })
        val last = states.last()
        assertTrue(last.servers.first { it.serviceName == "Real" }.status is DiscoveredServer.Status.Available)
        assertEquals(DiscoveredServer.Status.Unavailable("not Notflix"), last.servers.first { it.serviceName == "Impostor" }.status)
    }

    @Test fun slowChecksFinishAfterTheBrowseWindow() = runTest {
        val browser = ScriptedBrowser(900L to BrowseEvent.Found(service("Slow", "10.0.0.2")))
        val verify: suspend (String) -> Verification = { url -> delay(3_000); Verification.Ok(ServerIdentity(url)) }
        val last = ServerDiscovery(browser, verify, 1_000).search().last()
        assertTrue(last.servers.single().status is DiscoveredServer.Status.Available)
    }

    @Test fun lostServicesDisappear() = runTest {
        val browser = ScriptedBrowser(
            0L to BrowseEvent.Found(service("Gone", "10.0.0.2")),
            200L to BrowseEvent.Lost("Gone"),
        )
        assertTrue(ServerDiscovery(browser, notflix, 1_000).search().last().servers.isEmpty())
    }

    @Test fun sameInstanceAdvertisedTwiceShowsOnce() = runTest {
        val browser = ScriptedBrowser(
            0L to BrowseEvent.Found(service("Den (Wi-Fi)", "192.168.1.33")),
            0L to BrowseEvent.Found(service("Den (Ethernet)", "192.168.1.34")),
        )
        val verify: suspend (String) -> Verification = { url -> Verification.Ok(ServerIdentity(url, instanceId = "same")) }
        assertEquals(1, ServerDiscovery(browser, verify, 1_000).search().last().servers.size)
    }

    @Test fun reannouncementsAreNotRecheckedButNewAddressesAre() = runTest {
        var checks = 0
        val verify: suspend (String) -> Verification = { url -> checks++; Verification.Ok(ServerIdentity(url)) }
        val browser = ScriptedBrowser(
            0L to BrowseEvent.Found(service("Den", "192.168.1.33")),
            100L to BrowseEvent.Found(service("Den", "192.168.1.33")),
            100L to BrowseEvent.Found(service("Den", "192.168.5.33")), // DHCP gave it a new address
        )
        val last = ServerDiscovery(browser, verify, 1_000).search().last()
        assertEquals(2, checks)
        assertEquals("http://192.168.5.33:4119", last.servers.single().url)
    }

    @Test fun urlsForDifferentNetworks() {
        // IPv4 preferred, routable IPv6 next; link-local IPv6 and loopback are unusable in a URL.
        assertEquals("http://10.20.30.40:4119", NotflixService.urlFor(service("x", "fe80::1", "fd00::5", "10.20.30.40")))
        assertEquals("http://[fd00::5]:4119", NotflixService.urlFor(service("x", "fe80::1", "fd00::5")))
        assertNull(NotflixService.urlFor(service("x", "fe80::1")))
        assertNull(NotflixService.urlFor(service("x", "127.0.0.1")))
        assertNull(NotflixService.urlFor(service("x", "192.168.1.2", port = 0)))
        assertEquals("https://192.168.1.2", NotflixService.urlFor(service("x", "192.168.1.2", port = 443, txt = mapOf("scheme" to "https"))))
        assertEquals("http://192.168.1.2:8443", NotflixService.urlFor(service("x", "192.168.1.2", port = 8443, txt = mapOf("scheme" to "gopher"))))
    }
}
