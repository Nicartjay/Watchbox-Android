package space.nicart.watchbox.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Who published a score.
 *
 * The two Rotten Tomatoes meters and nothing else. IMDb and Metacritic were dropped
 * because TMDB's own score is already on the page and a second number out of ten
 * beside it reads as a duplicate rather than as a second opinion. These two earn
 * their place by measuring something it does not: critics and audiences separately.
 */
enum class RatingSource(val label: String) {
    /** Critic score - the Tomatometer. */
    ROTTEN_TOMATOES("Tomatometer"),

    /** Audience score - the Popcornmeter. */
    POPCORNMETER("Popcornmeter"),
}

/**
 * Which mark a score is shown with.
 *
 * The two meters each have their own set, and the thresholds are theirs, not ours:
 * a critic score at or above [FRESH_THRESHOLD] is Fresh and shown as a tomato,
 * below that it is Rotten and shown as a splat, and a score that has not populated
 * is a faded tomato. The audience meter divides at the same point between a full and
 * a tipped bucket. Certified Fresh is a separate award with its own criteria, so it
 * is only ever taken from a service that reports it rather than inferred from the
 * number.
 */
enum class TomatoState {
    /** Critic score at or above the fresh threshold. */
    FRESH,

    /** Critic score below it. */
    ROTTEN,

    /** The best-reviewed titles; awarded, not calculated from the score alone. */
    CERTIFIED_FRESH,

    /** Audience score at or above the threshold. */
    AUDIENCE_FRESH,

    /** Audience score below it. */
    AUDIENCE_SPILLED,

    /** Not enough reviews for a score to populate, or not yet released. */
    NONE,
}

/**
 * Rotten Tomatoes' own threshold, from their published definition: at or above 60%
 * of favourable reviews is Fresh, below it is Rotten. The audience meter divides at
 * the same point between a full and a tipped bucket.
 */
internal const val FRESH_THRESHOLD = 60

/**
 * One external score, already formatted for display.
 *
 * [display] keeps the percent sign rather than a bare number, because both meters
 * are shares of favourable reviews and dropping the unit would leave them looking
 * like scores out of a hundred.
 */
data class ExternalRating(
    val source: RatingSource,
    val display: String,
    /**
     * Which mark to draw.
     *
     * Always present: every score here is one of the two meters, and each has a mark
     * for its state. Where a source reports only a percentage the state is derived
     * from [FRESH_THRESHOLD], which is the same rule the number is read by.
     */
    val state: TomatoState,
    /**
     * How many reviews or ratings the score rests on, when reported.
     *
     * Null rather than zero where the service does not say. It returns 0 for
     * television even on a successful answer, which is absence rather than a count
     * of none, and printing "0 reviews" beside a score would be worse than printing
     * nothing.
     */
    val voteCount: Int? = null,
)

/**
 * External scores for a title.
 *
 * Deliberately an interface with one implementation. The keyless source behind it
 * covers well-known films and almost no television, which is not good enough to be
 * the permanent answer - so the seam is here to let a keyed provider (OMDb returns
 * IMDb, Rotten Tomatoes and Metacritic from one call on an IMDb id) replace it
 * without the UI or the detail load path changing.
 */
interface RatingProvider {
    /**
     * Scores for the title identified by [wikidataId] / [imdbId], or empty.
     *
     * Empty is an ordinary answer, not a failure: most television has no scores
     * recorded, and the caller is expected to omit the row rather than show a gap.
     */
    suspend fun ratings(wikidataId: String?, imdbId: String?): List<ExternalRating>
}

/**
 * A provider that never returns anything.
 *
 * The default, so a caller that has not been given a real one degrades to no
 * ratings rather than to a null check at every use.
 */
object NoRatings : RatingProvider {
    override suspend fun ratings(wikidataId: String?, imdbId: String?): List<ExternalRating> =
        emptyList()
}

/**
 * Reads scores from Wikidata's "review score" statements.
 *
 * Chosen because it needs no key and no scraping: TMDB already hands over a
 * `wikidata_id` on a request the detail page makes anyway, so a score costs one
 * extra call and no credentials.
 *
 * Its limits are real and were measured before choosing it, over eight titles:
 * IMDb on 3, Rotten Tomatoes on 5, Metacritic on 4, and **nothing at all** on
 * Breaking Bad, Arcane or One Piece. Television is largely absent, the values are
 * hand-edited so they lag the live figures, and there are no vote counts. It is
 * therefore treated as best-effort decoration - never awaited before paint, never
 * an error when empty.
 *
 * Rotten Tomatoes appears twice under the same qualifier, once as a percentage
 * (the Tomatometer) and once out of ten (the mean critic score). Only the
 * percentage is kept: it is the figure the brand is known by, and showing both
 * under one name reads as a contradiction.
 */
class WikidataRatingApi(private val client: HttpClient) : RatingProvider {

