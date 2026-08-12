package space.nicart.watchbox.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * TMDB artwork lookup.
 *
 * Extensions only expose a portrait poster URL, which is not enough for the
 * Nuvio-style layout: the hero needs a wide backdrop and a transparent title
 * logo, and episode cards need stills. TMDB supplies all three, keyed off the
 * title string the extension already gave us.
 *
 * This is enrichment only — never a content source. If a lookup fails the UI
 * falls back to the extension's own poster, so a TMDB outage degrades the
 * artwork rather than breaking playback.
 *
 * Responses are parsed explicitly rather than through ContentNegotiation, for the
 * same reason as the extension repo index: it keeps the parser lenient about
 * fields TMDB adds over time.
 */
class TmdbApi(private val client: HttpClient, private val apiKey: String) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** Memoises hits and misses; cleared only with the process. */
    private val cache = ConcurrentHashMap<String, Any>()

    /**
     * Exact lookup by TMDB id, for sources whose URLs already carry one.
     *
     * Some extensions are TMDB front-ends and encode the id in the entry URL
     * (`/movie/1719380`, `/tv/94997`). Searching by title throws that away and
     * guesses, which is how a card ends up with another title's artwork: several
     * unrelated entries share a name, and the search endpoint returns them in
     * relevance order that has nothing to do with which one the source meant.
     * When the id is known, the answer is not a guess.
     *
     * Keyed separately from the title cache: the two can disagree, and the id is
     * the trustworthy one.
     */
    suspend fun lookupById(tmdbId: Int, type: TmdbType): TmdbArtwork? {
        if (apiKey.isBlank() || tmdbId <= 0) return null

        val key = "id:${type.path}:$tmdbId"
        cache[key]?.let { return it as? TmdbArtwork }

        val artwork = details(tmdbId, type)
        cache[key] = artwork ?: MISS
        return artwork
    }

    /**
     * Best-effort match for [title].
     *
     * Anime titles are messy: extensions append season markers, "(Dub)",
     * bracketed tags and release years, and many sources use the romaji name
     * where TMDB uses the English one. So this tries the cleaned title first,
     * then the raw title, and searches TV before movies because the large
     * majority of extension content is episodic.
     *
     * A guess by nature. Prefer [lookupById] when the source supplies an id.
     */
    suspend fun lookup(title: String, preferMovie: Boolean = false): TmdbArtwork? {
        if (apiKey.isBlank() || title.isBlank()) return null

        val key = "lookup:${title.lowercase()}:$preferMovie"
        cache[key]?.let { return it as? TmdbArtwork }

        val cleaned = cleanTitle(title)
        val queries = listOf(cleaned, title).distinct().filter { it.isNotBlank() }
        val types = if (preferMovie) {
            listOf(TmdbType.MOVIE, TmdbType.TV)
        } else {
            listOf(TmdbType.TV, TmdbType.MOVIE)
        }

        for (type in types) {
            for (query in queries) {
                val hit = search(query, type) ?: continue
                val artwork = details(hit, type)
                if (artwork != null) {
                    cache[key] = artwork
                    return artwork
                }
            }
        }

        cache[key] = MISS
        return null
    }

    private suspend fun search(query: String, type: TmdbType): Int? = request(
        path = "search/${type.path}",
        params = mapOf("query" to query, "include_adult" to "true"),
    )?.let { body ->
        runCatching { json.decodeFromString<SearchResponse>(body) }
            .getOrNull()
            ?.results
            ?.firstOrNull()
            ?.id
    }

    private suspend fun details(id: Int, type: TmdbType): TmdbArtwork? {
        val body = request(
            path = "${type.path}/$id",
            params = mapOf(
                // external_ids rides along on the request we already make, so the IMDb id
                // costs nothing extra. Subtitle providers key on IMDb, not TMDB, and this
                // is the only place the two are ever linked.
                "append_to_response" to "images,external_ids",
                // Language-neutral logos are usually the clean, text-only ones.
                "include_image_language" to "en,ja,null",
            ),
        ) ?: return null

        val dto = runCatching { json.decodeFromString<DetailsResponse>(body) }.getOrNull()
            ?: return null

        return TmdbArtwork(
            tmdbId = id,
            type = type,
            title = dto.displayTitle,
            imdbId = dto.resolvedImdbId,
            backdropUrl = image(dto.backdropPath, BACKDROP_SIZE),
            // Full resolution, for the TV home's full-screen hero. w1280 is narrower
            // than the panel it fills there, so it visibly upscales.
            heroBackdropUrl = image(dto.backdropPath, HERO_BACKDROP_SIZE),
            // Same image, smaller transform: used by landscape cards, where the
            // hero-sized asset would be wasted bandwidth.
            cardBackdropUrl = image(dto.backdropPath, CARD_BACKDROP_SIZE),
            posterUrl = image(dto.posterPath, POSTER_SIZE),
            logoUrl = image(dto.bestLogoPath, LOGO_SIZE),
            overview = dto.overview.orEmpty(),
            year = dto.year,
            rating = dto.voteAverage,
            genres = dto.genres.orEmpty().map { it.name },
            seasonCount = dto.numberOfSeasons,
        )
    }

    /**
     * Episode stills and titles for one season.
     *
     * Returned keyed by episode number so the caller can merge them onto the
     * extension's own episode list without assuming the two orders agree.
     */
    suspend fun episodeStills(tmdbId: Int, season: Int): Map<Int, TmdbEpisodeArt> {
        val key = "season:$tmdbId:$season"

        @Suppress("UNCHECKED_CAST")
        cache[key]?.let { return if (it === MISS) emptyMap() else it as Map<Int, TmdbEpisodeArt> }

        val body = request("tv/$tmdbId/season/$season", emptyMap())
        val episodes = body
            ?.let { runCatching { json.decodeFromString<SeasonResponse>(it) }.getOrNull() }
            ?.episodes
            .orEmpty()

        if (episodes.isEmpty()) {
            cache[key] = MISS
            return emptyMap()
        }

        val art = episodes.associate { episode ->
            episode.episodeNumber to TmdbEpisodeArt(
                number = episode.episodeNumber,
                name = episode.name.orEmpty(),
                overview = episode.overview.orEmpty(),
                stillUrl = image(episode.stillPath, STILL_SIZE),
                airDate = episode.airDate,
                rating = episode.voteAverage,
                runtimeMinutes = episode.runtime,
            )
        }
        cache[key] = art
        return art
    }

    /**
     * Titles TMDB considers related.
     *
     * Uses `/recommendations` rather than `/similar`: the two sound
     * interchangeable but are not. For Frieren, recommendations returned
     * "To Your Eternity" and "The Ancient Magus' Bride" while similar returned
     * unrelated entries, so similar is not worth offering as a fallback.
     *
     * Returned as plain titles plus artwork. They carry no source URL, so the
     * caller must resolve each against an installed extension before any of them
     * can be played.
     */
    suspend fun recommendations(tmdbId: Int, type: TmdbType): List<TmdbSuggestion> {
        val key = "recs:${type.path}:$tmdbId"

        @Suppress("UNCHECKED_CAST")
        cache[key]?.let {
            return if (it === MISS) emptyList() else it as List<TmdbSuggestion>
        }

        val body = request("${type.path}/$tmdbId/recommendations", emptyMap())
        val results = body
            ?.let { runCatching { json.decodeFromString<RecommendationResponse>(it) }.getOrNull() }
            ?.results
            .orEmpty()
            .mapNotNull { entry ->
                val name = entry.displayTitle.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TmdbSuggestion(
                    tmdbId = entry.id,
                    title = name,
                    posterUrl = image(entry.posterPath, POSTER_SIZE),
                    backdropUrl = image(entry.backdropPath, BACKDROP_SIZE),
                    year = entry.year,
                    isMovie = entry.mediaType == "movie" || type == TmdbType.MOVIE,
                )
            }

        cache[key] = results.ifEmpty { MISS }
        return results
    }

    private suspend fun request(path: String, params: Map<String, String>): String? {
        val query = (params + ("api_key" to apiKey) + ("language" to "en-US"))
            .entries
            .joinToString("&") { (k, v) -> "$k=${v.urlEncoded()}" }

        return runCatching {
            val response = client.get("$BASE/$path?$query")
            if (response.status.isSuccess()) response.bodyAsText() else null
        }.onFailure {
            // Enrichment is optional, so a failure degrades artwork rather than
            // breaking the screen; still worth a breadcrumb.
            android.util.Log.w(TAG, "GET $path failed: ${it::class.java.simpleName}")
        }.getOrNull()
    }

    private fun image(path: String?, size: String): String? =
        path?.takeIf { it.isNotBlank() }?.let { "$IMAGE_BASE/$size$it" }

    companion object {
        private const val TAG = "WbTmdb"
        private const val BASE = "https://api.themoviedb.org/3"
        private const val IMAGE_BASE = "https://image.tmdb.org/t/p"

        private const val BACKDROP_SIZE = "w1280"

        /**
         * Backdrop size for a full-bleed hero.
         *
         * TMDB's backdrop transforms stop at w1280, which is *below* a 1080p panel and
         * far below a 4K one - so the TV home's full-screen hero was upscaling every
         * backdrop and the softness is obvious at that size. `original` is the only
         * option above w1280.
         *
         * Deliberately a separate field rather than raising [BACKDROP_SIZE]: the phone
         * hero and the detail pages draw the same image at a fraction of the size, and
         * an original-resolution asset is several times the bytes for no visible gain
         * there. Building the URL costs nothing until something actually loads it.
         */
        private const val HERO_BACKDROP_SIZE = "original"

        /**
         * Backdrop size for landscape cards.
         *
         * The full-bleed hero backdrop is w1280, but a card is a fraction of the
         * screen: serving w1280 per card would download roughly ten times the pixels
         * actually drawn, for a whole row at once.
         */
        private const val CARD_BACKDROP_SIZE = "w780"
        private const val POSTER_SIZE = "w500"
        private const val LOGO_SIZE = "w500"
        private const val STILL_SIZE = "w300"

        /** Marks a negative result so a miss is not retried on every scroll. */
        private val MISS = Any()

        /**
         * Strips the decorations extensions add to titles.
         *
         * Order matters: bracketed tags go first so a "[Dub]" suffix cannot
         * survive as stray punctuation, and the trailing-year strip runs last
         * because a year is only noise once the rest is gone.
         */
        fun cleanTitle(raw: String): String = raw
            .replace(Regex("""[\[(]\s*(dub|sub|subbed|dubbed|uncensored|raw)\s*[\])]""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\[[^]]*]"""), " ")
            .replace(Regex("""\bseason\s*\d+\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\bs\d{1,2}\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\bpart\s*\d+\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\b(2nd|3rd|\d+th)\s+season\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\bfinal\s+season\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""第\s*\d+\s*季"""), " ")
            .replace(Regex("""\(\s*(19|20)\d{2}\s*\)"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trim('-', ':', '·', '–')
            .trim()

        /**
         * Reads a TMDB id out of a source entry URL, or null.
         *
         * TMDB front-end extensions build their URLs straight from the id, so the
         * exact answer is already in hand and a title search is pure loss. The
         * `/movie/` or `/tv/` segment also states the media type, which otherwise
         * has to be inferred from the episode count.
         *
         * Deliberately strict. This runs against every source, and a loose match
         * would read an unrelated path number as a TMDB id and confidently attach
         * the wrong artwork - worse than the fuzzy title search it replaces,
         * because a wrong id is never reconsidered. So the segment must be the
         * whole path, optionally followed by a query or fragment: `/movie/123`
         * matches, `/anime/movie/123` and `/movie/123/season/2` do not.
         */
        fun parseTmdbRef(url: String): Pair<Int, TmdbType>? {
            val match = TMDB_URL.find(url.trim()) ?: return null
            val type = when (match.groupValues[1].lowercase()) {
                "movie" -> TmdbType.MOVIE
                "tv" -> TmdbType.TV
                else -> return null
            }
            // toIntOrNull rejects anything that would overflow, rather than
            // truncating it into a valid-looking id for an unrelated entry.
            val id = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return id to type
        }

        /** `/movie/123`, `/tv/456`, with an optional leading host and trailing query. */
        private val TMDB_URL = Regex(
            """^(?:https?://[^/]+)?/(movie|tv)/(\d+)/?(?:[?#].*)?$""",
            RegexOption.IGNORE_CASE,
        )
    }
}

enum class TmdbType(val path: String) {
    MOVIE("movie"),
    TV("tv"),
}

/** Artwork and metadata merged onto an extension entry. */
data class TmdbArtwork(
    val tmdbId: Int,
    val type: TmdbType,
    val title: String,
    /**
     * IMDb id, when TMDB has one. Null is common for obscure titles.
     *
     * Carried because subtitle providers index by IMDb rather than TMDB, and this lookup is
     * the only point where the app sees both.
     */
    val imdbId: String? = null,
    val backdropUrl: String?,
    /** The same backdrop at full resolution, for a full-bleed hero. */
    val heroBackdropUrl: String?,
    /** The same backdrop at card size. */
    val cardBackdropUrl: String?,
    val posterUrl: String?,
    val logoUrl: String?,
    val overview: String,
    val year: String?,
    val rating: Double,
    val genres: List<String>,
    val seasonCount: Int,
)

/**
 * A TMDB-recommended title.
 *
 * Deliberately not an [space.nicart.watchbox.domain.AnimeCard]: it has no source
 * URL yet and is therefore not playable until matched to an extension entry.
 */
data class TmdbSuggestion(
    val tmdbId: Int,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val year: String?,
    val isMovie: Boolean,
)

data class TmdbEpisodeArt(
    val number: Int,
    val name: String,
    val overview: String,
    val stillUrl: String?,
    val airDate: String?,
    val rating: Double,
    val runtimeMinutes: Int?,
)

// ---------------------------------------------------------------- wire format

@Serializable
private data class SearchResponse(val results: List<SearchResult>? = null)

@Serializable
private data class SearchResult(val id: Int = 0)

@Serializable
private data class DetailsResponse(
    val id: Int = 0,
    val name: String? = null,
    val title: String? = null,
    val overview: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("number_of_seasons") val numberOfSeasons: Int = 0,
    val genres: List<Genre>? = null,
    val images: Images? = null,
    /** Present on movies at the top level. TV series carry it under external_ids only. */
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("external_ids") val externalIds: ExternalIds? = null,
) {
    val displayTitle: String get() = name ?: title ?: ""

    /**
     * The IMDb id, from wherever this response happens to carry it.
     *
     * Movies expose `imdb_id` at the top level; TV series only inside `external_ids`. Blanks
     * are treated as absent because TMDB returns an empty string rather than null for titles
     * it has no IMDb match for.
     */
    val resolvedImdbId: String?
        get() = (imdbId ?: externalIds?.imdbId)?.takeIf { it.isNotBlank() }

    val year: String? get() = (firstAirDate ?: releaseDate)
        ?.take(4)
        ?.takeIf { it.length == 4 }

    /**
     * Prefers an English logo, then a language-neutral one, then anything.
     * Language-neutral entries are usually the clean text-free marks, which is
     * what the hero wants.
     */
    val bestLogoPath: String?
        get() = images?.logos.orEmpty().let { logos ->
            logos.firstOrNull { it.iso6391 == "en" }
                ?: logos.firstOrNull { it.iso6391 == null }
                ?: logos.firstOrNull()
        }?.filePath
}

@Serializable
private data class Genre(val name: String = "")

@Serializable
private data class Images(val logos: List<ImageEntry>? = null)

@Serializable
private data class ExternalIds(@SerialName("imdb_id") val imdbId: String? = null)

@Serializable
private data class ImageEntry(
    @SerialName("file_path") val filePath: String = "",
    @SerialName("iso_639_1") val iso6391: String? = null,
)

@Serializable
private data class RecommendationResponse(val results: List<RecommendationEntry>? = null)

@Serializable
private data class RecommendationEntry(
    val id: Int = 0,
    val name: String? = null,
    val title: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
) {
    val displayTitle: String get() = name ?: title ?: ""

    val year: String? get() = (firstAirDate ?: releaseDate)
        ?.take(4)
        ?.takeIf { it.length == 4 }
}

@Serializable
private data class SeasonResponse(val episodes: List<SeasonEpisode>? = null)

@Serializable
private data class SeasonEpisode(
    @SerialName("episode_number") val episodeNumber: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val runtime: Int? = null,
)

private fun String.urlEncoded(): String =
    java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
