package space.nicart.watchbox.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Intro and outro timestamps from IntroDB.
 *
 * Covers the titles AniSkip cannot. AniSkip is keyed on a MyAnimeList id, so it holds anime and
 * nothing else - a live-action series never has skip times there however popular it is. IntroDB is
 * keyed on an IMDb id, which TMDB already gives us for everything.
 *
 * Anonymous reads, no key. Failure is silent for the same reason it is in [AniSkipApi]: a title
 * with no submissions is the ordinary case, not a fault, and the absence of a skip button is the
 * correct outcome rather than something to report.
 */
class IntroDbApi(private val client: HttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Intervals for one episode, or empty when IntroDB has none.
     *
     * [imdbId] is accepted with or without its `tt` prefix; the endpoint wants it as TMDB reports
     * it, which is with.
     */
    suspend fun skipTimes(
        imdbId: String,
        season: Int,
        episodeNumber: Int,
    ): List<SkipInterval> {
        if (imdbId.isBlank() || season <= 0 || episodeNumber <= 0) return emptyList()

        return runCatching {
            val response = client.get("$BASE/segments") {
                url.parameters.append("imdb_id", imdbId)
                url.parameters.append("season", season.toString())
                url.parameters.append("episode", episodeNumber.toString())
            }
            if (!response.status.isSuccess()) return emptyList()

            val dto = json.decodeFromString<SegmentsResponse>(response.bodyAsText())

            // Ordered as they occur in an episode, since the player shows whichever the playhead
            // is inside and a recap precedes the opening.
            listOfNotNull(
                dto.recap?.toInterval(SkipKind.RECAP),
                dto.intro?.toInterval(SkipKind.OPENING),
                dto.outro?.toInterval(SkipKind.ENDING),
            )
        }.onFailure {
            android.util.Log.w(
                TAG,
                "introdb lookup failed for $imdbId s${season}e$episodeNumber: " +
                    "${it::class.java.simpleName}: ${it.message}",
            )
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val BASE = "https://api.introdb.app"
        const val TAG = "IntroDb"
    }
}

@Serializable
private data class SegmentsResponse(
    val intro: SegmentDto? = null,
    val recap: SegmentDto? = null,
    val outro: SegmentDto? = null,
)

@Serializable
private data class SegmentDto(
    /**
     * Milliseconds, which the endpoint supplies alongside its seconds fields.
     *
     * Taken in preference to `start_sec` because those are fractional - an outro at 3631.5s - and
     * converting them here would repeat arithmetic the API has already done.
     */
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
    /**
     * How much the community trusts this row.
     *
     * Present so a disputed timestamp can be ignored rather than acted on: a skip button that
     * jumps to the wrong place is worse than none, and IntroDB flags challenged rows rather than
     * removing them.
     */
    val confidence: Double = 0.0,
) {
    fun toInterval(kind: SkipKind): SkipInterval? {
        val start = startMs ?: return null
        val end = endMs ?: return null

        // A zero-length or inverted interval would place a button that skips nothing.
        if (end <= start) return null
        if (confidence < MIN_CONFIDENCE) return null

        return SkipInterval(kind = kind, startMs = start, endMs = end)
    }

    private companion object {
        /**
         * Below this a row is treated as absent.
         *
         * IntroDB reports 1 for an accepted timestamp and lowers it while a challenge is open, so
         * this admits settled data and skips anything under review.
         */
        const val MIN_CONFIDENCE = 1.0
    }
}
