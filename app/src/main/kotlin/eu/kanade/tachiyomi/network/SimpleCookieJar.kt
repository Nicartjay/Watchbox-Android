package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory fallback for devices without a WebView provider.
 *
 * Several extensions depend on session cookies surviving across the requests
 * within one browse or resolve operation, so a no-op jar is not enough.
 * The normal client uses [AndroidCookieJar] so WebView and HTTP share cookies.
 */
class SimpleCookieJar : CookieJar {

    private data class Key(val name: String, val domain: String, val path: String)

    private val store = ConcurrentHashMap<Key, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        cookies.forEach { cookie ->
            val key = Key(cookie.name, cookie.domain, cookie.path)
            if (cookie.expiresAt <= now) store.remove(key) else store[key] = cookie
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        store.forEach { (key, cookie) ->
            if (cookie.expiresAt <= now) store.remove(key, cookie)
        }
        return store.values.filter { it.matches(url) }.sortedByDescending { it.path.length }
    }

    fun clear() = store.clear()

    fun clearFor(host: String) {
        store.entries.removeAll {
            it.value.domain == host || (!it.value.hostOnly && host.endsWith(".${it.value.domain}"))
        }
    }
}
