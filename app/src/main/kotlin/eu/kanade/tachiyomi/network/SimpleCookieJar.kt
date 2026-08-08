package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cookie jar keyed by host.
 *
 * Several extensions depend on session cookies surviving across the requests
 * within one browse or resolve operation, so a no-op jar is not enough.
 * Deliberately not persisted: cookies are session state, and dropping them on
 * process death is the safer default for third-party sources.
 */
class SimpleCookieJar : CookieJar {

    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val host = url.host
        val existing = store.getOrPut(host) { mutableListOf() }

        synchronized(existing) {
            cookies.forEach { fresh ->
                existing.removeAll { it.name == fresh.name && it.path == fresh.path }
                existing.add(fresh)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val existing = store[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()

        return synchronized(existing) {
            existing.removeAll { it.expiresAt < now }
            existing.filter { it.matches(url) }
        }
    }

    fun clear() = store.clear()

    fun clearFor(host: String) {
        store.remove(host)
    }
}
