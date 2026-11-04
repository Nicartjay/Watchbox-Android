package eu.kanade.tachiyomi.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * One cookie store for extension HTTP requests and the in-app WebView, as in Mihon.
 *
 * In particular, a cf_clearance earned in WebView must be visible to the very next
 * OkHttp request. Copying cookies into a second, host-keyed jar loses domain/path
 * scoping and eventually overwrites newer browser cookies with stale HTTP cookies.
 *
 * The no-argument constructor and get/remove/removeAll methods are extension ABI.
 */
class AndroidCookieJar internal constructor(storageProvider: () -> WebCookieStorage?) : CookieJar {

    constructor() : this({ AndroidWebCookieStorage(CookieManager.getInstance()) })

    // Some TV devices have no WebView provider. Keep ordinary sources working there.
    private val storage by lazy { runCatching(storageProvider).getOrNull() }
    private val fallback = SimpleCookieJar()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val store = storage
        if (store == null) {
            fallback.saveFromResponse(url, cookies)
        } else {
            cookies.forEach { store.setCookie(url.toString(), it.toString()) }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = get(url)

    fun get(url: HttpUrl): List<Cookie> {
        val store = storage ?: return fallback.loadForRequest(url)
        // CookieManager has already applied Secure, expiry, domain and path rules.
        return store.getCookie(url.toString())
            ?.split(';')
            ?.mapNotNull { Cookie.parse(url, it.trim()) }
            .orEmpty()
    }

    fun remove(url: HttpUrl, cookieNames: List<String>? = null, maxAge: Int = -1): Int {
        val cookies = get(url).filter { cookieNames == null || it.name in cookieNames }
        if (cookies.isEmpty()) return 0
        val store = storage
        if (store == null) {
            fallback.saveFromResponse(
                url,
                cookies.map {
                    Cookie.Builder().name(it.name).value("").domain(it.domain).path(it.path).expiresAt(0).build()
                },
            )
            return cookies.size
        }

        // getCookie returns name/value pairs, not their original scope. Expire each
        // matching name at the paths/domains which could have supplied it, including
        // parent-domain clearance cookies shared by Arvin-DF's subdomains.
        val paths = buildSet {
            add("/")
            url.encodedPath.forEachIndexed { index, char ->
                if (char == '/' && index > 0) {
                    add(url.encodedPath.substring(0, index))
                    add(url.encodedPath.substring(0, index + 1))
                }
            }
            add(url.encodedPath)
        }
        val domains = buildList<String?> {
            add(null) // Host-only.
            val root = url.topPrivateDomain()
            var domain = url.host
            add(domain)
            while (root != null && domain != root) {
                domain = domain.substringAfter('.')
                add(domain)
            }
        }
        cookies.map { it.name }.distinct().forEach { name ->
            paths.forEach { path ->
                domains.forEach { domain ->
                    store.setCookie(
                        url.toString(),
                        "$name=; Max-Age=$maxAge; Path=$path" +
                            (domain?.let { "; Domain=$it" } ?: "") +
                            (if (url.isHttps) "; Secure" else ""),
                    )
                }
            }
        }
        return cookies.size
    }

    fun removeAll() {
        storage?.removeAll()
        fallback.clear()
    }

    fun flush() {
        storage?.flush()
    }
}

/** Small seam so cookie sharing can be tested without a device/WebView provider. */
internal interface WebCookieStorage {
    fun getCookie(url: String): String?
    fun setCookie(url: String, cookie: String)
    fun removeAll()
    fun flush()
}

private class AndroidWebCookieStorage(private val manager: CookieManager) : WebCookieStorage {
    init {
        manager.setAcceptCookie(true)
    }

    override fun getCookie(url: String): String? = manager.getCookie(url)
    override fun setCookie(url: String, cookie: String) = manager.setCookie(url, cookie)
    override fun removeAll() = manager.removeAllCookies(null)
    override fun flush() = manager.flush()
}
