package space.nicart.watchbox.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/**
 * Resolves the viewer's country for region-specific data.
 *
 * Needed because TMDB's availability differs substantially by country - one service in PH
 * against seven in US for the same title - so the wrong region misrepresents what can
 * actually be watched.
 *
 * Resolved from the network rather than the device locale. A locale reflects the language
 * the user chose, not where they are: an English-language phone in Manila reports US or GB
 * and would show the wrong catalogue. The network address is where they actually are.
 *
 * No location permission and no API key. Cloudflare's trace endpoint reports the country it
 * sees the request coming from, in 234 bytes, and every device already reaches Cloudflare.
 * Using the coarse network view rather than GPS also means nothing here can identify a
 * person - it is the same information any website already has.
 *
 * The device locale remains the fallback, so a blocked or slow endpoint degrades to the
 * previous behaviour rather than losing the section.
 */
class CountryResolver(private val client: HttpClient) {

    @Volatile
    private var cached: String? = null

    /**
     * The viewer's two-letter country code, upper-cased.
     *
     * Cached for the process: a country does not change while the app is open, and this is
     * consulted on every detail page.
     */
    suspend fun country(): String {
        cached?.let { return it }

        val resolved = fromNetwork() ?: fromLocale()
        cached = resolved
        return resolved
    }

    private suspend fun fromNetwork(): String? = runCatching {
        val response = client.get(TRACE_URL)
        if (!response.status.isSuccess()) return null

        // A flat `key=value` list, one per line. Parsed by prefix rather than split into a
        // map, because the endpoint adds fields over time and only one is wanted.
        response.bodyAsText()
            .lineSequence()
            .firstOrNull { it.startsWith("loc=") }
            ?.removePrefix("loc=")
            ?.trim()
            ?.takeIf { it.length == 2 }
            ?.uppercase()
    }.onFailure {
        android.util.Log.d(TAG, "country lookup failed: ${it::class.java.simpleName}")
    }.getOrNull()

    /**
     * The locale's country, or US.
     *
     * US rather than an empty string because an unmatched key returns nothing from TMDB's
     * provider map, and US has the richest data - so the fallback still shows something.
     */
    private fun fromLocale(): String =
        java.util.Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase() ?: "US"

    private companion object {
        const val TAG = "WbCountry"

        /** Cloudflare's own trace: no key, no permission, and reachable from anywhere. */
        const val TRACE_URL = "https://cloudflare.com/cdn-cgi/trace"
    }
}
