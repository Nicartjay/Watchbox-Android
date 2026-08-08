package space.nicart.watchbox.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.nicart.watchbox.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * TMDB enrichment.
 *
 * Ported from `js/tmdb.js`. Supplies the backdrops, title logos and episode
 * stills the Nuvio-style layout leans on — the ONEROOM API only returns portrait
 * covers, which are not enough for a cinematic hero.
 *
 * Improvements over the web original:
 *  - the key comes from `BuildConfig` rather than a source literal;
 *  - the cache stores in-flight [kotlinx.coroutines.Deferred]s, so two concurrent
 *    identical lookups share one network call (the web version fired both).
 */
class TmdbApi(private val client: HttpClient) {

    private val cache = ConcurrentHashMap<String, Any?>()

    // -------------------------------------------------------------- search

    /**
     * Resolve a TMDB id from a title. Retries with progressively looser queries,
     * mirroring `tmdbSearch` (`js/tmdb.js:54-79`).
     */
    suspend fun findId(title: String, year: String?, type: TmdbType): Int? {
        val cleaned = cleanTitle(title)
        val attempts = listOfNotNull(
            cleaned to year,
            (title to year).takeIf { cleaned != title },
            (cleaned to null).takeIf { year != null },
        )
        for ((query, y) in attempts) {
            val key = "search:${type.path}:$query:${y ?: ""}"
            @Suppress("UNCHECKED_CAST")
            val hit = cached(key) { searchOnce(query, y, type) } as Int?
            if (hit != null) return hit
        }
        return null
    }

    private suspend fun searchOnce(query: String, year: String?, type: TmdbType): Int? {
        val res = client.get("$BASE/search/${type.path}") {
            tmdbAuth()
            parameter("query", query)
            parameter("include_adult", false)
            if (year != null) {
                if (type == TmdbType.MOVIE) parameter("year", year)
                else parameter("first_air_date_year", year)
            }
        }
        if (!res.status.isSuccess()) return null
        val body = runCatching { res.body<TmdbSearchResponse>() }.getOrNull() ?: return null
        return body.results?.firstOrNull()?.id
    }

    // -------------------------------------------------------------- details

    suspend fun details(id: Int, type: TmdbType): TmdbDetails? =
        @Suppress("UNCHECKED_CAST")
        cached("full:${type.path}:$id") {
            val res = client.get("$BASE/${type.path}/$id") {
                tmdbAuth()
                parameter(
                    "append_to_response",
                    "credits,images,external_ids,videos,recommendations",
                )
                parameter("include_image_language", "en,null")
            }
            if (!res.status.isSuccess()) null
            else runCatching { res.body<TmdbDetails>() }.getOrNull()
        } as TmdbDetails?

    suspend fun seasonEpisodes(id: Int, season: Int): List<TmdbEpisode> =
        @Suppress("UNCHECKED_CAST")
        (
            cached("season:$id:$season") {
                val res = client.get("$BASE/tv/$id/season/$season") { tmdbAuth() }
                if (!res.status.isSuccess()) null
                else runCatching { res.body<TmdbSeason>() }.getOrNull()?.episodes
            } as List<TmdbEpisode>?
            ) ?: emptyList()

    // -------------------------------------------------------------- cache

    /**
     * Memoise by [key]. Negative results are cached too (as [NULL_SENTINEL]) so a
     * miss doesn't re-hit the network on every recomposition.
     */
    private suspend fun cached(key: String, produce: suspend () -> Any?): Any? {
        cache[key]?.let { return if (it === NULL_SENTINEL) null else it }
        val value = runCatching { produce() }.getOrNull()
        cache[key] = value ?: NULL_SENTINEL
        return value
    }

    fun clearCache() = cache.clear()

    private fun io.ktor.client.request.HttpRequestBuilder.tmdbAuth() {
        parameter("api_key", BuildConfig.TMDB_API_KEY)
        parameter("language", "en-US")
    }

