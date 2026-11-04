package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.cloudflareWebViewUrl
import eu.kanade.tachiyomi.network.configureForSource
import eu.kanade.tachiyomi.network.toWebViewHeaders
import okhttp3.Call
import okhttp3.Request
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Runs the site's own challenge in WebView; interactive checks stay user-operated. */
class WebViewChallengeSolver(
    context: Context,
    private val cookieJar: AndroidCookieJar,
    private val defaultUserAgent: () -> String,
) : CloudflareChallengeSolver {

    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override fun solve(request: Request, call: Call): Boolean {
        // A synchronous HTTP call on Main must not wait for Main to create WebView.
        if (Looper.myLooper() == Looper.getMainLooper()) return false
        val latch = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val succeeded = AtomicBoolean(false)
        val previous = cookieJar.get(request.url).firstOrNull { it.name == "cf_clearance" }?.value
        var webView: WebView? = null // Accessed only on Main.
        var rendererGone = false

        fun complete(success: Boolean) {
            if (finished.compareAndSet(false, true)) {
                succeeded.set(success)
                latch.countDown()
            }
        }

        val poll = object : Runnable {
            override fun run() {
                if (finished.get()) return
                val clearance = cookieJar.get(request.url).firstOrNull { it.name == "cf_clearance" }?.value
                if (!clearance.isNullOrBlank() && clearance != previous) {
                    complete(true)
                } else {
                    main.postDelayed(this, 250)
                }
            }
        }
        val url = request.cloudflareWebViewUrl()
        val start = Runnable {
            if (finished.get() || call.isCanceled()) {
                complete(false)
                return@Runnable
            }
            try {
                val view = WebView(context)
                webView = view
                view.configureForSource(request.header("User-Agent") ?: defaultUserAgent())

                // No unrestricted JavascriptInterface. This origin-scoped,
                // message-only listener can only stop an automatic attempt.
                val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build()
                    .toString().removeSuffix("/")
                val canListen = if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.addWebMessageListener(
                        view, "watchboxCloudflare", setOf(origin),
                    ) { _, message, _, isMainFrame, _ ->
                        if (isMainFrame && message.data in setOf("interactiveBegin", "fail")) complete(false)
                    }
                    true
                } else {
                    false
                }
                val canInjectAtStart = if (
                    canListen && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                ) {
                    WebViewCompat.addDocumentStartJavaScript(view, CHALLENGE_EVENTS, setOf(origin))
                    true
                } else {
                    false
                }
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        if (canListen && !canInjectAtStart && !finished.get()) {
                            view.evaluateJavascript(CHALLENGE_EVENTS, null)
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (request.isForMainFrame &&
                            (request.url.scheme !in setOf("https", "http") || request.url.host != url.host)
                        ) {
                            complete(false)
                            return true
                        }
                        return false
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) complete(false)
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        rendererGone = true
                        complete(false)
                        return true
                    }
                }
                main.post(poll)
                view.loadUrl(url.toString(), request.headers.toWebViewHeaders())
            } catch (_: Exception) {
                // Missing/updating WebView must fail this request, not crash an
                // OkHttp worker or leave the waiting thread blocked.
                complete(false)
            } catch (_: LinkageError) {
                complete(false)
            }
        }

        main.post(start)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!latch.await(100, TimeUnit.MILLISECONDS)) {
                if (call.isCanceled() || System.nanoTime() >= deadline) break
            }
            return succeeded.get() && !call.isCanceled()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Cloudflare verification interrupted").apply { initCause(e) }
        } finally {
            complete(false)
            main.removeCallbacks(start)
            main.removeCallbacks(poll)
            main.post {
                webView?.let { view ->
                    if (!rendererGone) {
                        view.stopLoading()
                        (view.parent as? android.view.ViewGroup)?.removeView(view)
                    }
                    view.destroy()
                }
                webView = null
            }
        }
    }
}

private val CHALLENGE_EVENTS = """
    (() => {
        if (window.watchboxCloudflareListening) return;
        window.watchboxCloudflareListening = true;
        addEventListener("message", ({data}) => {
            if (data?.source === "cloudflare-challenge" &&
                (data.event === "interactiveBegin" || data.event === "fail")) {
                window.watchboxCloudflare?.postMessage(data.event);
            }
        });
    })();
""".trimIndent()
