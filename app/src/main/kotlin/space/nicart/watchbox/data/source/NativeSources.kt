package space.nicart.watchbox.data.source

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.nicart.watchbox.data.remote.urlEncoded

/**
 * Native stream sources.
 *
 * The Worker exposes one resolver route per provider; every route takes the same
 * `action=resolve&keyword=&title=&type=&episode=` query and answers with the same
 * JSON envelope. The web app implements this as six ~85%-identical adapter files
 * (`js/kisskh-source.js`, `js/dramacool-source.js`, ...); here it collapses into
 * one [NativeSourceResolver] driven by the [NativeServer] table.
 */
@Serializable
data class NativeSourcesResponse(
    val sources: List<NativeStream>? = null,
    val subtitles: List<NativeSubtitle>? = null,
    val servers: List<NativeHost>? = null,
    val langs: List<NativeLang>? = null,
    val server: String? = null,
    val type: String? = null,
    val intro: SkipRange? = null,
    val outro: SkipRange? = null,
    val error: String? = null,
)

@Serializable
data class NativeStream(
    val url: String = "",
    val quality: String = "",
    val type: String? = null,
)

@Serializable
data class NativeSubtitle(
    val url: String = "",
    val lang: String = "",
    val label: String = "",
)

/** A selectable upstream host for multi-host providers (Atlas, Rigel). */
@Serializable
data class NativeHost(
    val label: String = "",
    val url: String = "",
    val type: String? = null,
)

/** A selectable audio track (Polaris sub/dub). */
@Serializable
data class NativeLang(
    val lang: String = "",
    val label: String = "",
    val url: String = "",
    val type: String? = null,
    val subtitles: List<NativeSubtitle>? = null,
    val intro: SkipRange? = null,
    val outro: SkipRange? = null,
)

@Serializable
data class SkipRange(
    @SerialName("start") val start: Double = 0.0,
    @SerialName("end") val end: Double = 0.0,
) {
    val isValid: Boolean get() = end > start && end > 0.0
}

/**
 * The server registry.
 *
 * Mirrors `js/detail/servers.js:13-62`, but keyed by a stable [id] rather than
 * array position. The web version derives its native -> native -> embed fallback
 * order from array index, which silently changes if the list is reordered; here
 * the order is explicit in [NativeServer.entries] and asserted by [fallbackOrder].
 *
 * [devOnly] servers are unreachable from Cloudflare egress IPs (the upstream CDNs
 * WAF-block datacentre ranges), so they are hidden in release builds exactly as
 * the web app hides them off-localhost.
 */
enum class NativeServer(
    val id: String,
    val displayName: String,
    val route: String,
    val devOnly: Boolean = false,
    val animeOnly: Boolean = false,
    val supportsHostPicker: Boolean = false,
    val supportsAudioPicker: Boolean = false,
) {
    KISSKH(
        id = "kisskh-native",
        displayName = "Nova",
        route = "kisskh",
        devOnly = true,
    ),
    DRAMACOOL(
        id = "dramacool-native",
        displayName = "Lumen",
        route = "dramacool",
    ),
    STREAMHG(
        id = "streamhg-native",
        displayName = "Sirius",
        route = "streamhg",
    ),
    DRAMANICE(
        id = "dramanice-native",
        displayName = "Atlas",
        route = "dramanice",
        supportsHostPicker = true,
    ),
    ASIAFLIX(
        id = "asiaflix-native",
        displayName = "Rigel",
        route = "asiaflix",
        supportsHostPicker = true,
    ),
    ANIKOTO(
        id = "anikoto-native",
        displayName = "Polaris",
        route = "anikoto",
        animeOnly = true,
        supportsAudioPicker = true,
    ),
    ;

    companion object {
        /**
         * Explicit fallback order. Anime-capable Polaris is tried first for anime
         * content, then the general-purpose providers by reliability.
         */
        fun fallbackOrder(isAnime: Boolean, includeDevOnly: Boolean): List<NativeServer> =
            entries
                .filter { includeDevOnly || !it.devOnly }
                .filter { !it.animeOnly || isAnime }
                .sortedBy { server ->
                    when {
                        isAnime && server == ANIKOTO -> 0
                        server == ASIAFLIX -> 1
                        server == DRAMANICE -> 2
                        server == STREAMHG -> 3
                        server == DRAMACOOL -> 4
                        else -> 5
                    }
                }

        fun byId(id: String?): NativeServer? = entries.firstOrNull { it.id == id }
    }
}

