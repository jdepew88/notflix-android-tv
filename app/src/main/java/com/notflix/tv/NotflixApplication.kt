package com.notflix.tv

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.notflix.tv.data.NotflixApi
import com.notflix.tv.data.PersistentCookieJar
import com.notflix.tv.data.ServerConfig
import com.notflix.tv.data.ServerConnection
import com.notflix.tv.data.ServerVerifier
import com.notflix.tv.data.UserRepository
import com.notflix.tv.discovery.NetworkMonitor
import com.notflix.tv.discovery.NsdServiceBrowser
import com.notflix.tv.discovery.ServerDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Process-wide singletons. */
class AppContainer(context: Context) {
    private val prefs = context.getSharedPreferences("notflix", Context.MODE_PRIVATE)

    val server = ServerConfig(prefs)
    val cookies = PersistentCookieJar(prefs)

    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(10, TimeUnit.SECONDS)
        // POST /api/playback/start may probe several releases before answering (engine timeout 90 s).
        .readTimeout(100, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        // Checked on every hop, redirects included.
        .addNetworkInterceptor(server.cleartextGuard())
        .build()

    val api = NotflixApi(http, server, cookies)
    val user = UserRepository(api)

    val connection = ServerConnection(server, cookies, user, ServerVerifier(USER_AGENT))
    val network = NetworkMonitor(context)
    private val browser = NsdServiceBrowser(context)

    fun newDiscovery() = ServerDiscovery(browser, connection.verifier::verify)

    /** Work that must outlive a screen (stopping an engine session, saving progress on exit). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        val USER_AGENT = "NotflixTV/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL})"
    }
}

class NotflixApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /** Images share the app's HTTP client, so uploaded profile photos load with the session cookie. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { container.http }))
                add(SvgDecoder.Factory())
            }
            .crossfade(true)
            .build()
}

val Context.container: AppContainer get() = (applicationContext as NotflixApplication).container
