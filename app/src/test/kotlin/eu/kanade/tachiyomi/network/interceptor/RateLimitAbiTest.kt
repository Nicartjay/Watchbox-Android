package eu.kanade.tachiyomi.network.interceptor

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import okhttp3.OkHttpClient

/**
 * Pins the rate-limit ABI surface, including the mangled `Duration` overloads.
 *
 * These exist because Aniyomi changed the signature from `Long` + `TimeUnit` to
 * `kotlin.time.Duration`, and extensions are compiled against whichever was current when they were
 * built - so both are in circulation simultaneously. Three extensions failed to load with
 * `NoSuchMethodError` naming `rateLimit-SxA4cEA` and `rateLimitHost-Wn2Vu4Y` while a fourth kept
 * working through the unmangled names.
 *
 * The mangling is not decoration: `Duration` is an inline value class, so Kotlin renames any
 * function taking one. That is what lets both overloads coexist, and it is also why the failure
 * pointed at a hash-suffixed symbol rather than an obviously absent method.
 *
 * Reflection is used deliberately. Calling these normally would prove only that *some* overload
 * resolved; the extensions link by exact JVM name, so the test has to assert on the emitted names.
 * The `$default` synthetics are checked too - Kotlin only emits those when a parameter carries a
 * default value, and one of the failing extensions called the synthetic rather than the function.
 */
class RateLimitAbiTest {

    private val globalMethods =
        Class.forName("eu.kanade.tachiyomi.network.interceptor.RateLimitInterceptorKt")
            .declaredMethods
            .map { it.name }

    private val hostMethods =
        Class.forName("eu.kanade.tachiyomi.network.interceptor.SpecificHostRateLimitInterceptorKt")
            .declaredMethods
            .map { it.name }

    // ------------------------------------------------------------- global limiter

    /** The older signature, which Anikoto still links against. */
    @Test
    fun `the unmangled rateLimit is present`() {
        assertTrue("rateLimit" in globalMethods, globalMethods.toString())
        assertTrue("rateLimit\$default" in globalMethods, globalMethods.toString())
    }

    /** The Duration signature, named exactly as AniZone's error demanded. */
    @Test
    fun `the mangled rateLimit is present`() {
        assertTrue("rateLimit-SxA4cEA" in globalMethods, globalMethods.toString())
    }

    /**
     * The synthetic AniZone actually calls.
     *
     * Emitted only because [period] has a default. Dropping the default leaves the function
     * resolvable and this bridge missing, which fails identically from the extension's side.
     */
    @Test
    fun `the mangled rateLimit default synthetic is present`() {
        assertTrue("rateLimit-SxA4cEA\$default" in globalMethods, globalMethods.toString())
    }

    // --------------------------------------------------------------- host limiter

    @Test
    fun `the unmangled rateLimitHost is present`() {
        assertTrue("rateLimitHost" in hostMethods, hostMethods.toString())
        assertTrue("rateLimitHost\$default" in hostMethods, hostMethods.toString())
    }

    /** Named exactly as Anikage's and Miruro's errors demanded. */
    @Test
    fun `the mangled rateLimitHost is present`() {
        assertTrue("rateLimitHost-Wn2Vu4Y" in hostMethods, hostMethods.toString())
        assertTrue("rateLimitHost-Wn2Vu4Y\$default" in hostMethods, hostMethods.toString())
    }

    /**
     * Both the HttpUrl and String forms mangle to the same name.
     *
     * Extensions use either, and both must be reachable - so the count matters, not just presence.
     */
    @Test
    fun `both mangled rateLimitHost forms exist`() {
        val mangled = hostMethods.count { it == "rateLimitHost-Wn2Vu4Y" }

        assertTrue(mangled >= 2, "expected HttpUrl and String forms, found $mangled")
    }

    // -------------------------------------------------------------------- behaviour

    /**
     * The overloads must actually work, not merely exist under the right name.
     *
     * Read off the built client: `interceptors()` is not public on the builder.
     */
    @Test
    fun `the duration overloads add an interceptor`() {
        val plain = OkHttpClient.Builder().build().interceptors.size

        val global = OkHttpClient.Builder()
            .rateLimit(permits = 2, period = 1.seconds)
            .build()
        val host = OkHttpClient.Builder()
            .rateLimitHost("https://example.test", 2, 1.seconds)
            .build()

        assertTrue(global.interceptors.size == plain + 1, "global: ${global.interceptors}")
        assertTrue(host.interceptors.size == plain + 1, "host: ${host.interceptors}")
    }

    /**
     * A sub-second period must survive.
     *
     * The delegation converts to milliseconds precisely so this does not floor to zero, which
     * would turn a 500ms limiter into no limit at all.
     */
    @Test
    fun `a sub-second period is not truncated away`() {
        val client = OkHttpClient.Builder()
            .rateLimitHost("https://example.test", permits = 1, period = 500.milliseconds)
            .build()

        assertTrue(client.interceptors.isNotEmpty())
    }
}
