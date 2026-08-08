package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Rate limiter scoped to a single host.
 *
 * Useful when an extension talks to both a metadata API and a separate CDN and
 * only one of them throttles.
 *
 * ## Why this is its own file
 *
 * Kotlin names the facade class for top-level declarations after the file, so
 * [rateLimitHost] only becomes `SpecificHostRateLimitInterceptorKt.rateLimitHost`
 * if it lives in `SpecificHostRateLimitInterceptor.kt`. The surveyed extension
 * APKs reference exactly that class name, so moving these functions into another
 * file would raise `NoSuchMethodError` at runtime even though everything still
 * compiles. Do not merge this into `RateLimitInterceptor.kt`.
 *
 * See the note in [eu.kanade.tachiyomi.animesource.model.SAnime] for why this
 * package reproduces the Aniyomi ABI.
 */
class SpecificHostRateLimitInterceptor(
    private val host: String,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
) : Interceptor {

    private val delegate = RateLimitInterceptor(permits, period, unit)

    override fun intercept(chain: Interceptor.Chain): Response =
        if (chain.request().url.host == host) {
            delegate.intercept(chain)
        } else {
            chain.proceed(chain.request())
        }
}

fun OkHttpClient.Builder.rateLimitHost(
    httpUrl: HttpUrl,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder = addInterceptor(
    SpecificHostRateLimitInterceptor(httpUrl.host, permits, period, unit),
)

fun OkHttpClient.Builder.rateLimitHost(
    url: String,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder = addInterceptor(
    SpecificHostRateLimitInterceptor(
        url.toHttpUrlOrNull()?.host ?: url,
        permits,
        period,
        unit,
    ),
)
