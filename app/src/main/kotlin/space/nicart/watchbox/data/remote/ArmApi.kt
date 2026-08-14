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
 * Maps a TMDB id to a MyAnimeList id, via [ARM](https://arm.haglund.dev).
 *
 * ## Why a mapping service rather than a title search
 *
 * [AniSkipApi] needs a MAL id and nothing in this app has one. Two routes were compared against
 * the live services before choosing:
 *
 *  - **Title search on AniList** was rejected. It matches confidently even when wrong -
 *    "The Matrix" returns "Transformers the Movie" - and a wrong match attaches another show's
 *    timestamps, which is worse than no button.
 *  - **ARM** is an id-to-id table, so a non-anime title simply has no row. Game of Thrones and
 *    The Matrix both answer with an empty array, which is the safety property that matters:
 *    a film can never accidentally acquire skip times.
 *
 * Bundling the underlying dataset was also considered and rejected on size - the published
 * mapping file is around 6 MB, against a whole APK of roughly 10 MB.
 *
 * ## Seasons
 *
 * TMDB treats a long-running show as one entry; MAL splits it per season. So one TMDB id often
 * maps to several MAL ids, and the season number is what tells them apart - ARM returns
 * `themoviedb-season` for exactly this. Frieren, for instance, is one TMDB id with a MAL id for
 * season 1 and another for season 2.
 */
class ArmApi(private val client: HttpClient) {

    /**
     * Cached per TMDB id, for the process lifetime.
     *
     * The mapping is immutable in practice, and the player asks on every episode change. An
     * absent mapping is cached too - as an empty list - because "this is not anime" is the
     * common answer and re-asking on every episode of a long series is pointless traffic.
     */
    private val cache = ConcurrentHashMap<Int, List<ArmMapping>>()

    /**
     * The MAL id for [tmdbId], preferring the entry for [season].
     *
     * Returns null when there is no mapping, which is the normal answer for anything that is not
     * anime. Falls back to the single mapping when only one exists and the season does not
     * match: a source that reports no season, or reports one TMDB does not use, should still get
     * skip times for a show with exactly one entry.
     *
     * Deliberately does *not* fall back when several mappings exist and none matches the season -
     * picking arbitrarily among seasons would show season one's timestamps on season three.
     */
    suspend fun malId(tmdbId: Int, season: Int?): Int? {
        if (tmdbId <= 0) return null

        val mappings = mappings(tmdbId)
        if (mappings.isEmpty()) return null

        season?.let { wanted ->
            mappings.firstOrNull { it.season == wanted }?.let { return it.malId }
        }

        // A movie has no season at all, and a single-entry show cannot be ambiguous.
        return mappings.singleOrNull()?.malId
    }

    private suspend fun mappings(tmdbId: Int): List<ArmMapping> {
        cache[tmdbId]?.let { return it }

        val body = runCatching {
            val response: HttpResponse = client.get("$BASE/themoviedb?id=$tmdbId") {
                header("User-Agent", USER_AGENT)
            }
            // 400 is the answer for a malformed or out-of-range id; treated as "no mapping"
            // rather than retried, since it will not become valid.
            if (!response.status.isSuccess()) return@runCatching null
            response.body<String>()
        }.getOrNull()

        // Not cached on a network failure: unlike an empty result, this may succeed later, and
        // caching it would disable skip times for the rest of the session.
        if (body == null) return emptyList()

        val parsed = runCatching {
            json.decodeFromString<List<ArmEntry>>(body)
        }.getOrDefault(emptyList())

        val mappings = parsed.mapNotNull { entry ->
            entry.myanimelist?.takeIf { it > 0 }?.let {
                ArmMapping(malId = it, season = entry.season)
            }
        }

        cache[tmdbId] = mappings
        return mappings
    }

    private companion object {
        const val BASE = "https://arm.haglund.dev/api/v2"
        const val USER_AGENT = "WatchBox/1.0 (+https://github.com/Nicartjay/Watchbox-Android)"

        val json = Json { ignoreUnknownKeys = true }
    }
}

/** One TMDB-to-MAL row. [season] is null for films, which have no season. */
internal data class ArmMapping(
    val malId: Int,
    val season: Int?,
)

@Serializable
private data class ArmEntry(
    val myanimelist: Int? = null,
    @SerialName("themoviedb-season") val season: Int? = null,
)
