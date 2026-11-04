package eu.kanade.tachiyomi.network

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Request
import java.util.Locale

/** Keep the installed Chromium version, rather than claiming Chrome 120 forever. */
internal fun inferBrowserUserAgent(webViewUserAgent: String): String = webViewUserAgent
    .replace("; wv", "")
    .replace(Regex("""Version/\S+\s+"""), "")

internal fun Request.cloudflareWebViewUrl(): HttpUrl = if (method == "GET") {
    url
} else {
    // Loading a POST API endpoint with GET usually cannot earn clearance. Use
    // its origin's page instead, then replay the *original* method/body in OkHttp.
    url.newBuilder().encodedPath("/").query(null).fragment(null).build()
}

internal fun Headers.toWebViewHeaders(): Map<String, String> = filter { (name, value) ->
    val lower = name.lowercase(Locale.ROOT)
    lower !in UNSAFE_WEBVIEW_HEADERS && !lower.startsWith("proxy-") &&
        !(lower == "connection" && value.equals("upgrade", ignoreCase = true))
}.associate { it }

@SuppressLint("SetJavaScriptEnabled")
internal fun WebView.configureForSource(userAgent: String) {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
        allowFileAccess = false
        allowContentAccess = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(true)
        builtInZoomControls = true
        displayZoomControls = false
        mediaPlaybackRequiresUserGesture = true
        userAgentString = userAgent
    }
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(this@configureForSource, true)
    }

    // A UA string override alone leaves Sec-CH-UA advertising Android WebView
    // instead of Chrome. Match its brands/version as Mihon does, when supported.
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
    val chrome = Regex("""Chrome/(\d+)(\.[\d.]+)?""").find(userAgent) ?: return
    runCatching {
        val metadata = WebSettingsCompat.getUserAgentMetadata(settings)
        val major = chrome.groupValues[1]
        val full = major + chrome.groupValues[2].ifEmpty { ".0.0.0" }
        val brands = metadata.brandVersionList.map { brand ->
            if (brand.brand != "Android WebView" && brand.brand != "Chromium") return@map brand
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand(if (brand.brand == "Android WebView") "Google Chrome" else "Chromium")
                .setMajorVersion(major)
                .setFullVersion(full)
                .build()
        }
        WebSettingsCompat.setUserAgentMetadata(
            settings,
            UserAgentMetadata.Builder(metadata).setBrandVersionList(brands).setFullVersion(full).build(),
        )
    }
}

// Chromium rejects these overrides (Origin can even kill the renderer). Let
// WebView negotiate compression and read cookies from the shared store itself.
private val UNSAFE_WEBVIEW_HEADERS = setOf(
    "host", "content-length", "content-type", "accept-encoding", "cookie", "cookie2",
    "origin", "trailer", "te", "upgrade", "keep-alive", "transfer-encoding", "set-cookie",
    "user-agent",
)
