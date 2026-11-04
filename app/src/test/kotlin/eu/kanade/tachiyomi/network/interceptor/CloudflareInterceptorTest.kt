package eu.kanade.tachiyomi.network.interceptor

import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.FakeWebCookieStorage
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CloudflareInterceptorTest {
    private val storage = FakeWebCookieStorage()
    private val jar = AndroidCookieJar { storage }
    private val request = Request.Builder()
        .url("https://dramawave.example.org/watch/123?lang=en")
        .header("User-Agent", "source-specific-agent")
        .header("Referer", "https://dramawave.example.org/")
        .build()

    private fun cleared(request: Request) {
        storage.setCookie(request.url.toString(), "cf_clearance=fresh; Path=/; Secure")
    }

    private fun client(
        solver: CloudflareChallengeSolver,
        network: (Request) -> Response,
    ): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(jar)
        .addInterceptor(CloudflareInterceptor(jar, solver))
        // In-memory transport: these tests never contact a protected/public site.
        .addInterceptor(Interceptor { network(it.request()) })
        .build()

    @Test
    fun `official challenge detection does not depend on Server or status code`() {
        val response = reply(request, 200, "<html>verify</html>")
            .newBuilder().header("cf-mitigated", "challenge").build()
        response.use { assertTrue(isCloudflareChallenge(it)) }
    }

    @Test
    fun `legacy detection peeks without consuming the extension response`() {
        val html = "<title>Just a moment</title><script src='/cdn-cgi/challenge-platform/main.js'></script>"
        val response = reply(request, 503, html).newBuilder().header("Server", "cloudflare").build()
        response.use {
            assertTrue(isCloudflareChallenge(it))
            assertEquals(html, it.body.string())
        }
    }

    @Test
    fun `ordinary CDN access errors and rate limits never start WebView`() {
        val client = client({ _, _ -> error("Not a challenge") }) {
            reply(it, 403, "<html>Access denied</html>").newBuilder().header("Server", "cloudflare").build()
        }
        client.newCall(request).execute().use {
            assertEquals(403, it.code)
            assertEquals("<html>Access denied</html>", it.body.string())
        }
        reply(request, 429, "<html>/cdn-cgi/challenge-platform/</html>")
            .newBuilder().header("Server", "cloudflare").build().use {
                assertFalse(isCloudflareChallenge(it))
            }
    }

    @Test
    fun `successful check replaces only clearance and retries with the same headers`() {
        storage.setCookie(request.url.toString(), "cf_clearance=stale; Path=/")
        storage.setCookie(request.url.toString(), "session=account; Path=/")
        val closed = TrackedBody()
        var attempts = 0
        var solves = 0
        val client = client({ source, _ ->
            solves++
            assertSame(request, source)
            assertEquals(listOf("session"), jar.get(source.url).map { it.name })
            cleared(source)
            true
        }) { outgoing ->
            attempts++
            assertEquals("source-specific-agent", outgoing.header("User-Agent"))
            assertEquals(request.header("Referer"), outgoing.header("Referer"))
            if (attempts == 1) {
                challenge(outgoing, closed)
            } else {
                assertTrue(closed.closed)
                assertEquals("fresh", jar.get(outgoing.url).first { it.name == "cf_clearance" }.value)
                assertEquals("no-cache", outgoing.header("Cache-Control"))
                reply(outgoing, 200, "catalogue")
            }
        }
        client.newCall(request).execute().use { assertEquals("catalogue", it.body.string()) }
        assertEquals(2, attempts)
        assertEquals(1, solves)
        assertEquals("account", jar.get(request.url).first { it.name == "session" }.value)
        assertEquals(1, storage.flushes)
    }

    @Test
    fun `a failed automatic check is closed and exposes an interactive request`() {
        val body = TrackedBody()
        val client = client({ _, _ -> false }) { challenge(it, body) }
        val failure = assertFailsWith<CloudflareChallengeException> { client.newCall(request).execute() }
        assertTrue(body.closed)
        assertSame(request, failure.request)
        assertTrue(failure.message!!.contains("Open WebView"))
        assertSame(request, IOException("wrapped", failure).cloudflareRequest())
    }

    @Test
    fun `a solver reporting success without new clearance never replays the request`() {
        var attempts = 0
        val client = client({ _, _ -> true }) {
            attempts++
            challenge(it)
        }
        assertFailsWith<CloudflareChallengeException> { client.newCall(request).execute() }
        assertEquals(1, attempts)
    }

    @Test
    fun `challenge errors from the solver are IOExceptions rather than worker crashes`() {
        val cause = IllegalStateException("WebView provider updating")
        val client = client({ _, _ -> throw cause }) { challenge(it) }
        val failure = assertFailsWith<CloudflareChallengeException> { client.newCall(request).execute() }
        assertSame(cause, failure.cause)
    }

    @Test
    fun `a site still refusing clearance is retried only once`() {
        var attempts = 0
        var solves = 0
        val bodies = mutableListOf<TrackedBody>()
        val client = client({ source, _ ->
            solves++
            cleared(source)
            true
        }) {
            attempts++
            val body = TrackedBody()
            bodies.add(body)
            challenge(it, body)
        }
        assertFailsWith<CloudflareChallengeException> { client.newCall(request).execute() }
        assertEquals(2, attempts)
        assertEquals(1, solves)
        assertTrue(bodies.all { it.closed })
    }

    @Test
    fun `a redirect verifies the actual portal not the source base URL`() {
        val portal = request.newBuilder().url("https://another.example.org/catalogue").build()
        var attempts = 0
        val client = client({ source, _ ->
            assertEquals(portal.url, source.url)
            cleared(source)
            true
        }) {
            if (++attempts == 1) challenge(portal) else reply(it, 200, "ok")
        }
        client.newCall(request).execute().use { assertEquals(200, it.code) }
        assertEquals(2, attempts)
    }

    @Test
    fun `repeatable POST retry preserves its method body query and authorization`() {
        val body = "original JSON".toRequestBody()
        val post = request.newBuilder().post(body).header("Authorization", "Bearer account").build()
        var attempts = 0
        val client = client({ source, _ -> cleared(source); true }) {
            attempts++
            assertEquals("POST", it.method)
            assertSame(body, it.body)
            assertEquals("lang=en", it.url.query)
            assertEquals("Bearer account", it.header("Authorization"))
            if (attempts == 1) challenge(it) else reply(it, 200, "ok")
        }
        client.newCall(post).execute().close()
        assertEquals(2, attempts)
    }

    @Test
    fun `one-shot request bodies are never replayed`() {
        val body = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("once")
            }
        }
        var attempts = 0
        val client = client({ _, _ -> error("Must not solve or replay") }) {
            attempts++
            challenge(it)
        }
        assertFailsWith<CloudflareChallengeException> {
            client.newCall(request.newBuilder().post(body).build()).execute()
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `simultaneous requests share one successful challenge`() = concurrentChallenge(success = true)

    @Test
    fun `simultaneous requests share one failed challenge too`() = concurrentChallenge(success = false)

    private fun concurrentChallenge(success: Boolean) {
        val count = 4
        val firstResponses = CyclicBarrier(count)
        val attempts = ConcurrentHashMap<String, AtomicInteger>()
        val solves = AtomicInteger()
        val client = client({ source, _ ->
            solves.incrementAndGet()
            if (success) cleared(source)
            success
        }) {
            val attempt = attempts.getOrPut(it.url.encodedPath) { AtomicInteger() }.incrementAndGet()
            if (attempt == 1) {
                firstResponses.await(5, TimeUnit.SECONDS)
                challenge(it)
            } else {
                reply(it, 200, "ok")
            }
        }
        val pool = Executors.newFixedThreadPool(count)
        try {
            val results = (1..count).map { index ->
                pool.submit<Boolean> {
                    try {
                        client.newCall(request.newBuilder().url("https://dramawave.example.org/$index").build())
                            .execute().use { it.isSuccessful }
                    } catch (_: CloudflareChallengeException) {
                        false
                    }
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(List(count) { success }, results)
            assertEquals(1, solves.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `different challenged hosts do not block each other`() {
        val bothSolving = CyclicBarrier(2)
        val attempts = ConcurrentHashMap<String, AtomicInteger>()
        val client = client({ source, _ ->
            bothSolving.await(5, TimeUnit.SECONDS)
            cleared(source)
            true
        }) {
            if (attempts.getOrPut(it.url.host) { AtomicInteger() }.incrementAndGet() == 1) {
                challenge(it)
            } else {
                reply(it, 200, "ok")
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = listOf("first.example.org", "second.example.org").map { host ->
                pool.submit<Boolean> {
                    client.newCall(request.newBuilder().url("https://$host/").build()).execute().use { it.isSuccessful }
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(listOf(true, true), results)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `cancelling a verification request does not retry or report a human challenge`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = client({ _, _ ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            false
        }) { challenge(it) }
        val call = client.newCall(request)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<IOException> {
                assertFailsWith<IOException> { call.execute() }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            call.cancel()
            release.countDown()
            val failure = result.get(5, TimeUnit.SECONDS)
            assertFalse(failure is CloudflareChallengeException)
            assertEquals("Canceled", failure.message)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    private fun challenge(request: Request, body: ResponseBody = TrackedBody()): Response =
        reply(request, 403, "")
            .newBuilder().header("cf-mitigated", "challenge").body(body).build()

    private fun reply(request: Request, code: Int, body: String): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
        .body(body.toResponseBody("text/html".toMediaType())).build()

    private class TrackedBody : ResponseBody() {
        var closed = false
        private val stream = object : ForwardingSource(Buffer().writeUtf8("<html>challenge</html>")) {
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()

        override fun contentType() = "text/html".toMediaType()
        override fun contentLength() = -1L
        override fun source(): BufferedSource = stream
    }
}
