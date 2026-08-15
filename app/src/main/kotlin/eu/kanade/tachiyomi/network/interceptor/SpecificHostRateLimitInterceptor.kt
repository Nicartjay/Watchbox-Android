package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

/**
 * The `kotlin.time.Duration` forms, which newer extensions link against.
 *
 * Kept alongside the `Long` + `TimeUnit` overloads above rather than replacing them: Aniyomi
 * changed the signature, extensions are compiled against whichever was current at the time, and
 * both are in circulation. Anikage and Miruro reference `rateLimitHost-Wn2Vu4Y`, while Anikoto
 * still references the plain `rateLimitHost` - so removing either breaks working extensions.
 *
 * `Duration` is an inline value class, so Kotlin mangles these to `rateLimitHost-Wn2Vu4Y` and the
 * plain overloads keep their unmangled names. That is why both can exist at once, and why the
 * runtime error named a hash-suffixed method rather than a plainly missing one.
 *
 * Milliseconds are used when delegating so a sub-second period is not silently truncated to zero.
 *
 * The defaults on [period] are load-bearing: Kotlin only emits the `rateLimitHost-Wn2Vu4Y$default`
 * synthetic when a parameter has one, and an extension compiled against a defaulted signature
 * calls that synthetic rather than the function directly.
 */
fun OkHttpClient.Builder.rateLimitHost(
    httpUrl: HttpUrl,
    permits: Int,
    period: Duration = 1.seconds,
): OkHttpClient.Builder = addInterceptor(
    SpecificHostRateLimitInterceptor(
        httpUrl.host,
        permits,
        period.inWholeMilliseconds,
        TimeUnit.MILLISECONDS,
    ),
)

fun OkHttpClient.Builder.rateLimitHost(
    url: String,
    permits: Int,
    period: Duration = 1.seconds,
): OkHttpClient.Builder = addInterceptor(
    SpecificHostRateLimitInterceptor(
        url.toHttpUrlOrNull()?.host ?: url,
        permits,
        period.inWholeMilliseconds,
        TimeUnit.MILLISECONDS,
    ),
)
