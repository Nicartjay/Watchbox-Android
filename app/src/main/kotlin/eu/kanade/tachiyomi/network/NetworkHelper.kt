package eu.kanade.tachiyomi.network

import android.content.Context
import android.webkit.WebSettings
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import eu.kanade.tachiyomi.network.interceptor.WebViewChallengeSolver
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.brotli.BrotliInterceptor
import java.util.concurrent.TimeUnit

/**
 * The shared HTTP client extensions build their requests against.
 *
 * `AnimeHttpSource` resolves this through Injekt, so the host must register a
 * singleton during startup. Extensions reach it via `network.client` and may
 * wrap it with their own interceptors.
 *
 * Both client names handle Cloudflare, sharing cookies with the in-app WebView.
 * Checks needing human interaction surface an Open WebView action instead of
 * being fed to an extension as HTML/JSON or silently looping.
 *
 * See the note in [eu.kanade.tachiyomi.animesource.model.SAnime] for why this
 * package reproduces the Aniyomi ABI.
 */
class NetworkHelper(context: Context) {

    private val cacheDir = context.cacheDir.resolve("network_cache")

    val cookieJar = AndroidCookieJar()

    // NetworkHelper is installed on Main before extensions are loaded. Resolve
    // the UA once so native requests and every challenge WebView use the same
    // installed Chromium version, including sources which cache their headers.
    private val userAgent = runCatching {
        inferBrowserUserAgent(WebSettings.getDefaultUserAgent(context)).ifBlank { DEFAULT_USER_AGENT }
    }.getOrDefault(DEFAULT_USER_AGENT)

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
            .addInterceptor(
                CloudflareInterceptor(
                    cookieJar,
                    WebViewChallengeSolver(context, cookieJar, ::defaultUserAgentProvider),
                ),
            )
            // Brotli, because the default User-Agent claims Chrome and servers
            // take that at face value.
            //
            // OkHttp only negotiates and decodes gzip on its own. Advertising a modern
            // browser without being able to read `br` means a site is free to answer
            // HTTP 200 with a Brotli body the extension then cannot parse - AniList
            // does exactly this, and it surfaced as "Unexpected JSON token at offset 0"
            // inside Miruro rather than as a network error, because the request had
            // genuinely succeeded.
            //
            // Added as an application interceptor so it sits above the cache: entries
            // are then stored decoded, and a cache hit behaves like a fresh response.
            .addInterceptor(BrotliInterceptor)
            .build()
    }

    /** Legacy extension ABI; regular requests also handle challenges, as in Mihon. */
    val cloudflareClient: OkHttpClient get() = client

    fun defaultUserAgentProvider(): String = userAgent

    private companion object {
        const val CACHE_SIZE = 5L * 1024 * 1024
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
