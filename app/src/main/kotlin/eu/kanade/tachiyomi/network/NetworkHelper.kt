package eu.kanade.tachiyomi.network

import android.content.Context
import okhttp3.Cache
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The shared HTTP client extensions build their requests against.
 *
 * `AnimeHttpSource` resolves this through Injekt, so the host must register a
 * singleton during startup. Extensions reach it via `network.client` and may
 * wrap it with their own interceptors.
 *
 * `cloudflareClient` exists because a number of extensions reference it by
 * name. A real Cloudflare bypass needs a WebView to run the JS challenge; that
 * is out of scope here, so it is an alias for [client] with a longer timeout.
 * Sources behind an active challenge will fail rather than silently hang.
 *
 * See the note in [eu.kanade.tachiyomi.animesource.model.SAnime] for why this
 * package reproduces the Aniyomi ABI.
 */
class NetworkHelper(context: Context) {

    private val cacheDir = context.cacheDir.resolve("network_cache")

    val cookieJar = SimpleCookieJar()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .cache(Cache(cacheDir, CACHE_SIZE))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
            .build()
    }

    /**
     * Kept for ABI compatibility. Not an actual challenge solver — see the class
     * note.
     */
    val cloudflareClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun defaultUserAgentProvider(): String = DEFAULT_USER_AGENT

    private companion object {
        const val CACHE_SIZE = 5L * 1024 * 1024
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
