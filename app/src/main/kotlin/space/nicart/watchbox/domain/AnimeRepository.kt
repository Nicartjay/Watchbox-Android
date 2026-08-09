package space.nicart.watchbox.domain

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import space.nicart.watchbox.data.remote.TmdbApi
import space.nicart.watchbox.data.remote.TmdbSuggestion
import space.nicart.watchbox.data.remote.TmdbType
import space.nicart.watchbox.extension.ExtensionManager

/**
 * The single repository, backed entirely by installed extensions.
 *
 * Every call here runs third-party code from an extension APK, so each one is
 * wrapped: a source that throws, hangs, or returns nonsense must degrade to an
 * empty rail rather than take down the screen. That is also why the home feed
 * uses [withTimeout] — a source blocking on a dead host would otherwise leave
 * Home spinning forever.
 */
class AnimeRepository(
    private val extensions: ExtensionManager,
    private val tmdb: TmdbApi,
) {

    // ------------------------------------------------------------------ home

    /**
     * Builds the home feed from the installed sources.
     *
     * There is no cross-source trending feed in this ecosystem, so the hero is
     * drawn from the first source's popular list and each source contributes one
     * rail. Sources are queried concurrently; slow or failing ones are dropped.
     */
    suspend fun homeFeed(): Result<HomeFeed> = runCatching {
        val sources = extensions.catalogueSources()
        if (sources.isEmpty()) error(NO_SOURCES)

        sourceErrors.clear()

        val rows = coroutineScope {
            sources.take(MAX_ROWS)
                .map { source -> async { source.popularRow() } }
                .mapNotNull { it.await() }
        }

        if (rows.isEmpty()) {
            // Report what actually went wrong per source. Most failures here are
            // the source rejecting the request (WAF / TLS), not a bug in the app,
            // and a generic "nothing found" makes that impossible to tell apart.
            val detail = sourceErrors.entries
                .joinToString("\n") { (id, message) ->
                    val name = extensions.catalogueSourceById(id)?.name ?: "Source $id"
                    "$name: $message"
                }
                .ifBlank { "No source returned any titles." }
            error(detail)
        }

        // Only the hero is enriched: it is the one place a wide backdrop and a
        // title logo are actually shown, and enriching whole rails would mean a
        // TMDB request per poster.
        val hero = coroutineScope {
            rows.first().items.take(MAX_HERO)
                .map { card -> async { card.enriched() } }
                .awaitAll()
        }

        HomeFeed(hero = hero, rows = rows)
    }

    /**
     * Enriches one card on demand.
     *
     * Exposed for the TV home, where the backdrop and title logo follow D-pad focus:
     * any card can become the focused one, but enriching whole rails up front would
     * be hundreds of TMDB requests for artwork that is mostly never seen. Fetching as
     * focus arrives keeps the request count proportional to what the user looks at.
     *
     * Returns the card unchanged when nothing matches, so callers need no fallback.
     */
    suspend fun artworkFor(card: AnimeCard): AnimeCard = card.enriched()

    /** Overlays TMDB artwork on a card, keeping the source fields intact. */
    private suspend fun AnimeCard.enriched(): AnimeCard {
        val art = guarded("tmdb($title)") { tmdb.lookup(title) } ?: return this
        return copy(
            backdropUrl = art.backdropUrl,
            cardBackdropUrl = art.cardBackdropUrl,
            logoUrl = art.logoUrl,
            tmdbPosterUrl = art.posterUrl,
            tmdbId = art.tmdbId,
            year = art.year,
            genres = art.genres,
        )
    }

    private suspend fun AnimeCatalogueSource.popularRow(): AnimeRow? = guarded(
        what = "popular($name)",
        sourceId = id,
    ) {
        val page = withTimeout(SOURCE_TIMEOUT_MS) { getPopularAnime(1) }
        val items = page.animes.map { it.toCard(this) }
        if (items.isEmpty()) return@guarded null

        AnimeRow(
            sourceId = id,
            sourceName = name,
            title = "Popular on $name",
            items = items,
        )
    }

    /** Latest-updates rail for one source, used by the browse screen. */
    suspend fun latest(sourceId: Long, page: Int = 1): Result<List<AnimeCard>> = runCatching {
        val source = catalogueOrThrow(sourceId)
        if (!source.supportsLatest) return@runCatching emptyList()
        withContext(Dispatchers.IO) {
            source.getLatestUpdates(page).animes.map { it.toCard(source) }
        }
    }

    suspend fun popular(sourceId: Long, page: Int = 1): Result<List<AnimeCard>> = runCatching {
        val source = catalogueOrThrow(sourceId)
        withContext(Dispatchers.IO) {
            source.getPopularAnime(page).animes.map { it.toCard(source) }
        }
    }

    // ---------------------------------------------------------------- search

    /** Searches one source. */
    suspend fun search(
        sourceId: Long,
        query: String,
        page: Int = 1,
        filters: AnimeFilterList = AnimeFilterList(),
    ): Result<List<AnimeCard>> = runCatching {
        val source = catalogueOrThrow(sourceId)
        withContext(Dispatchers.IO) {
            source.getSearchAnime(page, query, filters)
                .animes
                .map { it.toCard(source) }
        }
    }

    /**
     * The filter list a source offers, or an empty list.
     *
     * Built fresh on every call: the ABI expects the host to mutate the returned
     * filters in place, so a cached list would leak one screen's selections into
     * the next. Guarded because `getFilterList` runs extension code that may throw.
     */
    fun filterList(sourceId: Long): AnimeFilterList =
        extensions.catalogueSourceById(sourceId)
            ?.let { source -> runCatching { source.getFilterList() }.getOrNull() }
            ?: AnimeFilterList()

    /**
     * Searches every installed source at once.
     *
     * Results are grouped per source rather than merged, because relevance is not
     * comparable across sources and a merged list would bury the good matches.
     */
    suspend fun searchAll(query: String): List<AnimeRow> {
        val sources = extensions.catalogueSources()
        if (sources.isEmpty() || query.isBlank()) return emptyList()

        return coroutineScope {
            sources
                .map { source ->
                    async {
                        guarded("search(${source.name})", source.id) {
                            val page = withTimeout(SOURCE_TIMEOUT_MS) {
                                source.getSearchAnime(1, query, AnimeFilterList())
                            }
                            val items = page.animes.map { it.toCard(source) }
                            if (items.isEmpty()) return@guarded null

                            AnimeRow(
                                sourceId = source.id,
                                sourceName = source.name,
                                title = source.name,
                                items = items,
                            )
                        }
                    }
                }
                .mapNotNull { it.await() }
        }
    }

    // ---------------------------------------------------------------- detail

    /**
     * Fetches details and the episode list together.
     *
     * Episodes come back newest-first from most sources; they are sorted
     * ascending here so "next episode" means what the user expects. Sources that
     * report `-1` for every number keep their original order.
     */
    suspend fun detail(sourceId: Long, url: String): Result<AnimeDetail> = runCatching {
        val source = catalogueOrThrow(sourceId)

        withContext(Dispatchers.IO) {
            val stub = SAnime.create().apply { this.url = url }

            val details = runCatching { source.getAnimeDetails(stub) }.getOrDefault(stub)
            val episodes = runCatching { source.getEpisodeList(stub) }
                .getOrDefault(emptyList())

            val resolvedTitle = details.title.ifBlank { "Untitled" }
            val ordered = episodes.sortedEpisodes()

            // One lookup per title, then one per season for stills.
            val art = guarded("tmdb($resolvedTitle)") {
                tmdb.lookup(resolvedTitle, preferMovie = ordered.size <= 1)
            }

            val stills = if (art != null && art.type == TmdbType.TV) {
                guarded("stills(${art.tmdbId})") {
                    tmdb.episodeStills(art.tmdbId, season = 1)
                }.orEmpty()
            } else {
                emptyMap()
            }

            val enrichedEpisodes = if (stills.isEmpty()) {
                ordered
            } else {
                ordered.map { episode ->
                    // Match on episode number; sources and TMDB agree on that far
                    // more reliably than on titles or ordering.
                    episode.withArt(stills[episode.number.toInt()])
                }
            }

            AnimeDetail(
                sourceId = source.id,
                sourceName = source.name,
                url = url,
                title = resolvedTitle,
                posterUrl = details.thumbnail_url?.takeIf { it.isNotBlank() }
                    ?: art?.posterUrl,
                backdropUrl = art?.backdropUrl,
                logoUrl = art?.logoUrl,
                tmdbId = art?.tmdbId,
                year = art?.year,
                rating = art?.rating ?: 0.0,
                description = details.description
                    ?.takeIf { it.isNotBlank() }
                    ?: art?.overview.orEmpty(),
                author = details.author?.takeIf { it.isNotBlank() },
                artist = details.artist?.takeIf { it.isNotBlank() },
                genres = details.getGenres()?.takeIf { it.isNotEmpty() }
                    ?: art?.genres.orEmpty(),
                status = AnimeStatus.from(details.status),
                episodes = enrichedEpisodes,
            )
        }
    }

    private fun List<SEpisode>.sortedEpisodes(): List<EpisodeEntry> {
        val mapped = map { it.toEntry() }
        val hasNumbers = mapped.any { it.number >= 0 }
        return if (hasNumbers) mapped.sortedBy { it.number } else mapped.reversed()
    }

    // ----------------------------------------------------------- suggestions

    /**
     * Related-anime suggestions for a title.
     *
     * Two tiers, in the order Anikku uses:
     *
     *  1. **The source's own related feed** (`fetchRelatedAnimeList`). Best
     *     quality, because the site itself decided what is related — but most
     *     extensions do not implement it and throw.
     *  2. **A keyword search on the same source.** Titles are split into
     *     meaningful words and the longest is searched, which finds sequels and
     *     spin-offs sharing a franchise name.
     *
     * Fetched separately from [detail] rather than inline: tier 2 is a second
     * network round-trip, and blocking the detail screen on it would delay the
     * episode list for a section the user may never scroll to.
     */
    suspend fun suggestions(
        sourceId: Long,
        animeUrl: String,
        title: String,
    ): List<AnimeCard> {
        val source = extensions.catalogueSourceById(sourceId) ?: return emptyList()
        val stub = SAnime.create().apply { url = animeUrl; this.title = title }

        val http = source as? AnimeHttpSource

        // Tier 1: the source's own related list.
        if (http?.disableRelatedAnimes != true) {
            val own = guarded("related(${source.name})") {
                withTimeout(SOURCE_TIMEOUT_MS) {
                    http?.fetchRelatedAnimeList(stub)
                }
            }.orEmpty()

            if (own.isNotEmpty()) return own.toCards(source, excluding = animeUrl)
        }

        // A source can veto searching entirely; without search neither remaining
        // tier can produce anything.
        if (http?.disableRelatedAnimesBySearch == true) return emptyList()

        // Tier 2: TMDB recommendations, resolved against this source.
        tmdbSuggestions(source, animeUrl, title).takeIf { it.isNotEmpty() }
            ?.let { return it }

        // Tier 3: longest-word keyword search. Weakest of the three, but it is
        // the only option for a title TMDB does not know.
        val keyword = title.toSearchKeyword() ?: return emptyList()

        val found = guarded("relatedSearch(${source.name})") {
            withTimeout(SOURCE_TIMEOUT_MS) {
                source.getSearchAnime(1, keyword, AnimeFilterList()).animes
            }
        }.orEmpty()

        return found.toCards(source, excluding = animeUrl)
    }

    /**
     * TMDB recommendations, filtered to what this source actually carries.
     *
     * TMDB returns titles, not extension entries: they have no source URL and so
     * cannot be played directly. Each is therefore searched on the source and
     * dropped when it is not found, which keeps every card in the rail tappable
     * at the cost of a shorter rail.
     *
     * Uses TMDB's own poster rather than the source's, since it is generally the
     * cleaner artwork, but the source URL is what makes the card playable.
     */
    private suspend fun tmdbSuggestions(
        source: AnimeCatalogueSource,
        animeUrl: String,
        title: String,
    ): List<AnimeCard> {
        val art = guarded("tmdbLookup($title)") { tmdb.lookup(title) } ?: return emptyList()

        val recommended = guarded("tmdbRecs(${art.tmdbId})") {
            tmdb.recommendations(art.tmdbId, art.type)
        }.orEmpty()

        if (recommended.isEmpty()) return emptyList()

        // Probing is capped rather than unbounded: each candidate is a search
        // request against a third-party site, and more than a rail's worth is
        // wasted work. A little headroom covers the ones that will not resolve.
        return coroutineScope {
            recommended.take(MAX_SUGGESTIONS * 2)
                .map { suggestion -> async { suggestion.resolveOn(source, animeUrl) } }
                .awaitAll()
                .filterNotNull()
                .distinctBy { it.url }
                .take(MAX_SUGGESTIONS)
        }
    }

    /** Finds this TMDB title on [source], or null when the source lacks it. */
    private suspend fun TmdbSuggestion.resolveOn(
        source: AnimeCatalogueSource,
        excluding: String,
    ): AnimeCard? {
        val hits = guarded("resolve($title)") {
            withTimeout(RESOLVE_TIMEOUT_MS) {
                source.getSearchAnime(1, title, AnimeFilterList()).animes
            }
        }.orEmpty()

        // Require a real title match, not merely the source's first result:
        // most sources return something for any query, so taking the top hit
        // blindly would fill the rail with unrelated entries.
        val match = hits.firstOrNull { it.title.matchesLoosely(title) }
            ?: return null

        if (match.url == excluding) return null

        return match.toCard(source).copy(
            tmdbPosterUrl = posterUrl,
            backdropUrl = backdropUrl,
            tmdbId = tmdbId,
            year = year,
        )
    }

    /**
     * Compares titles after normalising case, punctuation and spacing.
     *
     * Sources and TMDB disagree constantly on colons, hyphens and season
     * suffixes, so exact equality rejects almost every real match while a bare
     * `contains` accepts far too much. Containment on the normalised forms is the
     * usable middle ground.
     */
    private fun String.matchesLoosely(other: String): Boolean {
        val a = normaliseForMatch()
        val b = other.normaliseForMatch()
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    private fun String.normaliseForMatch(): String = TmdbApi.cleanTitle(this)
        .lowercase()
        .filter { it.isLetterOrDigit() || it == ' ' }
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun List<SAnime>.toCards(
        source: AnimeCatalogueSource,
        excluding: String,
    ): List<AnimeCard> = asSequence()
        // Drop the title being viewed; a source's own related feed often
        // includes it, and search almost always does.
        .filter { it.url != excluding }
        .filter { it.title.isNotBlank() }
        .distinctBy { it.url }
        .take(MAX_SUGGESTIONS)
        .map { it.toCard(source) }
        .toList()

    /**
     * Picks a search term from a title.
     *
     * The longest word is used because that is the most distinctive part of a
     * franchise name; short words and generic season markers match everything.
     */
    private fun String.toSearchKeyword(): String? = TmdbApi.cleanTitle(this)
        .split(' ', ':', '-', '–', '·')
        .map { it.trim() }
        .filter { it.length >= MIN_KEYWORD_LENGTH }
        .filterNot { it.lowercase() in GENERIC_WORDS }
        .maxByOrNull { it.length }

    // -------------------------------------------------------------- playback

    /**
     * Resolves streams for an episode.
     *
     * Highest resolution first, so the player's default pick is the best
     * available rather than whatever the source happened to list first.
     */
    suspend fun streams(
        sourceId: Long,
        episodeUrl: String,
    ): Result<List<StreamOption>> = runCatching {
        val source = catalogueOrThrow(sourceId)

        withContext(Dispatchers.IO) {
            val stub = SEpisode.create().apply { url = episodeUrl }
            source.getVideoList(stub)
                .map { it.toStreamOption() }
                .filter { it.url.isNotBlank() }
                .sortedByDescending { it.resolution }
                .ifEmpty { error("This source returned no playable streams.") }
        }
    }

    // --------------------------------------------------------------- helpers

    private fun catalogueOrThrow(sourceId: Long): AnimeCatalogueSource =
        extensions.catalogueSourceById(sourceId)
            ?: error("That source is no longer installed.")

    fun hasSources(): Boolean = extensions.catalogueSources().isNotEmpty()

    /**
     * Runs extension code, swallowing failures.
     *
     * Deliberately catches [Throwable]: extensions are linked at runtime, so a
     * mismatch surfaces as `NoSuchMethodError` or `AbstractMethodError` rather
     * than an `Exception`, and one bad source should not break the whole feed.
     */
    private inline fun <T> guarded(
        what: String,
        sourceId: Long? = null,
        block: () -> T?,
    ): T? = try {
        block()
    } catch (t: Throwable) {
        sourceId?.let { sourceErrors[it] = t.friendlyMessage() }
        // Logged, not silently dropped: these are the failures that matter most
        // when diagnosing a source, and they arrive as NoSuchMethodError or
        // AbstractMethodError rather than Exception because extensions are
        // linked at runtime.
        android.util.Log.w(TAG, "$what failed: ${t::class.java.simpleName}: ${t.message}", t)
        null
    }

    /** Last failure per source, so the UI can explain an empty feed. */
    val sourceErrors: MutableMap<Long, String> = java.util.concurrent.ConcurrentHashMap()

    /**
     * Turns a raw throwable into something a user can act on.
     *
     * Most source failures are the site refusing the request rather than a bug,
     * so the common network cases get plain wording; anything unexpected keeps
     * its class name because that is what makes an ABI mismatch identifiable.
     */
    private fun Throwable.friendlyMessage(): String = when {
        this is javax.net.ssl.SSLException ->
            "TLS handshake failed - the site rejected the connection"
        this is java.net.UnknownHostException -> "Host not found"
        this is java.net.SocketTimeoutException ||
            this is kotlinx.coroutines.TimeoutCancellationException -> "Timed out"
        this is NoSuchMethodError || this is AbstractMethodError ||
            this is NoClassDefFoundError ->
            "Incompatible extension (${this::class.java.simpleName})"
        else -> message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
    }

    private companion object {
        const val TAG = "AnimeRepository"
        const val NO_SOURCES = "No sources installed yet."
        const val SOURCE_TIMEOUT_MS = 15_000L
        const val MAX_SUGGESTIONS = 8

        /**
         * Shorter than the general source timeout: this runs once per candidate
         * and a slow site must not stall the whole rail.
         */
        const val RESOLVE_TIMEOUT_MS = 8_000L
        const val MIN_KEYWORD_LENGTH = 4

        /** Words too common to distinguish one title from another. */
        val GENERIC_WORDS = setOf(
            "the", "and", "movie", "season", "part", "final", "special",
            "series", "story", "anime", "film", "episode", "chapter",
        )
        const val MAX_ROWS = 12
        const val MAX_HERO = 6
    }
}
