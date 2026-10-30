package com.notflix.tv.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.InetAddress
import java.util.concurrent.Executor

/**
 * DNS-SD / mDNS browsing through Android's Network Service Discovery (the system mDNS daemon;
 * no multicast lock or extra permission is needed beyond INTERNET). Discovery runs only while the
 * flow is collected: cancelling the collector stops discovery and releases every resolve callback.
 *
 * Resolving differs by version: Android 14+ (API 34) has registerServiceInfoCallback, which may run
 * for several services at once and reports every address; older versions only allow one
 * resolveService() at a time, so those are queued.
 */
class NsdServiceBrowser(context: Context) : ServiceBrowser {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    override fun browse(serviceType: String): Flow<BrowseEvent> = callbackFlow {
        val manager = nsd
        if (manager == null) {
            trySend(BrowseEvent.Failed("Network service discovery isn't available on this TV."))
            close()
            return@callbackFlow
        }
        val emit: (BrowseEvent) -> Unit = { trySend(it) }
        val resolver: Resolver = if (Build.VERSION.SDK_INT >= 34) CallbackResolver(manager, emit) else QueuedResolver(manager, main, emit)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                trySend(BrowseEvent.Failed("Network discovery couldn't start (error $errorCode)."))
                close()
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                resolver.resolve(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                resolver.forget(info.serviceName)
                trySend(BrowseEvent.Lost(info.serviceName))
            }
        }

        try {
            manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            trySend(BrowseEvent.Failed("Network discovery couldn't start (${e.message ?: e.javaClass.simpleName})."))
            close()
        }

        awaitClose {
            runCatching { manager.stopServiceDiscovery(listener) }
            resolver.close()
        }
    }

    private interface Resolver {
        fun resolve(info: NsdServiceInfo)
        fun forget(serviceName: String)
        fun close()
    }

    @RequiresApi(34)
    private class CallbackResolver(private val nsd: NsdManager, private val emit: (BrowseEvent) -> Unit) : Resolver {
        private val callbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()
        private val direct = Executor { it.run() }
        private var closed = false

        @Synchronized
        override fun resolve(info: NsdServiceInfo) {
            if (closed || info.serviceName in callbacks) return
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                override fun onServiceInfoCallbackUnregistered() {}
                override fun onServiceLost() {}

                override fun onServiceUpdated(updated: NsdServiceInfo) {
                    updated.toAdvertised(updated.hostAddresses)?.let { emit(BrowseEvent.Found(it)) }
                }
            }
            callbacks[info.serviceName] = callback
            runCatching { nsd.registerServiceInfoCallback(info, direct, callback) }.onFailure { callbacks.remove(info.serviceName) }
        }

        @Synchronized
        override fun forget(serviceName: String) {
            callbacks.remove(serviceName)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        }

        @Synchronized
        override fun close() {
            closed = true
            callbacks.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            callbacks.clear()
        }
    }

    /** Pre-Android 14: one resolve at a time; "already active" failures are retried shortly after. */
    @Suppress("DEPRECATION")
    private class QueuedResolver(private val nsd: NsdManager, private val main: Handler, private val emit: (BrowseEvent) -> Unit) : Resolver {
        private val queue = ArrayDeque<NsdServiceInfo>()
        private var busy = false
        private var closed = false
        private val attempts = mutableMapOf<String, Int>()

        @Synchronized
        override fun resolve(info: NsdServiceInfo) {
            if (closed || queue.any { it.serviceName == info.serviceName }) return
            queue.addLast(info)
            next()
        }

        @Synchronized
        override fun forget(serviceName: String) {
            queue.removeAll { it.serviceName == serviceName }
        }

        @Synchronized
        override fun close() {
            closed = true
            queue.clear()
        }

        @Synchronized
        private fun next() {
            if (busy || closed) return
            val info = queue.removeFirstOrNull() ?: return
            busy = true
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                    val tries = synchronized(this@QueuedResolver) {
                        busy = false
                        ((attempts[info.serviceName] ?: 0) + 1).also { attempts[info.serviceName] = it }
                    }
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && tries < 4) main.postDelayed({ resolve(info) }, 300L * tries)
                    main.post { next() }
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val open = synchronized(this@QueuedResolver) { busy = false; !closed }
                    if (open) resolved.toAdvertised(listOfNotNull(resolved.host))?.let { emit(BrowseEvent.Found(it)) }
                    main.post { next() }
                }
            }
            runCatching { nsd.resolveService(info, listener) }.onFailure { busy = false; main.post { next() } }
        }
    }
}

private const val MAX_TXT_VALUE = 200

/**
 * Network data, so it must never crash the app: a malformed record yields null and is skipped.
 * (The attributes map is an ArrayMap view whose entrySet() can't be copied with toArray(), so
 * it's walked by key.)
 */
private fun NsdServiceInfo.toAdvertised(addresses: List<InetAddress>): AdvertisedService? = runCatching {
    val txt = LinkedHashMap<String, String>()
    val attrs = attributes ?: emptyMap()
    for (key in attrs.keys) {
        if (txt.size >= 16) break
        txt[key.lowercase()] = (attrs[key]?.let { String(it, Charsets.UTF_8) } ?: "").take(MAX_TXT_VALUE)
    }
    AdvertisedService(serviceName = serviceName.orEmpty(), addresses = addresses, port = port, txt = txt)
}.getOrNull()
