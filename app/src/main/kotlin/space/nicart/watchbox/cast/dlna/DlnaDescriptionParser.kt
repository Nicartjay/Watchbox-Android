package space.nicart.watchbox.cast.dlna

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI

/**
 * A DLNA renderer resolved from its description XML.
 *
 * A device is only usable if it exposes AVTransport; RenderingControl is optional
 * and only needed for volume.
 */
data class DlnaRenderer(
    val friendlyName: String,
    val location: String,
    val host: String,
    val avTransportControlUrl: String,
    val renderingControlUrl: String?,
)

/**
 * Fetches and parses UPnP device description XML.
 *
 * Two details here are easy to get wrong and both cost you real devices:
 *
 *  - Services are matched on **serviceId**, not serviceType. Vendors are far more
 *    consistent about `urn:upnp-org:serviceId:AVTransport` than about the
 *    versioned service type.
 *  - `<deviceList>` must be walked recursively. Many roots are containers whose
 *    nested device is the actual renderer, so a flat parse finds nothing on them.
 */
class DlnaDescriptionParser(private val client: OkHttpClient) {

    suspend fun resolveAll(locations: Collection<String>): List<DlnaRenderer> =
        coroutineScope {
            locations.map { location -> async { resolve(location) } }
                .awaitAll()
                .flatten()
                .distinctBy { it.avTransportControlUrl }
        }

    private suspend fun resolve(location: String): List<DlnaRenderer> =
        withContext(Dispatchers.IO) {
            val xml = fetch(location) ?: return@withContext emptyList()

            val host = runCatching { URI(location).host }.getOrNull()
                ?: return@withContext emptyList()

            // URLBase overrides the location when present; otherwise relative
            // control URLs resolve against the description URL itself.
            val base = xml.tagValue("URLBase")
                ?.takeIf { it.startsWith("http", true) }
                ?: location

            parseDevices(xml, base, host, location)
        }

    private suspend fun fetch(location: String): String? = runCatching {
        val request = Request.Builder()
            .url(location)
            .header("Connection", "close")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null

            // peekBody caps the read. The obvious-looking
            // `source().readString(MAX, UTF_8)` is NOT a cap: okio treats the
            // byte count as an exact length and throws EOFException when the body
            // is shorter, and a description is only a few KB - so that form
            // discarded every device it ever fetched, silently, because the
            // failure was swallowed as a fetch error.
            response.peekBody(MAX_DESCRIPTION_BYTES).string()
        }
    }.onFailure {
        Log.d(TAG, "description fetch failed for $location: ${it.javaClass.simpleName}")
    }.getOrNull()

    /**
     * Extracts every AVTransport-bearing device, including nested ones.
     *
     * Parsed with targeted matching rather than a full XML parser: descriptions
     * are small and vendor XML is frequently malformed in ways that make a strict
     * parser throw where substring matching still succeeds.
     */
    private fun parseDevices(
        xml: String,
        base: String,
        host: String,
        location: String,
    ): List<DlnaRenderer> {
        val renderers = mutableListOf<DlnaRenderer>()

        DEVICE_BLOCK.findAll(xml).forEach { match ->
            val block = match.value

            val avTransport = block.controlUrlFor(AV_TRANSPORT_SERVICE_ID)
                ?: return@forEach

            val name = block.tagValue("friendlyName")
                ?: xml.tagValue("friendlyName")
                ?: host

            renderers += DlnaRenderer(
                friendlyName = name,
                location = location,
                host = host,
                avTransportControlUrl = absolute(avTransport, base) ?: return@forEach,
                renderingControlUrl = block.controlUrlFor(RENDERING_CONTROL_SERVICE_ID)
                    ?.let { absolute(it, base) },
            )
        }

        return renderers
    }

    /** Finds the controlURL belonging to a specific serviceId. */
    private fun String.controlUrlFor(serviceId: String): String? =
        SERVICE_BLOCK.findAll(this)
            .firstOrNull { it.value.contains(serviceId, ignoreCase = true) }
            ?.value
            ?.tagValue("controlURL")

    private fun String.tagValue(tag: String): String? =
        Regex("<$tag[^>]*>(.*?)</$tag>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * Resolves a possibly-relative control URL.
     *
     * A relative path whose first segment contains a colon breaks [URI]
     * resolution, so it is prefixed with `./` — a real quirk on some renderers,
     * not a hypothetical one.
     */
    private fun absolute(url: String, base: String): String? {
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) return url

        val safe = if (!url.startsWith("/") && url.substringBefore('/').contains(':')) {
            "./$url"
        } else {
            url
        }

        return runCatching { URI(base).resolve(safe).toString() }.getOrNull()
    }

    private companion object {
        const val TAG = "DlnaDescription"
        const val MAX_DESCRIPTION_BYTES = 512L * 1024
        const val AV_TRANSPORT_SERVICE_ID = "serviceId:AVTransport"
        const val RENDERING_CONTROL_SERVICE_ID = "serviceId:RenderingControl"

        val DEVICE_BLOCK = Regex(
            "<device>(?:(?!<device>).)*?</device>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )

        val SERVICE_BLOCK = Regex(
            "<service>.*?</service>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
    }
}
