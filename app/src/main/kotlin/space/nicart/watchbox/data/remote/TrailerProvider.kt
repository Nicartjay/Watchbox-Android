package space.nicart.watchbox.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A playable trailer file.
 *
 * A direct stream rather than a watch-page link, which is what makes it usable in
 * the hero: Media3 can decode this, where the YouTube keys in [TmdbVideo] would
 * need an embed.
 */
data class Trailer(
    val url: String,
    /** Container type as the service reports it, e.g. `video/mp4`. */
    val mimeType: String,
)

/**
 * Where hero trailers come from.
 *
 * An interface for the same reason [RatingProvider] is one, and rather more
 * urgently: the implementation behind it is a third party's private endpoint with
 * no versioning and no guarantee of continued existence. When it stops answering,
 * only the class behind this changes.
 */
interface TrailerProvider {
    /**
     * A trailer for the TMDB id, or null.
     *
     * Null is an ordinary answer - not every title has one, and the caller falls
     * back to the backdrop rather than reporting a failure.
     */
    suspend fun trailer(tmdbId: Int, isMovie: Boolean): Trailer?
}

/** A provider that never returns anything, so a caller with no real one shows backdrops. */
object NoTrailers : TrailerProvider {
    override suspend fun trailer(tmdbId: Int, isMovie: Boolean): Trailer? = null
}

/**
 * Resolves a trailer to a direct MP4, keyed on a TMDB id.
 *
 * ## Why this rather than IMDb or TMDB directly
 *
 * TMDB returns YouTube keys and nothing else - every video across the titles
 * surveyed is `site: "YouTube"`, and the payload carries no stream URL - so Media3
 * cannot play one without an embed.
 *
 * IMDb hosts real MP4s but does not expose which. Its title pages answer an AWS
 * WAF challenge (HTTP 202 with a JS captcha) and `api.graphql.imdb.com` answers
 * 403, so the video id cannot be discovered from a client. The id *is* obtainable
 * from the keyless suggestion endpoint, but the files are served from signed
 * CloudFront URLs and the unsigned path exists for almost nothing: measured across
 * ten titles, two of twenty videos resolved, both from a single recent release.
 *
 * This service does the signing that is otherwise inaccessible. Measured across
 * twelve titles - films and television, new and old - all twelve returned a signed
 * URL and all twelve served decodable MP4 bytes.
 *
 * ## What that costs
 *
 * It is not a public API. There is no version, no documented contract, and no
 * commitment that it will answer tomorrow; it may begin requiring a `Referer`,
 * rate-limit, or disappear. The signed URLs carry roughly a day's expiry, so
 * nothing is worth persisting - only held for the session.
 *
 * Every one of those failures degrades to the same place: no trailer, and the hero
 * shows its backdrop. That is why this is acceptable at all, and why the caller
 * must treat a null as ordinary rather than as an error.
 */
class SheguTrailerApi(private val client: HttpClient) : TrailerProvider {

    /**
     * Cached per id for the process lifetime, misses included.
     *
     * A detail page is often reopened in one session, and the service caches at its
     * own edge anyway. Not persisted: the URLs expire, so a stored one would resolve
     * to a 403 rather than a video.
     */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Trailer>()

    /** Ids known to have no trailer, kept apart so a miss is not re-requested. */
    private val misses = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    override suspend fun trailer(tmdbId: Int, isMovie: Boolean): Trailer? {
        if (tmdbId <= 0) return null

        val key = "$tmdbId:${if (isMovie) "movie" else "tv"}"
        cache[key]?.let { return it }
        if (key in misses) return null

        val body = runCatching {
            val response: HttpResponse = client.get(
                "$BASE/trailer?tmdb=$tmdbId&type=${if (isMovie) "movie" else "tv"}",
            ) {
                header("User-Agent", USER_AGENT)
            }
            // 404 is how it says "no trailer for this title", which is a settled
            // answer rather than a fault - recorded as a miss below.
            if (response.status.value == 404) return@runCatching NOT_FOUND
            if (!response.status.isSuccess()) return@runCatching null
            response.body<String>()
        }.getOrNull()

        if (body == NOT_FOUND) {
            misses += key
            return null
        }

        // Left uncached on a network failure: unlike a 404 this may succeed later,
        // and caching it would disable trailers for the rest of the session.
        if (body == null) return null

        val trailer = parse(body)
        if (trailer == null) {
            misses += key
            return null
        }

        cache[key] = trailer
        return trailer
    }

    internal companion object {
        const val BASE = "https://trailer.shegu.st"
        const val USER_AGENT = "WatchBox/1.0 (+https://github.com/Nicartjay/Watchbox-Android)"

        /** Sentinel for "answered, and there is nothing", distinct from a failure. */
        const val NOT_FOUND = "\u0000not-found"

        val json = Json { ignoreUnknownKeys = true }

        /**
         * Reads a trailer out of a response body, or null.
         *
         * Internal so the tolerance can be tested without a network call. The service
         * is a third party's private endpoint with no versioned contract, so a body
         * that is not what this expects has to yield null rather than throw - the
         * hero falls back to its backdrop, and the page is unaffected.
         *
         * A blank or absent `url` counts as nothing rather than as a malformed
         * answer: both mean there is no video to play, and the caller does the same
         * thing either way.
         */
        internal fun parse(body: String): Trailer? {
            val dto = runCatching { json.decodeFromString<TrailerDto>(body) }.getOrNull()
                ?: return null
            val url = dto.url?.takeIf { it.isNotBlank() } ?: return null
            // Defaulted rather than required: the container is knowable from the file
            // itself, and refusing a playable URL over a missing label would be a
            // poor trade.
            return Trailer(url = url, mimeType = dto.mime?.takeIf { it.isNotBlank() } ?: "video/mp4")
        }
    }
}

@Serializable
private data class TrailerDto(
    val url: String? = null,
    val mime: String? = null,
    /** Which upstream the file came from; carried for diagnosis only. */
    val source: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
)
