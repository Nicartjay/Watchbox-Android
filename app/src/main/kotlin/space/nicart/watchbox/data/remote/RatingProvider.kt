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

/** Who published a score. */
enum class RatingSource(val label: String) {
    IMDB("IMDb"),
    ROTTEN_TOMATOES("RT"),
    METACRITIC("Metacritic"),
}

/**
 * One external score, already formatted for display.
 *
 * [display] carries the units the source is read in rather than a bare number,
 * because they are not interchangeable: IMDb is out of ten, the Tomatometer is a
 * percentage of favourable reviews, and Metacritic is a weighted 0-100. Rendering
 * all three as "8.2" would invite comparison between scales that do not compare.
 */
data class ExternalRating(
    val source: RatingSource,
    val display: String,
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
         * Publisher entity ids.
         *
         * Verified against the live API rather than taken from documentation: an
         * earlier reading of these had IMDb and Rotten Tomatoes transposed, which
         * mislabelled every score. Metacritic (Q150248) has no English label, so
         * it cannot be resolved by name and has to be pinned by id.
         */
        val SOURCES = mapOf(
            37312 to RatingSource.IMDB,
            105584 to RatingSource.ROTTEN_TOMATOES,
            150248 to RatingSource.METACRITIC,
        )

        val json = Json { ignoreUnknownKeys = true }

        /**
         * Pulls `(source, value)` pairs out of a claim list response.
         *
         * Hand-walked rather than modelled as data classes: the statement shape
         * nests five levels deep through `qualifiers` and varies by datatype, while
         * only two leaves matter. A schema for the rest would be a lot of
         * declarations that exist only to be ignored.
         *
         * Internal so the parsing can be tested against captured payloads without a
         * network call - the ordering and the Rotten Tomatoes rule are the parts
         * that are wrong silently.
         */
        internal fun parse(body: String): List<ExternalRating> {
            val claims = json.parseToJsonElement(body)
                .jsonObject["claims"]?.jsonObject
                ?.get(REVIEW_SCORE) as? JsonArray
                ?: return emptyList()

            val found = LinkedHashMap<RatingSource, String>()

            for (claim in claims) {
                val statement = claim as? JsonObject ?: continue
                val value = statement.scoreValue() ?: continue
                val source = statement.scoreSource() ?: continue

                // Rotten Tomatoes publishes a percentage and a mean out of ten under
                // the same qualifier; only the percentage carries the brand's meaning.
                if (source == RatingSource.ROTTEN_TOMATOES && !value.endsWith("%")) continue

                // First wins. Where a source has several statements - a re-scored
                // release, or a duplicate entry - later ones are not more current,
                // since these are hand-edited and unordered.
                found.putIfAbsent(source, value)
            }

            // Fixed order, so the row does not reshuffle between titles according to
            // however the statements happened to be stored.
            return RatingSource.entries.mapNotNull { source ->
                found[source]?.let { ExternalRating(source, it) }
            }
        }

        /** The score itself, e.g. `81%` or `8.8/10`. */
        private fun JsonObject.scoreValue(): String? =
            (this["mainsnak"] as? JsonObject)
                ?.get("datavalue")?.jsonObject
                ?.get("value")?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        /** Which publisher the score is attributed to, via the `review score by` qualifier. */
        private fun JsonObject.scoreSource(): RatingSource? {
            val qualifiers = (this["qualifiers"] as? JsonObject)?.get(SCORE_BY)
                as? JsonArray ?: return null

            for (qualifier in qualifiers) {
                val id = (qualifier as? JsonObject)
                    ?.get("datavalue")?.jsonObject
                    ?.get("value")?.jsonObject
                    ?.get("numeric-id")?.contentOrNull
                    ?.toIntOrNull()
                    ?: continue
                SOURCES[id]?.let { return it }
            }
            return null
        }

        /** Primitive content, or null when the node is an object or array. */
        private val JsonElement.contentOrNull: String?
            get() = runCatching { jsonPrimitive.content }.getOrNull()
    }
}
