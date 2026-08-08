package eu.kanade.tachiyomi.network

import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Request builders extensions call directly.
 *
 * These are top-level functions, which the Kotlin compiler exposes as static
 * members of `RequestsKt`. That class name is part of the ABI — the surveyed
 * extensions reference `RequestsKt.GET(...)` — so this file must stay named
 * `Requests.kt`.
 *
 * See the note in [eu.kanade.tachiyomi.animesource.model.SAnime] for why this
 * package reproduces the Aniyomi ABI.
 */

private val DEFAULT_CACHE_CONTROL = CacheControl.Builder().maxAge(10, java.util.concurrent.TimeUnit.MINUTES).build()
private val DEFAULT_HEADERS = Headers.Builder().build()
private val DEFAULT_BODY: RequestBody = ByteArray(0).toRequestBody()

fun GET(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request = Request.Builder()
    .url(url)
    .headers(headers)
    .cacheControl(cache)
    .build()

fun GET(
    url: HttpUrl,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request = Request.Builder()
    .url(url)
    .headers(headers)
    .cacheControl(cache)
    .build()

fun POST(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request = Request.Builder()
    .url(url)
    .post(body)
    .headers(headers)
    .cacheControl(cache)
    .build()

fun PUT(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request = Request.Builder()
    .url(url)
    .put(body)
    .headers(headers)
    .cacheControl(cache)
    .build()

fun DELETE(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request = Request.Builder()
    .url(url)
    .delete(body)
    .headers(headers)
    .cacheControl(cache)
    .build()

/** Convenience used by some extensions when posting JSON. */
val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()
