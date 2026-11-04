package eu.kanade.tachiyomi.network.interceptor

import eu.kanade.tachiyomi.network.AndroidCookieJar
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Handles actual challenge pages, not every 403 returned by a Cloudflare CDN.
 * Both the regular client and the legacy cloudflareClient use this interceptor.
 */
class CloudflareInterceptor(
    private val cookieJar: AndroidCookieJar,
    private val solver: CloudflareChallengeSolver,
) : Interceptor {

    private class HostState {
        val lock = ReentrantLock()
        var failedAt = Long.MIN_VALUE
    }

    private val hosts = ConcurrentHashMap<String, HostState>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val startedAt = System.nanoTime()
        val originalRequest = chain.request()
        val previousClearance = clearance(originalRequest.url)
        val response = chain.proceed(originalRequest)
        if (!challengeOrClose(response)) return response

        // Use the challenged host, not the original host of a redirect or a
        // source's baseUrl: Arvin-DF uses several independently protected portals.
        val request = response.request
        val rejectedClearance = if (request.url.host == originalRequest.url.host) {
            previousClearance
        } else {
            response.networkResponse?.request?.header("Cookie")
                ?.split(';')?.map(String::trim)
                ?.firstOrNull { it.startsWith("cf_clearance=") }?.substringAfter('=')
        }
        response.close()
        checkCancelled(chain.call())

        // Never replay a streaming/one-shot body. Ordinary POSTs keep their
        // original body and method; only the WebView navigation uses GET.
        if (originalRequest.body?.isOneShot() == true || originalRequest.body?.isDuplex() == true) {
            throw CloudflareChallengeException(request)
        }

        val host = hosts.getOrPut(request.url.host) { HostState() }
        try {
            while (!host.lock.tryLock(100, TimeUnit.MILLISECONDS)) checkCancelled(chain.call())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Cloudflare verification interrupted").apply { initCause(e) }
        }

        try {
            checkCancelled(chain.call())
            val currentClearance = clearance(request.url)
            if (currentClearance.isNullOrBlank() || currentClearance == rejectedClearance) {
                // Requests already in flight share a failed attempt too, rather
                // than opening a new WebView each after the first one times out.
                if (host.failedAt >= startedAt) throw CloudflareChallengeException(request)
                try {
                    cookieJar.remove(request.url, listOf("cf_clearance"), 0)
                    if (!solver.solve(request, chain.call()) ||
                        clearance(request.url).let { it.isNullOrBlank() || it == rejectedClearance }
                    ) {
                        checkCancelled(chain.call())
                        throw CloudflareChallengeException(request)
                    }
                    cookieJar.flush()
                } catch (e: Exception) {
                    checkCancelled(chain.call())
                    host.failedAt = System.nanoTime()
                    if (e is CloudflareChallengeException) throw e
                    throw CloudflareChallengeException(request, e)
                }
            }
        } finally {
            host.lock.unlock()
        }

        checkCancelled(chain.call())
        // Re-enter OkHttp's bridge so it reads fresh cookies. Reusing the
        // network request here would also reuse its rejected Cookie header.
        val retry = chain.proceed(
            originalRequest.newBuilder().cacheControl(CacheControl.FORCE_NETWORK).build(),
        )
        if (challengeOrClose(retry)) {
            val failedRequest = retry.request
            retry.close()
            throw CloudflareChallengeException(failedRequest)
        }
        return retry
    }

    private fun clearance(url: HttpUrl): String? =
        cookieJar.get(url).firstOrNull { it.name == "cf_clearance" }?.value

    private fun challengeOrClose(response: Response): Boolean = try {
        isCloudflareChallenge(response)
    } catch (e: Exception) {
        response.close()
        throw e
    }

    private fun checkCancelled(call: Call) {
        if (call.isCanceled()) throw IOException("Canceled")
    }
}

fun interface CloudflareChallengeSolver {
    /** Synchronous, bounded wait on the HTTP worker; WebView itself runs on Main. */
    fun solve(request: Request, call: Call): Boolean
}

/** Keeps the exact URL/headers available for an interactive WebView retry. */
class CloudflareChallengeException(val request: Request, cause: Throwable? = null) : IOException(
    "${request.url.host} needs a Cloudflare check. Open WebView, complete verification, then retry.",
    cause,
)

fun Throwable.cloudflareRequest(): Request? {
    var error: Throwable? = this
    val seen = mutableSetOf<Throwable>()
    while (error != null && seen.add(error)) {
        if (error is CloudflareChallengeException) return error.request
        error = error.cause
    }
    return null
}

internal fun isCloudflareChallenge(response: Response): Boolean {
    // Official signal, independent of status code and the presence of Server.
    if (response.header("cf-mitigated").equals("challenge", ignoreCase = true)) return true

    // Older challenge pages sometimes omit cf-mitigated. Peek, never consume:
    // extensions still need to read ordinary error bodies themselves.
    if (response.code !in listOf(403, 503) ||
        response.header("Server")?.lowercase() !in setOf("cloudflare", "cloudflare-nginx")
    ) return false
    val type = response.body.contentType()
    if (type != null && type.subtype != "html") return false
    val html = response.peekBody(64L * 1024).string()
    return html.contains("/cdn-cgi/challenge-platform/", ignoreCase = true) ||
        html.contains("_cf_chl_opt", ignoreCase = true) ||
        (html.contains("Just a moment", ignoreCase = true) &&
            html.contains("challenges.cloudflare.com", ignoreCase = true))
}