/** Outcome of a resolve attempt. */
sealed interface ResolveResult {
    data class Success(
        val server: NativeServer,
        val payload: NativeSourcesResponse,
    ) : ResolveResult

    data class Failure(
        val server: NativeServer,
        val reason: String,
    ) : ResolveResult
}

/**
 * Calls the Worker's resolver routes.
 *
 * One implementation replaces the web app's six duplicate adapters. Callers get
 * either the first success from [resolveWithFallback] or a list of the failures.
 */
class NativeSourceResolver(
    private val client: HttpClient,
    private val workerBaseProvider: suspend () -> String,
) {

    suspend fun resolve(
        server: NativeServer,
        title: String,
        year: String?,
        isSeries: Boolean,
        episode: Int?,
        tmdbId: Int?,
        malId: Int?,
    ): ResolveResult {
        val base = workerBaseProvider().trimEnd('/')
        val params = buildString {
            append("action=resolve")
            append("&keyword=").append(title.urlEncoded())
            append("&title=").append(title.urlEncoded())
            append("&type=").append(if (isSeries) "tv" else "movie")
            if (year != null) append("&year=").append(year.urlEncoded())
            if (isSeries && episode != null) append("&episode=").append(episode)
            if (server == NativeServer.ASIAFLIX && tmdbId != null) append("&tmdbId=").append(tmdbId)
            if (server == NativeServer.ANIKOTO && malId != null) append("&mal=").append(malId)
        }

        val response = runCatching {
            client.get("$base/api/${server.route}?$params")
        }.getOrElse {
            return ResolveResult.Failure(server, it.message ?: "network error")
        }

        if (!response.status.isSuccess()) {
            return ResolveResult.Failure(server, "HTTP ${response.status.value}")
        }

        val payload = runCatching { response.body<NativeSourcesResponse>() }
            .getOrElse { return ResolveResult.Failure(server, "malformed response") }

        payload.error?.takeIf { it.isNotBlank() }?.let {
            return ResolveResult.Failure(server, it)
        }
        if (payload.sources.isNullOrEmpty()) {
            return ResolveResult.Failure(server, "no sources")
        }
        return ResolveResult.Success(server, payload)
    }

    /**
     * Try every eligible server in order and return the first success.
     *
     * This is the piece the web app is missing for iframe servers — there,
     * embed failures are never detected (`js/detail/player.js:919-945` sets
     * `iframe.src` and returns). Here every provider is native, so a failure is
     * always observable and the chain always advances.
     */
    suspend fun resolveWithFallback(
        title: String,
        year: String?,
        isSeries: Boolean,
        episode: Int?,
        tmdbId: Int?,
        malId: Int?,
        isAnime: Boolean,
        includeDevOnly: Boolean,
        preferred: NativeServer? = null,
        onAttempt: (NativeServer) -> Unit = {},
    ): Pair<ResolveResult.Success?, List<ResolveResult.Failure>> {
        val ordered = buildList {
            preferred?.let(::add)
            addAll(
                NativeServer.fallbackOrder(isAnime, includeDevOnly)
                    .filter { it != preferred },
            )
        }

        val failures = mutableListOf<ResolveResult.Failure>()
        for (server in ordered) {
            onAttempt(server)
            when (val result = resolve(server, title, year, isSeries, episode, tmdbId, malId)) {
                is ResolveResult.Success -> return result to failures
                is ResolveResult.Failure -> failures += result
            }
        }
        return null to failures
    }
}