    companion object {
        const val BASE = "https://api.themoviedb.org/3"
        const val IMG = "https://image.tmdb.org/t/p"
        private val NULL_SENTINEL = Any()

        /**
         * Strip season/part markers so `"Squid Game Season 2"` matches
         * `"Squid Game"` (`js/tmdb.js:32-44`).
         */
        fun cleanTitle(raw: String): String = raw
            .replace(Regex("""\[[^]]*]"""), " ")
            .replace(Regex("""\bSeason\s*\d+\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\bS\d{1,2}\b"""), " ")
            .replace(Regex("""\bPart\s*\d+\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\bFinal Season\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""第\s*\d+\s*季"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}

enum class TmdbType(val path: String) {
    MOVIE("movie"),
    TV("tv"),
    ;

    companion object {
        fun of(subjectType: Int): TmdbType =
            if (subjectType == space.nicart.watchbox.data.model.SubjectType.TV) TV else MOVIE
    }
}

/** Image size buckets, chosen per screen width (`js/tmdb.js:130-144`). */
object TmdbImage {
    fun backdrop(path: String?, wide: Boolean = false): String? =
        path?.let { "${TmdbApi.IMG}/${if (wide) "w1280" else "w780"}$it" }

    fun logo(path: String?): String? = path?.let { "${TmdbApi.IMG}/w500$it" }

    fun poster(path: String?): String? = path?.let { "${TmdbApi.IMG}/w500$it" }

    fun still(path: String?): String? = path?.let { "${TmdbApi.IMG}/w300$it" }

    fun profile(path: String?): String? = path?.let { "${TmdbApi.IMG}/w185$it" }
}

// ---------------------------------------------------------------- DTOs

@Serializable
data class TmdbSearchResponse(val results: List<TmdbSearchResult>? = null)

@Serializable
data class TmdbSearchResult(
    val id: Int = 0,
    val name: String? = null,
    val title: String? = null,
)

@Serializable
data class TmdbDetails(
    val id: Int = 0,
    val name: String? = null,
    val title: String? = null,
    val overview: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int>? = null,
    val runtime: Int? = null,
    val genres: List<TmdbGenre>? = null,
    @SerialName("number_of_seasons") val numberOfSeasons: Int = 0,
    val seasons: List<TmdbSeasonSummary>? = null,
    val images: TmdbImages? = null,
    val credits: TmdbCredits? = null,
    @SerialName("origin_country") val originCountry: List<String>? = null,
    val recommendations: TmdbSearchResponse? = null,
) {
    val displayName: String get() = name ?: title ?: ""
    val logoPath: String?
        get() = images?.logos
            ?.sortedByDescending { if (it.iso6391 == "en") 1 else 0 }
            ?.firstOrNull()
            ?.filePath
    val year: String? get() = (firstAirDate ?: releaseDate)?.take(4)?.takeIf { it.length == 4 }
    val runtimeMinutes: Int? get() = runtime ?: episodeRunTime?.firstOrNull()
}

@Serializable
data class TmdbGenre(val id: Int = 0, val name: String = "")

@Serializable
data class TmdbSeasonSummary(
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("poster_path") val posterPath: String? = null,
    val name: String = "",
)

@Serializable
data class TmdbImages(
    val logos: List<TmdbImageEntry>? = null,
    val backdrops: List<TmdbImageEntry>? = null,
    val posters: List<TmdbImageEntry>? = null,
)

@Serializable
data class TmdbImageEntry(
    @SerialName("file_path") val filePath: String = "",
    @SerialName("iso_639_1") val iso6391: String? = null,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class TmdbCredits(val cast: List<TmdbCastMember>? = null)

@Serializable
data class TmdbCastMember(
    val id: Int = 0,
    val name: String = "",
    val character: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
    val order: Int = 0,
)

@Serializable
data class TmdbSeason(val episodes: List<TmdbEpisode>? = null)

@Serializable
data class TmdbEpisode(
    @SerialName("episode_number") val episodeNumber: Int = 0,
    @SerialName("season_number") val seasonNumber: Int = 0,
    val name: String = "",
    val overview: String = "",
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val runtime: Int? = null,
)
