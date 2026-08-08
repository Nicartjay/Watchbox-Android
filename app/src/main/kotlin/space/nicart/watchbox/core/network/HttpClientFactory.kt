package space.nicart.watchbox.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Shared Ktor client factory.
 *
 * Two clients exist because the two upstreams have incompatible header needs:
 *  - [createOneRoomClient] talks to the AOneRoom API and must carry the
 *    `X-Client-Token` / Bearer dance plus the `X-Client-Info` timezone blob.
 *  - [createPlainClient] talks to our own Worker and to TMDB, which need none
 *    of that.
 */
object HttpClientFactory {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** Chrome UA, matching the one the Worker spoofs (`stream-helpers.js:6`). */
    const val USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun baseClient(configure: HttpClient.() -> Unit = {}): HttpClient =
        HttpClient(OkHttp) {
            expectSuccess = false

            engine {
                config {
                    retryOnConnectionFailure(true)
                    connectTimeout(15, TimeUnit.SECONDS)
                    readTimeout(30, TimeUnit.SECONDS)
                    callTimeout(60, TimeUnit.SECONDS)
                    followRedirects(true)
                }
            }

            install(ContentNegotiation) { json(json) }

            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            }

            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 2)
                retryOnExceptionIf(maxRetries = 2) { _, cause ->
                    cause is java.io.IOException
                }
                exponentialDelay(base = 2.0, maxDelayMs = 4_000)
            }
        }.also(configure)

    /**
     * Client for the AOneRoom API.
     *
     * [tokenStore] supplies/persists the Bearer JWT; see [OneRoomAuth].
     */
    fun createOneRoomClient(tokenStore: TokenStore): HttpClient =
        HttpClient(OkHttp) {
            expectSuccess = false

            engine {
                config {
                    retryOnConnectionFailure(true)
                    connectTimeout(15, TimeUnit.SECONDS)
                    readTimeout(30, TimeUnit.SECONDS)
                    followRedirects(true)
                }
            }

            install(ContentNegotiation) { json(json) }

            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            }

            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 2)
                exponentialDelay(base = 2.0, maxDelayMs = 4_000)
            }

            install(OneRoomAuth) {
                this.tokenStore = tokenStore
            }

            defaultRequest {
                header("Accept", "application/json")
                header("X-Request-Lang", "en")
                header("X-Client-Info", clientInfoHeader())
                header("User-Agent", USER_AGENT)
            }
        }

    /** Client for our Worker + TMDB. No auth. */
    fun createPlainClient(): HttpClient = baseClient().config {
        defaultRequest {
            header("Accept", "application/json")
            header("User-Agent", USER_AGENT)
        }
    }

    /** `X-Client-Info: {"timezone":"Asia/Manila"}` — resolved once per process. */
    private fun clientInfoHeader(): String {
        val tz = runCatching { TimeZone.getDefault().id }.getOrNull() ?: "UTC"
        // Hand-built rather than serialised: the upstream is picky about key order.
        return """{"timezone":"$tz"}"""
    }
}
