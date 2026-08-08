package eu.kanade.tachiyomi.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.ResponseBody
import rx.Observable
import rx.Subscriber
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OkHttp helpers extensions call.
 *
 * Top-level functions here compile to static members of `OkHttpExtensionsKt`,
 * which is the name the surveyed extensions reference, so this file must stay
 * named `OkHttpExtensions.kt`.
 *
 * See the note in [eu.kanade.tachiyomi.animesource.model.SAnime] for why this
 * package reproduces the Aniyomi ABI.
 */

/**
 * Awaits the call, returning the response whatever its status code.
 *
 * Cancelling the coroutine cancels the call, so abandoning a browse request does
 * not leave a socket open.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWithException(e)
            }
        },
    )

    continuation.invokeOnCancellation {
        runCatching { cancel() }
    }
}

/**
 * Awaits the call and throws on a non-2xx status.
 *
 * The body is closed before throwing; leaking it here would exhaust the
 * connection pool after a run of failing sources.
 */
suspend fun Call.awaitSuccess(): Response {
    val response = await()
    if (!response.isSuccessful) {
        response.close()
        throw HttpException(response.code)
    }
    return response
}

fun Call.asObservable(): Observable<Response> = Observable.unsafeCreate { subscriber ->
    val call = clone()
    call.enqueue(
        object : Callback {
            override fun onResponse(c: Call, response: Response) {
                if (subscriber.isUnsubscribed) {
                    response.close()
                    return
                }
                subscriber.onNext(response)
                subscriber.onCompleted()
            }

            override fun onFailure(c: Call, e: IOException) {
                if (!subscriber.isUnsubscribed) subscriber.onError(e)
            }
        },
    )

    subscriber.add(rx.subscriptions.Subscriptions.create { call.cancel() })
}

fun Call.asObservableSuccess(): Observable<Response> = asObservable().map { response ->
    if (!response.isSuccessful) {
        response.close()
        throw HttpException(response.code)
    }
    response
}

/** Thrown for non-2xx responses; extensions match on the message shape. */
class HttpException(val code: Int) : IOException("HTTP error $code")

/** Reads the body as text, closing it afterwards. */
fun Response.asText(): String = use { it.body?.string().orEmpty() }

@Suppress("unused")
fun ResponseBody.closeQuietly() {
    runCatching { close() }
}

private fun <T> Subscriber<T>.safeOnError(error: Throwable) {
    if (!isUnsubscribed) onError(error)
}
