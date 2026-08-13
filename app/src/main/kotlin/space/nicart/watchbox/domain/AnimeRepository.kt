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
import kotlin.random.Random
import kotlinx.coroutines.withTimeout
import space.nicart.watchbox.data.remote.TmdbApi
import space.nicart.watchbox.data.remote.TmdbArtwork
import space.nicart.watchbox.data.remote.TmdbEpisodeArt
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
                .map { source -> async { source.homeRow() } }
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

        // The spotlight is drawn at random from every source, not from the head of one row.
        //
        // Taking the first row's opening titles meant the same handful every launch - a
        // catalogue's ordering barely moves - and no other source was ever represented.
        //
        // Deduplicated by key before sampling: the same title routinely appears in several
        // catalogues, and a carousel repeating it looks broken. Shuffled then taken, so the
        // pick is uniform across the whole pool rather than favouring whichever source
        // happened to return first.
        //
        // Seeded per calendar day rather than left to chance. The feed reloads whenever the
        // installed extension set changes, and an unseeded shuffle would silently swap the
        // cards under the carousel while the user was looking at it - the pager is keyed on
        // item count, so a same-length reshuffle does not reset the position, it just changes
        // what each page shows. A daily seed keeps the selection stable within a session and
        // still gives a different spotlight tomorrow.
        val hero = coroutineScope {
            val pool = rows.asSequence()
                .flatMap { it.items.asSequence() }
                .distinctBy { it.key }
                .toList()

            pool.shuffled(Random(heroSeed()))
                .take(MAX_HERO)
                // Only the hero is enriched: it is the one place a wide backdrop and a title
                // logo are actually shown, and enriching whole rails would mean a TMDB request
                // per poster.
                .map { card -> async { card.enriched() } }
                .awaitAll()
        }

        HomeFeed(hero = hero, rows = rows)
    }

    /**
     * Shuffle seed for the spotlight: stable for a calendar day.
     *
     * Derived from the local date so the carousel does not reorder itself mid-session, and so
     * two launches on the same day agree. `currentTimeMillis` is deliberate rather than a
     * monotonic clock - the intent is "today", which is a wall-clock notion.
     */
    private fun heroSeed(): Long =
        System.currentTimeMillis() / MILLIS_PER_DAY

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
        val art = guarded("tmdb($title)") { tmdbArtwork(url, title) } ?: return this
        return copy(
            backdropUrl = art.backdropUrl,
            heroBackdropUrl = art.heroBackdropUrl,
            cardBackdropUrl = art.cardBackdropUrl,
            logoUrl = art.logoUrl,
            tmdbPosterUrl = art.posterUrl,
            tmdbId = art.tmdbId,
            year = art.year,
            genres = art.genres,
        )
    }

    /**
     * Resolves TMDB artwork for one entry, by id when the source gives one.
     *
     * TMDB front-end extensions encode the id in the entry URL, which is an exact
     * answer; a title search only guesses, and picks the wrong entry whenever a
     * name is shared. Sources that use their own URL scheme parse to null here and
     * keep the title search unchanged, so this is additive rather than a
     * replacement.
     *
     * [preferMovie] only applies to the title path: a URL that carries an id also
     * states the type, which is strictly better than inferring it.
     */
    private suspend fun tmdbArtwork(
        url: String,
        title: String,
        preferMovie: Boolean = false,
    ): TmdbArtwork? {
        TmdbApi.parseTmdbRef(url)?.let { (id, type) ->
            // Falls through to the title search when the id resolves to nothing:
            // an id can be stale, and no artwork at all is worse than a guess.
            tmdb.lookupById(id, type)?.let { return it }
        }
        return tmdb.lookup(title, preferMovie = preferMovie)
    }

    /**
     * The home rail for one source: its latest updates, falling back to popular.
     *
     * Latest is preferred because the home screen is somewhere people return to for what has
     * appeared since last time, and a popularity chart barely moves between visits.
     *
     * The fallback is not optional. `supportsLatest` is part of the extension ABI and plenty of
     * sources report false, so a straight switch would drop those sources off the home screen
     * entirely - and if none of the installed sources supported it, [homeFeed] would treat the
     * empty result as a failure and show its error state.
     *
     * A source that claims support can still return nothing, so an empty page falls back too:
     * the claim is the extension's word, not a guarantee.
     *
     * The title says which list is being shown. Silently labelling a popularity chart "Latest"
     * would be worse than either row on its own.
     */
    private suspend fun AnimeCatalogueSource.homeRow(): AnimeRow? {
        val claimsLatest = runCatching { supportsLatest }.getOrDefault(false)

        if (claimsLatest) {
            val latest = guarded(what = "latest($name)", sourceId = id) {
                val page = withTimeout(SOURCE_TIMEOUT_MS) { getLatestUpdates(1) }
                val items = page.animes.map { it.toCard(this) }
                if (items.isEmpty()) return@guarded null

                AnimeRow(
                    sourceId = id,
                    sourceName = name,
                    title = "Latest on $name",
                    items = items,
                    isLatest = true,
                )
            }

            if (latest != null) {
                android.util.Log.i(TAG, "home row for $name: latest")
                return latest
            }
        }

        // Logged so which list a source ended up on is answerable without a screenshot. A
        // successful row is otherwise silent, and "latest was requested" and "latest was
        // actually shown" are different claims - the fallback is invisible from the outside.
        android.util.Log.i(
            TAG,
            "home row for $name: popular" +
                if (claimsLatest) " (latest returned nothing)" else " (no latest support)",
        )
        return popularRow()
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

            // One lookup per title, then one per season for stills. Resolved by the
            // URL's TMDB id when the source supplies one, so the detail page and the
            // card it was opened from cannot disagree about which entry this is.
            val art = guarded("tmdb($resolvedTitle)") {
                tmdbArtwork(url, resolvedTitle, preferMovie = ordered.size <= 1)
            }

            // Fetched per season, keyed by season. Season 1's episodes were previously
            // used for every season, so in a multi-season show S2E1 and S3E1 both showed
            // season 1 episode 1's still, title, overview and runtime.
            val seasons = ordered.mapNotNull { it.season }.distinct().ifEmpty { listOf(1) }

            val stillsBySeason: Map<Int, Map<Int, TmdbEpisodeArt>> =
                if (art != null && art.type == TmdbType.TV) {
                    seasons.take(MAX_SEASON_LOOKUPS).associateWith { season ->
                        guarded("stills(${art.tmdbId} s$season)") {
                            tmdb.episodeStills(art.tmdbId, season = season)
                        }.orEmpty()
                    }
                } else {
                    emptyMap()
                }

            val enrichedEpisodes = if (stillsBySeason.isEmpty()) {
                ordered
            } else {
                ordered.map { episode ->
                    // Match on season and episode number; sources and TMDB agree on
                    // those far more reliably than on titles or ordering.
                    val forSeason = stillsBySeason[episode.season ?: 1].orEmpty()
                    episode.withArt(forSeason[episode.number.toInt()])
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
                imdbId = art?.imdbId,
                year = art?.year,
                rating = art?.rating ?: 0.0,
                description = details.description?.takeIf { it.hasSummary() }
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
        // Season first, then number. Sorting on the number alone interleaved the
        // seasons of a multi-season show - S3E1, S2E1, S1E1, S3E2 - because every
        // season restarts at 1.
        return if (hasNumbers) {
            mapped.sortedWith(compareBy({ it.season ?: 0 }, { it.number }))
        } else {
            mapped.reversed()
        }
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
        val art = guarded("tmdbLookup($title)") { tmdbArtwork(animeUrl, title) }
            ?: return emptyList()

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
         * Caps the per-season still lookups for one title.
         *
         * A long-running show would otherwise issue a request per season on every
         * detail open. Later seasons simply keep the source's own titles.
         */
        const val MAX_SEASON_LOOKUPS = 12

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

        /** Length of the spotlight's shuffle window. */
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}

/**
 * Whether a source description contains prose, not just scraped metadata.
 *
 * Several sources build a description out of markdown metadata blocks - AniDB emits
 * `**Type:** TV | **Rating:** 7.4`, then `**Alternative Titles:**` and `**Links:**` -
 * and append the synopsis only if the page had one. A plain `isNotBlank` check
 * therefore reports a description for an entry that has no summary at all, which both
 * suppresses the TMDB overview fallback and puts raw markdown on the detail page.
 *
 * A paragraph counts as prose when it does not open with a markdown marker. Cheaper
 * and more robust than enumerating each source's labels, which differ per source and
 * change with any extension update.
 */
internal fun String.hasSummary(): Boolean = split("\n\n", "\\n\\n").any { paragraph ->
    val text = paragraph.trimStart()
    text.isNotBlank() && !text.startsWith("*") && !text.startsWith("#")
}
