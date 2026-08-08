package eu.kanade.tachiyomi.animesource

import kotlinx.coroutines.suspendCancellableCoroutine
import rx.Observable
import rx.Subscription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Bridges the deprecated RxJava half of [AnimeSource] onto coroutines.
 *
 * Legacy extensions only implement the `fetch*` methods, so the `suspend`
 * defaults have to await a single emission. Cancelling the coroutine
 * unsubscribes, otherwise an abandoned browse request would keep its HTTP call
 * alive.
 */
internal suspend fun <T> Observable<T>.awaitSingleValue(): T =
    suspendCancellableCoroutine { continuation ->
        var subscription: Subscription? = null
        var delivered = false

        subscription = this.single().subscribe(
            { value ->
                if (!delivered) {
                    delivered = true
                    continuation.resume(value)
                }
            },
            { error ->
                if (!delivered) {
                    delivered = true
                    continuation.resumeWithException(error)
                }
            },
        )

        continuation.invokeOnCancellation { subscription.unsubscribe() }
    }
