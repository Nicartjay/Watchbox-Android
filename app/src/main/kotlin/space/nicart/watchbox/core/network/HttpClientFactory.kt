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
import java.util.concurrent.TimeUnit

/**
 * Ktor client used for repository metadata and extension APK downloads.
 *
 * Deliberately separate from the OkHttp client the extensions use: that one
 * carries source cookies and a spoofed User-Agent, which have no business being
 * attached to plain repository requests.
 */
object HttpClientFactory {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * Chrome UA sent on every first-party request.
     *
     * Deliberately kept identical to the value [NetworkHelper] hands extensions
     * and to the one the WatchBox Next web front end's stream proxy spoofs, so a
     * host that accepts one accepts all three. Changing it here without changing
     * it there reintroduces the class of 403 that only appears on one client.
     */
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

    /** Plain JSON/file client. No auth, no cookies. */
    fun createPlainClient(): HttpClient = baseClient().config {
        defaultRequest {
            header("Accept", "application/json")
            header("User-Agent", USER_AGENT)
        }
    }

}
