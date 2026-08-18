package space.nicart.watchbox.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import space.nicart.watchbox.data.local.ARTWORK_LANGUAGE_DEFAULT
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
     * Preferred language for posters and logos.
     *
     * Held here rather than threaded through every call site: artwork is requested from
     * rails, heroes, detail pages and the cast layer, and passing a language through all of
     * them to reach two selection points would touch far more code than it informs.
     *
     * Written from settings at startup and whenever it changes. Volatile because the write
     * comes from a coroutine and the reads happen on whichever thread a lookup runs on.
     */
    @Volatile
    private var artworkLanguage: String = ARTWORK_LANGUAGE_DEFAULT

    /**
     * Sets the artwork language and drops anything already resolved.
     *
     * The cache has to go: entries hold the URLs chosen under the previous language, so
     * keeping them would leave every already-seen title on its old logo until the process
     * restarted - which reads as the setting not working.
     */
    fun setArtworkLanguage(code: String) {
        val next = code.trim().lowercase().ifBlank { ARTWORK_LANGUAGE_DEFAULT }
        if (next == artworkLanguage) return
        artworkLanguage = next
        cache.clear()
    }

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

    /**
     * Searches TMDB and picks the best candidate for [query].
     *
     * Prefers a title-verified hit over TMDB's own ranking. TMDB's first result is
     * usually right, but when it is wrong it is confidently wrong - searching a
     * romaji title with no TMDB entry returns whatever shares a word, and the
     * resulting artwork is attached to the wrong show for as long as it stays cached.
     *
     * The unverified first result is still used as a last resort, because rejecting
     * it outright would lose the many correct matches whose titles differ
     * legitimately (romaji vs English, punctuation, subtitles TMDB omits). So this
     * reorders rather than filters: verified first, TMDB's ranking after.
     */
    private suspend fun search(query: String, type: TmdbType): Int? = request(
        path = "search/${type.path}",
        params = mapOf("query" to query, "include_adult" to "true"),
    )?.let { body ->
        val results = runCatching { json.decodeFromString<SearchResponse>(body) }
            .getOrNull()
            ?.results
            ?.filter { it.id != 0 }
            ?: return@let null

        val verified = results.firstOrNull { Companion.titleMatches(query, it.displayTitle) }
        (verified ?: results.firstOrNull())?.id
    }

    private suspend fun details(id: Int, type: TmdbType): TmdbArtwork? {
        val body = request(
            path = "${type.path}/$id",
            params = mapOf(
                // external_ids rides along on the request we already make, so the IMDb id
                // costs nothing extra. Subtitle providers key on IMDb, not TMDB, and this
                // is the only place the two are ever linked.
                "append_to_response" to "images,external_ids",
                // The chosen language, then English, then the language-neutral set - which
                // is the clean text-free logos and the textless posters the portrait hero
                // uses. Requested together because the selection falls back through exactly
                // this order, and asking for one at a time would cost a request per step.
                "include_image_language" to imageLanguageParam(),
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
            // Full-height, because a portrait hero fills the screen: the card transform
            // would upscale visibly at that size.
            heroPosterUrl = image(dto.textlessPosterPath, HERO_BACKDROP_SIZE),
            logoUrl = image(dto.logoPathFor(artworkLanguage), LOGO_SIZE),
            overview = dto.overview.orEmpty(),
            year = dto.year,
            rating = dto.voteAverage,
            genres = dto.genres.orEmpty().map { it.name },
            seasonCount = dto.numberOfSeasons,
            studios = dto.productionCompanies.orEmpty()
                .filter { it.name.isNotBlank() }
                .map { company ->
                    TmdbStudio(
                        name = company.name,
                        logoUrl = image(company.logoPath, STUDIO_LOGO_SIZE),
                    )
                },
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

    /**
     * Videos, availability, reviews, ratings and ids for one title.
     *
     * One request, not six: `append_to_response` folds every sub-endpoint into the same
     * payload for the same rate-limit cost, and the whole thing is ~66KB.
     *
     * Fetched only for a detail page, and separately from [lookupById], because none of it
     * is needed for a card in a rail - bundling them would request reviews for every poster
     * on the home screen.
     *
     * [country] selects the provider list and the age rating. TMDB carries 129 countries and
     * they differ substantially, so showing another region's would be actively misleading
     * about what the user can watch.
     */
    suspend fun extras(id: Int, type: TmdbType, country: String): TmdbExtras {
        val key = "extras:${type.path}:$id:$country"
        cache[key]?.let { return it as? TmdbExtras ?: TmdbExtras() }

        val body = request(
            path = "${type.path}/$id",
            params = mapOf(
                "append_to_response" to
                    "videos,watch/providers,reviews,keywords,content_ratings," +
                    "release_dates,alternative_titles,external_ids",
            ),
        )

        val extras = body
            ?.let { runCatching { json.decodeFromString<ExtrasResponse>(it) }.getOrNull() }
            ?.toExtras(country)
            ?: TmdbExtras()

        cache[key] = extras
        return extras
    }

    private fun ExtrasResponse.toExtras(country: String): TmdbExtras {
        val entry = watchProviders?.results?.get(country)

        val providers = buildList {
            entry?.flatrate?.forEach { add(it.toProvider(ProviderKind.STREAM)) }
            entry?.free?.forEach { add(it.toProvider(ProviderKind.FREE)) }
            entry?.ads?.forEach { add(it.toProvider(ProviderKind.FREE)) }
            entry?.rent?.forEach { add(it.toProvider(ProviderKind.RENT)) }
            entry?.buy?.forEach { add(it.toProvider(ProviderKind.BUY)) }
        }
            // The same service appears under several kinds - Netflix as both flatrate and
            // ads - and one logo per service is the useful presentation. The first wins,
            // and the build order above puts the most favourable kind first.
            .distinctBy { it.name }

        return TmdbExtras(
            videos = videos?.results.orEmpty()
                .filter { it.site.equals("YouTube", ignoreCase = true) && it.key.isNotBlank() }
                // Official first, then trailers before teasers, then newest. A fan re-upload
                // at the top of the list looks like the app picked badly.
                .sortedWith(
                    compareByDescending<VideoEntry> { it.official }
                        .thenByDescending { it.type.equals("Trailer", ignoreCase = true) }
                        .thenByDescending { it.publishedAt },
                )
                .map {
                    TmdbVideo(
                        key = it.key,
                        name = it.name.ifBlank { it.type },
                        type = it.type,
                        official = it.official,
                        publishedAt = it.publishedAt,
                    )
                },
            providers = providers,
            providerCountry = country,
            providerLink = entry?.link,
            reviews = reviews?.results.orEmpty()
                .filter { it.content.isNotBlank() }
                .map {
                    TmdbReview(
                        author = it.author.ifBlank { "Anonymous" },
                        content = it.content,
                        rating = it.authorDetails?.rating?.toInt(),
                        avatarUrl = it.authorDetails?.avatarPath?.let { path ->
                            // TMDB stores some avatars as a Gravatar URL with a leading
                            // slash, which would otherwise be joined onto the image host.
                            if (path.startsWith("/http")) path.removePrefix("/")
                            else image(path, AVATAR_SIZE)
                        },
                        createdAt = it.createdAt,
                    )
                },
            // Movies publish certifications under release_dates, series under
            // content_ratings, so both are read and whichever exists wins.
            //
            // Falls back to US when the user's country publishes none, which is common:
            // of three titles sampled against PH, only one had a local rating while all
            // three had a US one. A US rating is recognisable enough to be useful, and an
            // empty field tells the viewer nothing.
            certification = certificationFor(country).ifBlank { certificationFor(FALLBACK_CERT_COUNTRY) },
            keywords = (keywords?.results ?: keywords?.keywords).orEmpty()
                .mapNotNull { it.name.takeIf { n -> n.isNotBlank() } },
            alternativeTitles = alternativeTitles?.results.orEmpty()
                .mapNotNull { it.title.takeIf { t -> t.isNotBlank() } }
                .distinct(),
            tvdbId = externalIds?.tvdbId,
            wikidataId = externalIds?.wikidataId?.takeIf { it.isNotBlank() },
            nextEpisodeAirDate = nextEpisodeToAir?.airDate.orEmpty(),
            nextEpisodeNumber = nextEpisodeToAir?.episodeNumber ?: 0,
        )
    }

    private fun ExtrasResponse.certificationFor(country: String): String =
        contentRatings?.results
            ?.firstOrNull { it.country.equals(country, ignoreCase = true) }
            ?.rating
            ?.takeIf { it.isNotBlank() }
            ?: releaseDates?.results
                ?.firstOrNull { it.country.equals(country, ignoreCase = true) }
                ?.releaseDates
                ?.firstNotNullOfOrNull { it.certification?.takeIf { c -> c.isNotBlank() } }
                .orEmpty()

    private fun ProviderEntry.toProvider(kind: ProviderKind) = TmdbProvider(
        name = providerName,
        logoUrl = image(logoPath, PROVIDER_LOGO_SIZE),
        kind = kind,
    )

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

    /**
     * The `include_image_language` value: the chosen language, English, and neutral.
     *
     * Deduplicated, so choosing English does not ask for it twice - TMDB accepts that but
     * it makes the request look wrong to anyone reading it.
     */
    private fun imageLanguageParam(): String =
        listOf(artworkLanguage, "en", "null").distinct().joinToString(",")

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

        /** Provider logos render as small square chips. */
        private const val PROVIDER_LOGO_SIZE = "w92"

        /** Where to look for an age rating when the user's own country publishes none. */
        private const val FALLBACK_CERT_COUNTRY = "US"

        /** Review avatars, shown at about 32dp. */
        private const val AVATAR_SIZE = "w45"

        /**
         * Studio logos are small on screen, so a small transform is enough.
         *
         * `w185` rather than `original`: these render at roughly 20dp tall in a row of
         * several, and the full-size asset would be a needless download per studio.
         */
        private const val STUDIO_LOGO_SIZE = "w185"
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
         * Collapses a title to letters and digits for comparison.
         *
         * Deleting separators rather than replacing them with spaces is deliberate:
         * it makes `Re:ZERO`, `Re Zero` and `RE-ZERO` identical, which is exactly the
         * class of difference that separates a source's title from TMDB's.
         *
         * Non-Latin titles collapse to an empty string. Callers must treat that as
         * "cannot verify" rather than "no match", or every Japanese-titled entry would
         * be rejected.
         */
        fun normaliseTitle(raw: String): String =
            raw.lowercase().replace(Regex("""[^a-z0-9]"""), "")

        /**
         * A normalised tail that marks a sequel rather than a different show.
         *
         * Anchored, so the whole remainder must be a season marker. This is what stops
         * a prefix match from claiming an unrelated title: searching "Monster" must not
         * accept "Monster Musume", but must still accept "Monster Season 2".
         *
         * Adapted from Zangetsu's `_seasonSuffix`, with Roman numerals extended past
         * VI and an `s2` short form added - both appear in extension titles that
         * Zangetsu never sees, because it does not read from Aniyomi sources.
         */
        private val SEASON_SUFFIX = Regex(
            """^(season\d*|\d+(st|nd|rd|th)?season|s\d{1,2}|\d{1,2}|""" +
                """ii|iii|iv|v|vi|vii|viii|ix|x|part\d*|cour\d*|final(season)?)$""",
        )

        /**
         * True when [candidate] is the title [wanted] asked for.
         *
         * Accepts an exact normalised match, or [wanted] plus a season marker - TMDB
         * indexes a sequel as "<base> Season 2" where a source often says just the
         * base name.
         *
         * Rejects a bare prefix otherwise. That asymmetry is the whole point: a
         * substring test would match "Monster" to "Monster Musume", and a wrong id is
         * never reconsidered once cached, so a false accept is worse than a miss.
         */
        fun titleMatches(wanted: String, candidate: String): Boolean {
            val want = normaliseTitle(wanted)
            val cand = normaliseTitle(candidate)
            if (want.isEmpty() || cand.isEmpty()) return false
            if (want == cand) return true
            if (!cand.startsWith(want)) return false
            return SEASON_SUFFIX.matches(cand.substring(want.length))
        }

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

/**
 * One studio credited on a title.
 *
 * [logoUrl] is null for studios TMDB has no logo for, which is common for smaller
 * ones - the name is always usable, the logo is not.
 */
data class TmdbStudio(
    val name: String,
    val logoUrl: String?,
)

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
    /**
     * A textless portrait poster, for a portrait hero. Null when TMDB has none.
     *
     * Separate from [posterUrl], which is the one-per-title default and usually carries a
     * title treatment - fine on a small card, wrong behind a logo the app draws itself.
     */
    val heroPosterUrl: String? = null,
    /** Credited studios, most significant first. Empty when TMDB listed none. */
    val studios: List<TmdbStudio> = emptyList(),
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
private data class SearchResult(
    val id: Int = 0,
    /** Movies use `title`, series use `name`; only one is ever populated. */
    val name: String? = null,
    val title: String? = null,
) {
    val displayTitle: String get() = title ?: name ?: ""
}

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
    /**
     * Studios, in TMDB's own order - which puts the animation studio first.
     *
     * `networks` is deliberately ignored. For an anime series it lists every regional
     * broadcaster that carried it: Jujutsu Kaisen reports 6 companies and 34 networks,
     * almost all of them local Japanese stations, which is noise rather than
     * information.
     */
    @SerialName("production_companies") val productionCompanies: List<Company>? = null,
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
     *
     * Raster only. TMDB serves some logos as SVG - The Mentalist's first three English
     * entries are `.svg` - and Coil has no SVG decoder registered, so those fail to
     * decode and the hero silently falls back to plain text. The failure is invisible:
     * the request succeeds with `content-type: image/svg+xml` and nothing reports an
     * error.
     *
     * Filtering rather than adding `coil-svg`: an SVG logo is a minority case (1 in 12
     * across a sample of popular titles) and every one of those titles also ships a
     * raster version, so the dependency would buy nothing this does not.
     */
    /**
     * A language-neutral poster, if TMDB has one.
     *
     * These are the textless prints - artwork with no title treatment burnt in - which is
     * what a portrait hero wants: the app draws the logo itself, so a poster carrying its
     * own title renders the name twice, often in a different typeface.
     *
     * Ordered by TMDB's own vote average, since the neutral set includes both official
     * key art and fan uploads and the votes separate them better than upload order does.
     *
     * Null when TMDB lists none, which is common for smaller titles - the caller then
     * falls back to the landscape backdrop.
     */
    val textlessPosterPath: String?
        get() = images?.posters.orEmpty()
            .filter { it.iso6391 == null }
            .filterNot { it.filePath.endsWith(".svg", ignoreCase = true) }
            .maxByOrNull { it.voteAverage }
            ?.filePath

    /**
     * A poster in [language], falling back through English to whatever exists.
     *
     * Used only when the hero is not the portrait one: [textlessPosterPath] is preferred
     * there, since the app draws the title logo itself and a poster carrying its own would
     * print the name twice.
     */
    fun posterPathFor(language: String): String? =
        images?.posters.orEmpty().pickByLanguage(language)

    /**
     * The title logo in [language], falling back through English to whatever exists.
     *
     * A logo is drawn per-market, so this is the setting's main effect: someone watching
     * Japanese releases can have the Japanese lettering with an English interface.
     */
    fun logoPathFor(language: String): String? =
        images?.logos.orEmpty().pickByLanguage(language)

    private fun List<ImageEntry>.pickByLanguage(language: String): String? =
        pickArtworkByLanguage(
            candidates = map { ArtworkCandidate(it.filePath, it.iso6391, it.voteAverage) },
            language = language,
        )

    val bestLogoPath: String?
        get() = logoPathFor("en")
}

@Serializable
private data class Genre(val name: String = "")

@Serializable
private data class Company(
    val id: Int = 0,
    val name: String = "",
    @SerialName("logo_path") val logoPath: String? = null,
)

@Serializable
private data class Images(
    val logos: List<ImageEntry>? = null,
    val posters: List<ImageEntry>? = null,
)

@Serializable
private data class ExternalIds(@SerialName("imdb_id") val imdbId: String? = null)

@Serializable
private data class ImageEntry(
    @SerialName("file_path") val filePath: String = "",
    @SerialName("iso_639_1") val iso6391: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
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

// ---------------------------------------------------------------- extras DTOs

@Serializable
private data class ExtrasResponse(
    val videos: VideoList? = null,
    @SerialName("watch/providers") val watchProviders: WatchProviders? = null,
    val reviews: ReviewList? = null,
    val keywords: KeywordList? = null,
    @SerialName("content_ratings") val contentRatings: ContentRatingList? = null,
    @SerialName("release_dates") val releaseDates: ReleaseDateList? = null,
    @SerialName("alternative_titles") val alternativeTitles: AltTitleList? = null,
    @SerialName("external_ids") val externalIds: ExtrasExternalIds? = null,
    @SerialName("next_episode_to_air") val nextEpisodeToAir: NextEpisode? = null,
)

@Serializable
private data class VideoList(val results: List<VideoEntry>? = null)

@Serializable
private data class VideoEntry(
    val key: String = "",
    val name: String = "",
    val site: String = "",
    val type: String = "",
    val official: Boolean = false,
    @SerialName("published_at") val publishedAt: String = "",
)

@Serializable
private data class WatchProviders(val results: Map<String, ProviderCountry>? = null)

@Serializable
private data class ProviderCountry(
    val link: String? = null,
    val flatrate: List<ProviderEntry>? = null,
    val free: List<ProviderEntry>? = null,
    val ads: List<ProviderEntry>? = null,
    val rent: List<ProviderEntry>? = null,
    val buy: List<ProviderEntry>? = null,
)

@Serializable
private data class ProviderEntry(
    @SerialName("provider_name") val providerName: String = "",
    @SerialName("logo_path") val logoPath: String? = null,
)

@Serializable
private data class ReviewList(val results: List<ReviewEntry>? = null)

@Serializable
private data class ReviewEntry(
    val author: String = "",
    val content: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("author_details") val authorDetails: AuthorDetails? = null,
)

@Serializable
private data class AuthorDetails(
    val rating: Double? = null,
    @SerialName("avatar_path") val avatarPath: String? = null,
)

/** TV returns `results`, movies return `keywords`; both shapes are accepted. */
@Serializable
private data class KeywordList(
    val results: List<KeywordEntry>? = null,
    val keywords: List<KeywordEntry>? = null,
)

@Serializable
private data class KeywordEntry(val name: String = "")

@Serializable
private data class ContentRatingList(val results: List<ContentRatingEntry>? = null)

@Serializable
private data class ContentRatingEntry(
    @SerialName("iso_3166_1") val country: String = "",
    val rating: String = "",
)

@Serializable
private data class ReleaseDateList(val results: List<ReleaseDateCountry>? = null)

@Serializable
private data class ReleaseDateCountry(
    @SerialName("iso_3166_1") val country: String = "",
    @SerialName("release_dates") val releaseDates: List<ReleaseDateEntry>? = null,
)

@Serializable
private data class ReleaseDateEntry(val certification: String? = null)

@Serializable
private data class AltTitleList(val results: List<AltTitleEntry>? = null)

@Serializable
private data class AltTitleEntry(val title: String = "")

@Serializable
private data class ExtrasExternalIds(
    @SerialName("tvdb_id") val tvdbId: Int? = null,
    @SerialName("wikidata_id") val wikidataId: String? = null,
)

@Serializable
private data class NextEpisode(
    @SerialName("air_date") val airDate: String = "",
    @SerialName("episode_number") val episodeNumber: Int = 0,
)

/**
 * One image TMDB offers, reduced to what the choice depends on.
 *
 * Mirrors the response DTO rather than exposing it, so the selection rule can be tested
 * without constructing a serialised payload.
 */
internal data class ArtworkCandidate(
    val filePath: String,
    val language: String?,
    val voteAverage: Double,
)

/**
 * Picks the highest-voted image in [language], then English, then language-neutral, then
 * anything at all.
 *
 * The fallback chain matters more than the preference. TMDB's coverage outside English is
 * patchy, so a strict match would leave most titles with no logo - and a missing logo is
 * worse than one in the wrong language, because the hero then falls back to plain text.
 *
 * English is tried before language-neutral because a neutral entry is usually the textless
 * cut: correct for a poster behind a logo, wrong for the logo itself, where it would show
 * artwork with no title on it.
 *
 * SVG is excluded throughout: Coil has no SVG decoder registered, so those fail to decode
 * and the caller silently gets nothing.
 *
 * Ordered by vote within each step, since the set mixes official key art with fan uploads
 * and the votes separate them better than upload order does.
 */
internal fun pickArtworkByLanguage(
    candidates: List<ArtworkCandidate>,
    language: String,
): String? {
    val usable = candidates.filterNot { it.filePath.endsWith(".svg", ignoreCase = true) }
    if (usable.isEmpty()) return null

    val wanted = language.trim().lowercase().ifBlank { ARTWORK_LANGUAGE_DEFAULT }

    fun bestOf(predicate: (ArtworkCandidate) -> Boolean) =
        usable.filter(predicate).maxByOrNull { it.voteAverage }

    return (
        bestOf { it.language.equals(wanted, ignoreCase = true) }
            ?: bestOf { it.language.equals(ARTWORK_LANGUAGE_DEFAULT, ignoreCase = true) }
            ?: bestOf { it.language == null }
            ?: usable.maxByOrNull { it.voteAverage }
        )?.filePath
}
