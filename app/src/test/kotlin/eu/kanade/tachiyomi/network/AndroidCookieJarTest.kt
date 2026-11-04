package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidCookieJarTest {
    private val storage = FakeWebCookieStorage()
    private val jar = AndroidCookieJar { storage }
    private val url = "https://dramawave.example.org/watch/123".toHttpUrl()

    @Test
    fun `browser clearance is immediately visible to HTTP and another jar instance`() {
        storage.setCookie(url.toString(), "cf_clearance=browser-token; Path=/; Secure; HttpOnly")
        assertEquals("browser-token", jar.loadForRequest(url).single().value)
        assertEquals("browser-token", AndroidCookieJar { storage }.get(url).single().value)
    }

    @Test
    fun `HTTP session cookies are saved in the browser store with their attributes`() {
        val cookie = Cookie.parse(url, "session=account; Domain=example.org; Path=/; Secure; HttpOnly")!!
        jar.saveFromResponse(url, listOf(cookie))
        assertEquals("session=account", storage.getCookie("https://other.example.org/"))
        assertTrue(storage.writes.single().second.contains("httponly", ignoreCase = true))
        assertEquals(emptyList(), jar.loadForRequest("http://dramawave.example.org/".toHttpUrl()))
    }

    @Test
    fun `named removal expires parent domain clearance without signing out the source`() {
        storage.setCookie(url.toString(), "cf_clearance=old; Domain=example.org; Path=/")
        storage.setCookie(url.toString(), "session=keep; Path=/")
        storage.setCookie(url.toString(), "__cf_bm=keep-too; Path=/")
        assertEquals(1, jar.remove(url, listOf("cf_clearance"), 0))
        assertEquals(setOf("session", "__cf_bm"), jar.get(url).map { it.name }.toSet())
        assertFalse(storage.writes.any { it.second.contains("Domain=org;") || it.second.endsWith("Domain=org") })
    }

    @Test
    fun `removal also covers cookies scoped to the request directory`() {
        storage.setCookie(url.toString(), "cf_clearance=old; Path=/watch")
        jar.remove(url, listOf("cf_clearance"), 0)
        assertTrue(jar.get(url).isEmpty())
    }

    @Test
    fun `browser cookie parsing trims whitespace and keeps equals signs in values`() {
        storage.setCookie(url.toString(), "first=one; Path=/")
        storage.setCookie(url.toString(), "second=two==; Path=/")
        assertEquals(mapOf("first" to "one", "second" to "two=="), jar.get(url).associate { it.name to it.value })
    }

    @Test
    fun `clear all and flush use the shared browser store`() {
        storage.setCookie(url.toString(), "cf_clearance=token; Path=/")
        jar.flush()
        assertEquals(1, storage.flushes)
        jar.removeAll()
        assertTrue(jar.get(url).isEmpty())
    }

    @Test
    fun `ordinary sessions work without a WebView provider`() {
        val fallback = AndroidCookieJar { error("WebView not installed") }
        fallback.saveFromResponse(url, listOf(Cookie.parse(url, "session=fallback; Path=/")!!))
        assertEquals("fallback", fallback.get(url).single().value)
        fallback.remove(url, listOf("session"), 0)
        assertTrue(fallback.get(url).isEmpty())
    }

    @Test
    fun `fallback shares parent domain cookies but not host-only cookies`() {
        val fallback = SimpleCookieJar()
        fallback.saveFromResponse(
            url,
            listOf(
                Cookie.parse(url, "parent=yes; Domain=example.org; Path=/")!!,
                Cookie.parse(url, "host=no; Path=/")!!,
            ),
        )
        assertEquals(listOf("parent"), fallback.loadForRequest("https://portal.example.org/".toHttpUrl()).map { it.name })
        assertTrue(fallback.loadForRequest("https://unrelated.org/".toHttpUrl()).isEmpty())
    }

    @Test
    fun `fallback cookie replacement honors domain and path`() {
        val fallback = SimpleCookieJar()
        listOf(
            "session=root; Path=/",
            "session=directory; Path=/watch",
            "session=parent; Domain=example.org; Path=/",
            "session=replaced; Path=/watch",
        ).forEach { fallback.saveFromResponse(url, listOf(Cookie.parse(url, it)!!)) }
        assertEquals(listOf("replaced", "root", "parent").toSet(), fallback.loadForRequest(url).map { it.value }.toSet())
        fallback.saveFromResponse(url, listOf(Cookie.parse(url, "session=; Path=/watch; Max-Age=0")!!))
        assertEquals(setOf("root", "parent"), fallback.loadForRequest(url).map { it.value }.toSet())
    }
}