    /**
     * Cached per entity for the process lifetime, including the empty answer.
     *
     * "This title has no scores" is the common case, and re-asking each time a
     * detail page opens would spend a request to learn nothing.
     */
    private val cache = ConcurrentHashMap<String, List<ExternalRating>>()

    override suspend fun ratings(wikidataId: String?, imdbId: String?): List<ExternalRating> {
        val qid = wikidataId?.takeIf { it.isNotBlank() } ?: return emptyList()

        cache[qid]?.let { return it }

        val body = runCatching {
            val response: HttpResponse = client.get(
                "$BASE?action=wbgetclaims&entity=$qid&property=$REVIEW_SCORE&format=json",
            ) {
                header("User-Agent", USER_AGENT)
            }
            if (!response.status.isSuccess()) return@runCatching null
            response.body<String>()
        }.getOrNull()

        // Not cached on a network failure - unlike an empty result, it may succeed
        // later, and caching it would blank the row for the rest of the session.
        if (body == null) return emptyList()

        val ratings = runCatching { parse(body) }.getOrDefault(emptyList())
        cache[qid] = ratings
        return ratings
    }

    internal companion object {
        const val BASE = "https://www.wikidata.org/w/api.php"
        const val USER_AGENT = "WatchBox/1.0 (+https://github.com/Nicartjay/Watchbox-Android)"

        /** `review score`. */
        const val REVIEW_SCORE = "P444"

        /** `review score by`, which names the publisher. */
        const val SCORE_BY = "P447"

        /**
         * Publisher entity id for the critic meter.
         *
         * Only Rotten Tomatoes is read now. This source also carries IMDb and
         * Metacritic figures, but neither is shown any more, and it has no audience
         * score at all - so the Tomatometer is the whole of what it can contribute.
         *
         * Pinned by id rather than resolved by name, and verified against the live
         * API: an earlier reading had this transposed with IMDb's id, which
         * mislabelled every score while looking entirely correct on screen.
         */
        const val ROTTEN_TOMATOES_ID = 105584

        val json = Json { ignoreUnknownKeys = true }

        /**
         * Pulls the critic score out of a claim list response.
         *
         * Hand-walked rather than modelled as data classes: the statement shape
         * nests five levels deep through `qualifiers` and varies by datatype, while
         * only two leaves matter. A schema for the rest would be a lot of
         * declarations that exist only to be ignored.
         *
         * Internal so the parsing can be tested against captured payloads without a
         * network call - the percentage rule is the part that is wrong silently.
         */
        internal fun parse(body: String): List<ExternalRating> {
            val claims = json.parseToJsonElement(body)
                .jsonObject["claims"]?.jsonObject
                ?.get(REVIEW_SCORE) as? JsonArray
                ?: return emptyList()

            for (claim in claims) {
                val statement = claim as? JsonObject ?: continue
                val value = statement.scoreValue() ?: continue
                if (!statement.isRottenTomatoes()) continue

                // Rotten Tomatoes publishes a percentage and a mean out of ten under
                // the same qualifier; only the percentage carries the brand's meaning,
                // and only it maps onto a Fresh or Rotten mark.
                if (!value.endsWith("%")) continue

                val score = value.removeSuffix("%").trim().toIntOrNull() ?: continue

                // First wins. Where there are several statements - a re-scored release,
                // or a duplicate entry - later ones are not more current, since these
                // are hand-edited and unordered.
                //
                // No Certified Fresh: that is an award this source does not record, and
                // inferring it from the score alone would claim something untrue.
                return listOf(
                    ExternalRating(
                        source = RatingSource.ROTTEN_TOMATOES,
                        display = value,
                        state = if (score >= FRESH_THRESHOLD) {
                            TomatoState.FRESH
                        } else {
                            TomatoState.ROTTEN
                        },
                        // Not recorded here, so left absent rather than guessed at.
                        voteCount = null,
                    ),
                )
            }

            return emptyList()
        }

        /** The score itself, e.g. `81%` or `8.8/10`. */
        private fun JsonObject.scoreValue(): String? =
            (this["mainsnak"] as? JsonObject)
                ?.get("datavalue")?.jsonObject
                ?.get("value")?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        /** Which publisher the score is attributed to, via the `review score by` qualifier. */
        /** Whether this statement's `review score by` qualifier names Rotten Tomatoes. */
        private fun JsonObject.isRottenTomatoes(): Boolean {
            val qualifiers = (this["qualifiers"] as? JsonObject)?.get(SCORE_BY)
                as? JsonArray ?: return false

            for (qualifier in qualifiers) {
                val id = (qualifier as? JsonObject)
                    ?.get("datavalue")?.jsonObject
                    ?.get("value")?.jsonObject
                    ?.get("numeric-id")?.contentOrNull
                    ?.toIntOrNull()
                    ?: continue
                if (id == ROTTEN_TOMATOES_ID) return true
            }
            return false
        }

        /** Primitive content, or null when the node is an object or array. */
        private val JsonElement.contentOrNull: String?
            get() = runCatching { jsonPrimitive.content }.getOrNull()
    }
}
