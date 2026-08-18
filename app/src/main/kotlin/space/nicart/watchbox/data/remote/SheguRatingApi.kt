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
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads scores from the same service the trailers come from.
 *
 * Preferred over [WikidataRatingApi] because it answers what that one cannot: an
 * audience score, live values rather than hand-edited ones, review counts, and all
 * of it from a single call keyed on a TMDB id - no `external_ids` hop, no entity
 * lookup.
 *
 * ## Reliability
 *
 * Measured over ten titles it answered three and returned HTTP 502 for seven, and
 * the failures are neither rate-limiting nor a block: retries spaced six seconds
 * apart failed identically, while the service root and the trailer route answered
 * 200 throughout. The same title can fail and then succeed, so it is per-request
 * rather than per-title.
 *
 * That is why [ChainedRatingApi] exists rather than this replacing the Wikidata
 * source outright. When this answers, its data is better; when it does not, a thin
 * score is better than none.
 */
class SheguRatingApi(private val client: HttpClient) : RatingProvider {

    private val cache = ConcurrentHashMap<String, List<ExternalRating>>()

    /**
     * Not cached by TMDB id here.
     *
     * The interface is keyed on a Wikidata or IMDb id, so this takes the TMDB id
     * through [ratingsFor] instead - see the note there.
     */
    override suspend fun ratings(wikidataId: String?, imdbId: String?): List<ExternalRating> =
        emptyList()

    /**
     * Scores for a TMDB id, or empty.
     *
     * A separate entry point from [RatingProvider.ratings] because this service is
     * keyed on the id the app already has, where the Wikidata one needs an entity
     * id. The repository calls whichever a provider supports.
     */
    suspend fun ratingsFor(tmdbId: Int, isMovie: Boolean): List<ExternalRating> {
        if (tmdbId <= 0) return emptyList()

        val key = "$tmdbId:${if (isMovie) "movie" else "tv"}"
        cache[key]?.let { return it }

        val body = runCatching {
            val response: HttpResponse = client.get(
                "$BASE/info?type=${if (isMovie) "movie" else "tv"}&tmdb=$tmdbId",
            ) {
                header("User-Agent", USER_AGENT)
                // Sent because the service is CORS-configured for a browser origin and
                // answers more consistently with one present.
                header("Origin", ORIGIN)
            }
            if (!response.status.isSuccess()) return@runCatching null
            response.body<String>()
        }.getOrNull()

        // Deliberately not cached on failure, and not recorded as a miss either. A 502
        // here is transient - the same id answers on a later attempt - so caching it
        // would blank the row for the rest of the session over a momentary fault.
        if (body == null) return emptyList()

        val ratings = parse(body)
        // Only a real answer is worth keeping. An empty parse of a 200 means the
        // service has nothing for this title, which is settled, but it is cheap to
        // re-ask and avoids pinning an empty result from a half-built response.
        if (ratings.isNotEmpty()) cache[key] = ratings
        return ratings
    }

    internal companion object {
        const val BASE = "https://api.shegu.st"
        const val ORIGIN = "https://cinejoy.to"
        const val USER_AGENT = "WatchBox/1.0 (+https://github.com/Nicartjay/Watchbox-Android)"

        /**
         * Rotten Tomatoes' own threshold, from their published definition: at or above
         * 60% of favourable reviews is Fresh, below it is Rotten. The audience meter
         * divides at the same point between a full and a tipped bucket.
         */
        const val FRESH_THRESHOLD = 60

        val json = Json { ignoreUnknownKeys = true }

        /**
         * Reads the scores out of a response body.
         *
         * Internal so it can be tested against captured payloads. Every field is
         * optional and independently guarded: this is an undocumented endpoint whose
         * shape can change, and a missing score must cost only that score rather than
         * the whole row.
         */
        internal fun parse(body: String): List<ExternalRating> {
            val dto = runCatching { json.decodeFromString<InfoDto>(body) }.getOrNull()
                ?: return emptyList()

            return buildList {
                // No IMDb score, though the service reports one: TMDB's own is already
                // on the page, and a second figure out of ten beside it reads as a
                // duplicate rather than as a second opinion.
                dto.tomatoMeter?.takeIf { it > 0 }?.let { score ->
                    add(
                        ExternalRating(
                            source = RatingSource.ROTTEN_TOMATOES,
                            display = "$score%",
                            state = criticState(score, dto.tomatoMeterState),
                            voteCount = dto.tomatoMeterCount?.takeIf { it > 0 },
                        ),
                    )
                }

                dto.audienceScore?.takeIf { it > 0 }?.let { score ->
                    add(
                        ExternalRating(
                            source = RatingSource.POPCORNMETER,
                            display = "$score%",
                            state = if (score >= FRESH_THRESHOLD) {
                                TomatoState.AUDIENCE_FRESH
                            } else {
                                TomatoState.AUDIENCE_SPILLED
                            },
                            voteCount = dto.audienceScoreCount?.takeIf { it > 0 },
                        ),
                    )
                }
            }
        }

        /**
         * Which critic mark a score earns.
         *
         * Certified Fresh is an award with its own criteria - a minimum number of
         * reviews, a share of them from top critics - so it is only ever taken from the
         * service's own field, never inferred. Everything else is the published 60%
         * split.
         *
         * Note the field reads `"none"` on a title that is plainly Fresh, so it means
         * "not certified" rather than "no score"; treating it as the latter would show
         * a faded tomato beside 81%.
         */
        private fun criticState(score: Int, reported: String?): TomatoState = when {
            reported?.contains("certified", ignoreCase = true) == true -> TomatoState.CERTIFIED_FRESH
            score >= FRESH_THRESHOLD -> TomatoState.FRESH
            else -> TomatoState.ROTTEN
        }
    }
}

/**
 * Tries each provider in turn and takes the first that answers.
 *
 * Exists because the richer source is the less reliable one. The service behind
 * [SheguRatingApi] returns an audience score and live figures but fails often; the
 * Wikidata source is dependable for well-known films and silent on most
 * television. Neither alone is a good answer, and falling through gives the better
 * data when it is there without losing the row when it is not.
 */
class ChainedRatingApi(
    private val primary: SheguRatingApi,
    private val fallback: RatingProvider,
) : RatingProvider {

    override suspend fun ratings(wikidataId: String?, imdbId: String?): List<ExternalRating> =
        fallback.ratings(wikidataId, imdbId)

    /**
     * Scores for a title, preferring the primary source.
     *
     * All-or-nothing rather than merged per publisher. The two disagree - Wikidata's
     * figures are edited by hand and lag - so a row showing this service's Tomatometer
     * beside Wikidata's IMDb score would be two different vintages presented as one
     * set.
     */
    suspend fun ratingsFor(
        tmdbId: Int,
        isMovie: Boolean,
        wikidataId: String?,
    ): List<ExternalRating> {
        primary.ratingsFor(tmdbId, isMovie).takeIf { it.isNotEmpty() }?.let { return it }
        return fallback.ratings(wikidataId, null)
    }
}

@Serializable
private data class InfoDto(
    @SerialName("imdb_rating") val imdbRating: String? = null,
    @SerialName("tomato_meter") val tomatoMeter: Int? = null,
    @SerialName("tomato_meter_count") val tomatoMeterCount: Int? = null,
    @SerialName("tomato_meter_state") val tomatoMeterState: String? = null,
    @SerialName("audience_score") val audienceScore: Int? = null,
    @SerialName("audience_score_count") val audienceScoreCount: Int? = null,
    /** Age rating, e.g. `R`. Carried for a future field rather than shown yet. */
    @SerialName("content_rating") val contentRating: String? = null,
)
