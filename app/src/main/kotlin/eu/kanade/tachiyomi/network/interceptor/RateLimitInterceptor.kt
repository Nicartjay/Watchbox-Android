package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Caps how many requests may be issued in a sliding window.
 *
 * A large number of sources block or captcha aggressive clients, so extensions
 * commonly attach one of these to their own client. Implemented as a simple
 * timestamp ring rather than a scheduler: it must block the calling thread,
 * because OkHttp interceptors are synchronous.
 *
 * See the note in [eu.kanade.tachiyomi.animesource.model.SAnime] for why this
 * package reproduces the Aniyomi ABI.
 */
class RateLimitInterceptor(
    private val permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
) : Interceptor {

    private val periodMillis = unit.toMillis(period)
    private val lock = ReentrantLock()
    private val timestamps = LongArray(permits)
    private var cursor = 0

    override fun intercept(chain: Interceptor.Chain): Response {
        throttle()
        return chain.proceed(chain.request())
    }

    private fun throttle() {
        var sleepFor = 0L

        lock.withLock {
            val now = System.currentTimeMillis()
            val oldest = timestamps[cursor]
            val elapsed = now - oldest

            if (oldest != 0L && elapsed < periodMillis) {
                sleepFor = periodMillis - elapsed
            }

            timestamps[cursor] = now + sleepFor
            cursor = (cursor + 1) % permits
        }

        if (sleepFor > 0) {
            runCatching { Thread.sleep(sleepFor) }
        }
    }
}

/**
 * Global limiter, applied to every request on the client.
 *
 * Host-scoped limiting lives in `SpecificHostRateLimitInterceptor.kt`; see the
 * note there about why the file name is load-bearing.
 */
fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder = addInterceptor(RateLimitInterceptor(permits, period, unit))

/**
 * The `kotlin.time.Duration` form, which newer extensions link against.
 *
 * Both overloads must exist. Aniyomi changed this signature from `Long` + `TimeUnit` to
 * `Duration`, and extensions are compiled against whichever was current - so the two are found in
 * the wild simultaneously. Anikoto references the old symbol while AniZone, Anikage and Miruro
 * reference the new one; providing only one breaks the other half.
 *
 * They can coexist because Kotlin mangles a function taking a value class: `Duration` is an inline
 * class over a `Long`, so this compiles to `rateLimit-SxA4cEA(Builder, int, long)` while the
 * overload above stays plain `rateLimit`. Different JVM names, no clash - which is also why the
 * failure was `NoSuchMethodError` naming a hash rather than an ordinary missing method.
 *
 * The default on [period] is load-bearing, not a convenience. Kotlin only emits the
 * `rateLimit-SxA4cEA$default` synthetic when a parameter has a default, and that synthetic is
 * precisely what AniZone calls - without it the symbol resolves and the *bridge* does not, so the
 * extension still fails with the same class of error.
 *
 * Verified by compiling the signature in isolation and comparing the emitted symbols against the
 * runtime error text: an exact match, including the `$default` variant.
 */
fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Duration = 1.seconds,
): OkHttpClient.Builder = addInterceptor(
    RateLimitInterceptor(permits, period.inWholeMilliseconds, TimeUnit.MILLISECONDS),
)
