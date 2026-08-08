package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit
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
