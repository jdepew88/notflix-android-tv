package com.notflix.tv

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notflix.tv.data.ServerVerifier
import com.notflix.tv.discovery.DiscoveredServer
import com.notflix.tv.discovery.NotflixService
import com.notflix.tv.discovery.NsdServiceBrowser
import com.notflix.tv.discovery.ServerDiscovery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Real Android NSD on a device or emulator, against a controlled advertisement: the test itself
 * registers a `_notflix._tcp` service backed by a tiny health endpoint, then runs the app's
 * discovery (NsdServiceBrowser + ServerVerifier) and expects to find and verify exactly that
 * service. A second advertisement points at a non-Notflix HTTP service and must stay unverified.
 */
@RunWith(AndroidJUnit4::class)
class NsdDiscoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val nonce = UUID.randomUUID().toString().take(6)
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()
    private val servers = mutableListOf<ServerSocket>()

    @Before fun setUp() {
        servers += httpServer("""{"ok":true,"service":"notflix","name":"Controlled $nonce","instanceId":"test-$nonce","apiVersion":1}""")
        servers += httpServer("""{"status":"ok"}""")
    }

    @After fun tearDown() {
        registrations.forEach { runCatching { nsd.unregisterService(it) } }
        servers.forEach { runCatching { it.close() } }
    }

    @Test fun discoversAndVerifiesAControlledAdvertisement() = runBlocking {
        val notflixName = advertise("Notflix Test $nonce", servers[0].localPort)
        val impostorName = advertise("Impostor $nonce", servers[1].localPort)

        val discovery = ServerDiscovery(NsdServiceBrowser(context), ServerVerifier("instrumented-test")::verify, windowMs = 12_000)
        val result = discovery.search().last()
        val found = result.servers.filter { it.serviceName.endsWith(nonce) }
        android.util.Log.i("NsdDiscoveryTest", "found: $found (error=${result.error})")

        val real = found.single { it.serviceName == notflixName }
        val status = real.status as DiscoveredServer.Status.Available
        assertEquals("Controlled $nonce", status.identity.name)
        assertEquals("test-$nonce", status.identity.instanceId)
        assertEquals(1, status.identity.apiVersion)
        assertTrue(real.url.endsWith(":${servers[0].localPort}"))

        val impostor = found.single { it.serviceName == impostorName }
        assertTrue(impostor.status is DiscoveredServer.Status.Unavailable)
    }

    /**
     * Manual UI testing only (does nothing unless run with `-e holdSeconds N -e forwardTo host:port`):
     * advertises "Notflix Test …" for N seconds, backed by a TCP forwarder to a real server (for
     * the emulator, 10.0.2.2:4129), so the wizard can discover, verify and connect through it.
     */
    @Test fun holdControlledAdvertisement() {
        val args = InstrumentationRegistry.getArguments()
        val hold = args.getString("holdSeconds")?.toLongOrNull()
        val target = args.getString("forwardTo")
        // A no-op in normal runs (AGP reports skipped assumptions as failures).
        if (hold == null || target == null) return
        val (host, port) = target.substringBeforeLast(':') to target.substringAfterLast(':').toInt()
        val forwarder = ServerSocket(0).also { servers += it }
        thread(isDaemon = true) {
            while (!forwarder.isClosed) {
                val client = runCatching { forwarder.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        java.net.Socket(host, port).use { upstream ->
                            client.use {
                                val up = thread(isDaemon = true) { runCatching { client.getInputStream().copyTo(upstream.getOutputStream()) }; runCatching { upstream.shutdownOutput() } }
                                runCatching { upstream.getInputStream().copyTo(client.getOutputStream()) }
                                up.join(1_000)
                            }
                        }
                    }
                }
            }
        }
        val name = advertise("Living Room $nonce", forwarder.localPort)
        android.util.Log.i("NsdDiscoveryTest", "advertising '$name' on port ${forwarder.localPort} -> $target for ${hold}s")
        Thread.sleep(hold * 1000)
    }

    /**
     * End to end against a real server's advertisement (only with `-e lanRelay host:port`, e.g.
     * 10.0.2.2:5399): the emulator's NAT doesn't pass LAN multicast, so a relay on the PC
     * (mdns-relay.mjs) carries Android's own mDNS queries to the LAN and the LAN's Notflix answers
     * back, where they're multicast on the emulator's network for the system NSD service. Discovery,
     * resolution and verification are the app's real code. `-e expectName`, `-e expectPort`,
     * `-e expectHost` check what was resolved.
     */
    @Test fun discoversTheRealServerThroughALanRelay() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val relay = args.getString("lanRelay") ?: return@runBlocking
        val stop = lanRelay(relay)
        try {
            val discovery = ServerDiscovery(NsdServiceBrowser(context), ServerVerifier("instrumented-test")::verify, windowMs = 15_000)
            val result = discovery.search().last()
            android.util.Log.i("NsdDiscoveryTest", "relay search: ${result.servers} (error=${result.error})")
            val name = args.getString("expectName")
            val found = result.servers.filter { name == null || it.serviceName == name }
            val server = found.single()
            val status = server.status as DiscoveredServer.Status.Available
            args.getString("expectPort")?.let { assertTrue("${server.url} has port $it", server.url.endsWith(":$it")) }
            args.getString("expectHost")?.let { assertEquals("http://$it:${args.getString("expectPort")}", server.url) }
            name?.let { assertEquals(it, status.identity.name) }
            assertEquals(1, status.identity.apiVersion)
            assertTrue(status.identity.instanceId!!.isNotBlank())
        } finally {
            stop()
        }
    }

    /** Manual UI testing: keeps the LAN relay running for `-e holdSeconds N` (see the test above). */
    @Test fun holdLanRelay() {
        val args = InstrumentationRegistry.getArguments()
        val relay = args.getString("lanRelay") ?: return
        val hold = args.getString("holdSeconds")?.toLongOrNull() ?: return
        val stop = lanRelay(relay)
        Thread.sleep(hold * 1000)
        stop()
    }

    /**
     * Emulator side of the relay: frames from the PC (2-byte length + DNS message) are multicast to
     * 224.0.0.251:5353 here, from port 5353; DNS queries seen on this network (Android's NSD asking)
     * are sent to the PC. Returns a function that stops it.
     */
    @Suppress("DEPRECATION") // joinGroup(InetAddress) and loopbackMode: the simple forms are enough here
    private fun lanRelay(target: String): () -> Unit {
        val host = target.substringBeforeLast(':')
        val port = target.substringAfterLast(':').toInt()
        val group = InetAddress.getByName("224.0.0.251")
        val socket = Socket(host, port).apply { tcpNoDelay = true }
        val out = DataOutputStream(socket.getOutputStream())
        val mcast = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(5353))
            joinGroup(group)
            loopbackMode = false // false = loopback ON: the system's NSD on this device must receive what we send
        }
        var running = true
        thread(isDaemon = true, name = "relay-in") {
            val input = DataInputStream(socket.getInputStream())
            runCatching {
                while (running) {
                    val len = input.readUnsignedShort()
                    val buf = ByteArray(len).also { input.readFully(it) }
                    mcast.send(DatagramPacket(buf, len, group, 5353))
                    android.util.Log.i("NsdDiscoveryTest", "relay: LAN response ($len bytes) multicast on the emulator")
                }
            }
        }
        thread(isDaemon = true, name = "relay-out") {
            val buf = ByteArray(9000)
            runCatching {
                while (running) {
                    val p = DatagramPacket(buf, buf.size)
                    mcast.receive(p)
                    val isQuery = p.length > 12 && (buf[2].toInt() and 0x80) == 0
                    val aboutNotflix = String(buf, 0, p.length, Charsets.ISO_8859_1).contains("_notflix", ignoreCase = true)
                    if (isQuery && aboutNotflix) synchronized(out) {
                        out.writeShort(p.length)
                        out.write(buf, 0, p.length)
                        out.flush()
                        android.util.Log.i("NsdDiscoveryTest", "relay: query from ${p.address} sent to the LAN")
                    }
                }
            }
        }
        return {
            running = false
            runCatching { socket.close() }
            runCatching { mcast.close() }
        }
    }

    /** Registers a service and returns the name NSD actually registered (it may rename on conflict). */
    private fun advertise(name: String, port: Int): String = runBlocking {
        val registered = CompletableDeferred<String>()
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { registered.complete(info.serviceName) }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) { registered.completeExceptionally(IllegalStateException("register failed $errorCode")) }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = NotflixService.TYPE
            this.port = port
            setAttribute("app", "notflix")
            setAttribute("scheme", "http")
            setAttribute("api", "1")
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        registrations += listener
        withTimeout(10_000) { registered.await() }
    }

    /** Answers every request with [body] as JSON (enough for GET /api/health). */
    private fun httpServer(body: String): ServerSocket {
        val socket = ServerSocket(0)
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    client.use { c ->
                        val reader = c.getInputStream().bufferedReader()
                        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                        val bytes = body.toByteArray()
                        c.getOutputStream().write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes
                        )
                        c.getOutputStream().flush()
                    }
                }
            }
        }
        return socket
    }
}
