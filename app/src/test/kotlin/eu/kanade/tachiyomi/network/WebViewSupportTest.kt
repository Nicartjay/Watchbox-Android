package eu.kanade.tachiyomi.network

import okhttp3.Headers
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebViewSupportTest {
    @Test
    fun `browser user agent retains the installed Chromium version`() {
        val ua = inferBrowserUserAgent(
            "Mozilla/5.0 (Linux; Android 13; Pixel 7; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/142.0.7444.12 Mobile Safari/537.36",
        )
        assertTrue(ua.contains("Chrome/142.0.7444.12"))
        assertFalse(ua.contains("; wv"))
        assertFalse(ua.contains("Version/4.0"))
    }

    @Test
    fun `GET challenge navigation preserves its path and query`() {
        val request = Request.Builder().url("https://dramawave.example.org/watch?id=123&lang=en").build()
        assertEquals(request.url, request.cloudflareWebViewUrl())
    }

    @Test
    fun `POST challenge is navigated at its origin not its API endpoint`() {
        val request = Request.Builder()
            .url("https://portal.example.org:8443/api/search?token=private#fragment")
            .post("original-body".toRequestBody())
            .build()
        assertEquals("https://portal.example.org:8443/", request.cloudflareWebViewUrl().toString())
        assertEquals("POST", request.method)
        assertEquals("token=private", request.url.query)
    }

    @Test
    fun `unsafe browser headers are removed case insensitively while source headers survive`() {
        val headers = Headers.Builder()
            .add("Origin", "https://portal.example.org")
            .add("Host", "portal.example.org")
            .add("Cookie", "cf_clearance=stale")
            .add("Accept-Encoding", "br,gzip")
            .add("Content-Length", "123")
            .add("Connection", "Upgrade")
            .add("Proxy-Authorization", "private")
            .add("User-Agent", "configured-via-settings")
            .add("Referer", "https://portal.example.org/")
            .add("Accept-Language", "en")
            .build()
        assertEquals(
            mapOf("Referer" to "https://portal.example.org/", "Accept-Language" to "en"),
            headers.toWebViewHeaders(),
        )
    }
}
