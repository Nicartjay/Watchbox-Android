package space.nicart.watchbox.domain

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
class AnimeRepository(private val extensions: ExtensionManager) {

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

        HomeFeed(
            hero = rows.first().items.take(MAX_HERO),
            rows = rows,
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
    ): Result<List<AnimeCard>> = runCatching {
        val source = catalogueOrThrow(sourceId)
        withContext(Dispatchers.IO) {
            source.getSearchAnime(page, query, AnimeFilterList())
                .animes
                .map { it.toCard(source) }
        }
    }

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

            AnimeDetail(
                sourceId = source.id,
                sourceName = source.name,
                url = url,
                title = details.title.ifBlank { "Untitled" },
                posterUrl = details.thumbnail_url?.takeIf { it.isNotBlank() },
                description = details.description.orEmpty(),
                author = details.author?.takeIf { it.isNotBlank() },
                artist = details.artist?.takeIf { it.isNotBlank() },
                genres = details.getGenres().orEmpty(),
                status = AnimeStatus.from(details.status),
                episodes = episodes.sortedEpisodes(),
            )
        }
    }

    private fun List<SEpisode>.sortedEpisodes(): List<EpisodeEntry> {
        val mapped = map { it.toEntry() }
        val hasNumbers = mapped.any { it.number >= 0 }
        return if (hasNumbers) mapped.sortedBy { it.number } else mapped.reversed()
    }

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
        const val MAX_ROWS = 12
        const val MAX_HERO = 6
    }
}
