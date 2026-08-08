package space.nicart.watchbox.core.network

import android.util.Base64
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Persisted Bearer token. Backed by DataStore in production; the in-memory
 * variant exists for tests.
 */
interface TokenStore {
    suspend fun get(): String?
    suspend fun set(token: String)
    suspend fun clear()
}

class InMemoryTokenStore : TokenStore {
    @Volatile
    private var token: String? = null
    override suspend fun get(): String? = token
    override suspend fun set(token: String) { this.token = token }
    override suspend fun clear() { token = null }
}

internal class OneRoomAuthConfig {
    var tokenStore: TokenStore = InMemoryTokenStore()
}

/**
 * AOneRoom authentication.
 *
 * Ported from `js/shared.js:150-180` (`apiFetch`). The upstream flow is:
 *
 *  1. With no valid token, send `X-Client-Token: <ts>,<md5(reverse(ts))>`.
 *  2. The response carries an `x-user` header whose JSON `token` field is a JWT.
 *     Persist it and send it as `Authorization: Bearer <jwt>` thereafter.
 *  3. Locally expire the JWT via its `exp` claim so a stale token is never sent,
 *     and on a 401 drop the token and retry once with a fresh `X-Client-Token`.
 *
 * Implemented entirely inside the [Send] hook so the retry re-runs the original
 * [HttpRequestBuilder] — that keeps the request body intact, which a
 * reconstruct-from-`HttpRequest` approach cannot guarantee for streaming bodies.
 * A [Mutex] serialises token writes so concurrent first-launch requests don't
 * race.
 */
internal val OneRoomAuth = createClientPlugin("OneRoomAuth", ::OneRoomAuthConfig) {
    val store = pluginConfig.tokenStore
    val mutex = Mutex()

    suspend fun captureToken(headerValue: String?) {
        val fresh = headerValue
            ?.let { raw -> runCatching { JSONObject(raw).optString("token") }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: return
        mutex.withLock { store.set(fresh) }
    }

    on(Send) { request ->
        // --- attempt 1: Bearer when we hold a live token, else X-Client-Token.
        val token = mutex.withLock {
            store.get()?.takeIf { !isJwtExpired(it) }
        }
        if (token != null) {
            request.header("Authorization", "Bearer $token")
        } else {
            request.header("X-Client-Token", ClientToken.generate())
        }

        var call = proceed(request)
        captureToken(call.response.headers["x-user"])

        // --- attempt 2: the token was rejected; fall back to a client token.
        if (call.response.status == HttpStatusCode.Unauthorized && token != null) {
            mutex.withLock { store.clear() }

            request.headers.remove("Authorization")
            request.header("X-Client-Token", ClientToken.generate())

            call = proceed(request)
            captureToken(call.response.headers["x-user"])
        }

        call
    }
}

/**
 * Decode the JWT payload and compare `exp` against now, with a 30s safety
 * margin. A malformed token is treated as expired.
 */
internal fun isJwtExpired(
    jwt: String,
    nowSeconds: Long = System.currentTimeMillis() / 1000,
): Boolean {
    val payload = jwt.split('.').getOrNull(1) ?: return true
    val decoded = runCatching {
        String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
    }.getOrNull() ?: return true
    val exp = runCatching { JSONObject(decoded).optLong("exp", 0L) }.getOrDefault(0L)
    if (exp <= 0L) return false // no expiry claim: assume usable
    return exp < (nowSeconds + 30)
}
