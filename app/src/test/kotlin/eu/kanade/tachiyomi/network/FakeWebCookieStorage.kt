package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.CopyOnWriteArrayList

/** Simulates the browser store, including domain/path matching, without Android. */
internal class FakeWebCookieStorage : WebCookieStorage {
    private val cookies = SimpleCookieJar()
    val writes = CopyOnWriteArrayList<Pair<String, String>>()
    var flushes = 0

    override fun getCookie(url: String): String? = cookies.loadForRequest(url.toHttpUrl())
        .takeIf { it.isNotEmpty() }
        ?.joinToString("; ") { "${it.name}=${it.value}" }

    override fun setCookie(url: String, cookie: String) {
        writes.add(url to cookie)
        val address = url.toHttpUrl()
        Cookie.parse(address, cookie)?.let { cookies.saveFromResponse(address, listOf(it)) }
    }

    override fun removeAll() = cookies.clear()
    override fun flush() {
        flushes++
    }
}
