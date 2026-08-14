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
 * Opening and ending timestamps from [AniSkip](https://api.aniskip.com).
 *
 * ## Why this needs an id the app does not have
 *
 * AniSkip is keyed on **MyAnimeList id**, and nothing in this app carries one. The extension ABI
 * has no id field at all - `SAnime` exposes only `url`, `title`, `thumbnail_url` and a few
 * strings - and TMDB, the app's enrichment source, speaks its own ids.
 *
 * The bridge is [ArmApi], which maps TMDB ids to MAL ids. Deriving the id from a *title search*
 * was rejected: AniList's fuzzy search answers confidently even when wrong - searching
 * "The Matrix" returns "Transformers the Movie" - and a bad match attaches another show's
 * timestamps, which is worse than showing no button at all.
 *
 * ## Failure is always silent
 *
 * Every failure path returns no intervals rather than surfacing an error. A missing skip button
 * is a non-event; an error toast for an optional convenience is noise. Verified against the live
 * API: an unknown id answers 404, `id=0` answers 400, and an episode with no data answers
 * `found: false` with a 404 status inside a 200 body.
 */
class AniSkipApi(private val client: HttpClient) {

    /**
     * Skip intervals for one episode, or empty when there are none.
     *
     * [episodeLengthSeconds] is sent because AniSkip uses it to decide whether a submitted
     * interval plausibly belongs to this release; a wrong length returns nothing rather than
     * wrong times. Zero is allowed - the API treats it as "unknown".
     */
    suspend fun skipTimes(
        malId: Int,
        episodeNumber: Int,
        episodeLengthSeconds: Long,
    ): List<SkipInterval> {
        if (malId <= 0 || episodeNumber <= 0) return emptyList()

        val url = buildString {
            append(BASE)
            append("/v2/skip-times/")
            append(malId)
            append('/')
            append(episodeNumber)
            append("?types=op&types=ed&episodeLength=")
            append(episodeLengthSeconds.coerceAtLeast(0L))
        }

        val body = runCatching {
            val response: HttpResponse = client.get(url) {
                header("User-Agent", USER_AGENT)
            }
            // 404 is the ordinary "nothing known about this" answer, not a fault.
            if (!response.status.isSuccess()) return emptyList()
            response.body<String>()
        }.getOrNull() ?: return emptyList()

        val parsed = runCatching { json.decodeFromString<SkipResponse>(body) }.getOrNull()
            ?: return emptyList()

        if (!parsed.found) return emptyList()

        return parsed.results.mapNotNull { it.toInterval() }
    }

    private companion object {
        const val BASE = "https://api.aniskip.com"

        /** AniSkip asks for an identifying agent; the default Ktor one is impolite rather than blocked. */
        const val USER_AGENT = "WatchBox/1.0 (+https://github.com/Nicartjay/Watchbox-Android)"

        val json = Json { ignoreUnknownKeys = true }
    }
}

/** What kind of segment an interval covers. */
enum class SkipKind {
    /** Opening titles. */
    OPENING,

    /** Ending credits. */
    ENDING,
}

/**
 * One skippable stretch of an episode, in milliseconds.
 *
 * Milliseconds because the player works in them; AniSkip reports fractional seconds.
 */
data class SkipInterval(
    val kind: SkipKind,
    val startMs: Long,
    val endMs: Long,
) {
    /** True when [positionMs] falls inside this interval. */
    fun contains(positionMs: Long): Boolean = positionMs in startMs until endMs
}

@Serializable
private data class SkipResponse(
    val found: Boolean = false,
    val results: List<SkipResultDto> = emptyList(),
)

@Serializable
private data class SkipResultDto(
    val interval: SkipIntervalDto? = null,
    @SerialName("skipType") val skipType: String = "",
) {
    fun toInterval(): SkipInterval? {
        val start = interval?.startTime ?: return null
        val end = interval.endTime

        val kind = when (skipType.lowercase()) {
            "op", "mixed-op" -> SkipKind.OPENING
            "ed", "mixed-ed" -> SkipKind.ENDING
            // "recap" and anything new are ignored rather than guessed at: the button names a
            // specific thing, and mislabelling a recap as an opening is worse than omitting it.
            else -> return null
        }

        val startMs = (start * 1000).toLong()
        val endMs = (end * 1000).toLong()

        // A zero-length or reversed interval would draw a button that skips nowhere.
        if (endMs <= startMs) return null

        return SkipInterval(kind = kind, startMs = startMs, endMs = endMs)
    }
}

@Serializable
private data class SkipIntervalDto(
    val startTime: Double = 0.0,
    val endTime: Double = 0.0,
)
