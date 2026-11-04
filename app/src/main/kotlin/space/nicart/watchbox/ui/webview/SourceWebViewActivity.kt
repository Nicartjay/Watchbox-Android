package space.nicart.watchbox.ui.webview

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.cloudflareWebViewUrl
import eu.kanade.tachiyomi.network.configureForSource
import eu.kanade.tachiyomi.network.toWebViewHeaders
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import space.nicart.watchbox.R
import space.nicart.watchbox.WatchBoxApplication
import space.nicart.watchbox.core.ui.WatchBoxTheme
import space.nicart.watchbox.data.local.AppSettings

/** An internal, user-operated browser sharing the extensions' cookie store and UA. */
@UnstableApi
class SourceWebViewActivity : ComponentActivity() {

    private lateinit var network: NetworkHelper
    private var webView: WebView? = null
    private var currentUrl by mutableStateOf("")
    private var loading by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val url = intent.getStringExtra(URL)?.toHttpUrlOrNull()
        if (url == null) {
            finish()
            return
        }
        val container = (application as WatchBoxApplication).container
        network = container.networkHelper
        val headers = Headers.Builder().apply {
            val names = intent.getStringArrayListExtra(HEADER_NAMES).orEmpty()
            val values = intent.getStringArrayListExtra(HEADER_VALUES).orEmpty()
            names.zip(values).forEach { (name, value) -> add(name, value) }
        }.build()
        currentUrl = url.toString()

        try {
            val view = WebView(this)
            webView = view
            view.apply {
                configureForSource(headers["User-Agent"] ?: network.defaultUserAgentProvider())
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        currentUrl = url
                        loading = true
                        error = null
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        currentUrl = url
                        loading = false
                        network.cookieJar.flush()
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.scheme !in setOf("http", "https")

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) {
                            this@SourceWebViewActivity.error = error.description.toString()
                            loading = false
                        }
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        (view.parent as? ViewGroup)?.removeView(view)
                        webView = null
                        view.destroy()
                        loading = false
                        error = getString(R.string.source_webview_renderer_error)
                        return true
                    }
                }
                loadUrl(url.toString(), headers.toWebViewHeaders())
            }
        } catch (failure: Throwable) {
            if (failure !is Exception && failure !is LinkageError) throw failure
            webView?.destroy()
            webView = null
            Toast.makeText(this, R.string.source_webview_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContent {
            val settings by container.store.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            WatchBoxTheme(appTheme = settings.theme, uiScale = settings.uiScale, posterScale = settings.posterScale) {
                BackHandler {
                    if (webView?.canGoBack() == true) webView?.goBack() else finishForRetry()
                }
                Column(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .safeDrawingPadding(),
                ) {
                    Text(
                        text = currentUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { webView?.reload() }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.source_webview_reload))
                        }
                        TextButton(
                            onClick = {
                                currentUrl.toHttpUrlOrNull()?.let {
                                    network.cookieJar.remove(it)
                                    network.cookieJar.flush()
                                    webView?.reload()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.source_webview_clear_cookies))
                        }
                        TextButton(onClick = ::finishForRetry, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.source_webview_done))
                        }
                    }
                    Text(
                        text = error ?: stringResource(R.string.source_webview_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val view = webView
                    if (view != null) {
                        AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }

    private fun finishForRetry() {
        network.cookieJar.flush()
        // This means "the user returned; retry", not a claim that the site passed
        // verification. Only a successful HTTP retry can establish that.
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val URL = "source_url"
        private const val HEADER_NAMES = "header_names"
        private const val HEADER_VALUES = "header_values"

        fun newIntent(context: Context, request: Request): Intent =
            Intent(context, SourceWebViewActivity::class.java).apply {
                putExtra(URL, request.cloudflareWebViewUrl().toString())
                putStringArrayListExtra(HEADER_NAMES, ArrayList(request.headers.map { it.first }))
                putStringArrayListExtra(HEADER_VALUES, ArrayList(request.headers.map { it.second }))
            }
    }
}
