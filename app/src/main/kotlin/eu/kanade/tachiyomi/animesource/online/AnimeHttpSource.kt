package eu.kanade.tachiyomi.animesource.online

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.security.MessageDigest

/**
 * Base class virtually every HTTP-backed extension extends.
 *
 * Subclasses implement the `*Request` / `*Parse` pairs; this class owns the
 * request/response plumbing between them. All the member names, visibilities
 * and signatures here are fixed by the ABI — extensions were compiled against
 * them and are linked at runtime, so a rename surfaces as `NoSuchMethodError`.
 *
 * [network] is resolved through Injekt because extensions construct themselves
 * before the host can inject anything, so the host must register a
 * [NetworkHelper] singleton during startup.
 *
 * See the note in [eu.kanade.tachiyomi.animesource.model.SAnime] for why this
 * package reproduces the Aniyomi ABI instead of defining its own.
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
abstract class AnimeHttpSource : AnimeCatalogueSource {

    protected val network: NetworkHelper by injectLazy()

    abstract val baseUrl: String

    open val versionId: Int = 1

    /**
     * Derived from name/lang/versionId so that the same source keeps its
     * identity across installs. The algorithm is part of the ABI: it decides
     * which stored preferences and library rows a source owns.
     */
    override val id: Long by lazy { generateId(name, lang, versionId) }

    val headers: Headers by lazy { headersBuilder().build() }

    open val client: OkHttpClient
        get() = network.client

    protected open fun headersBuilder(): Headers.Builder = Headers.Builder().apply {
        add("User-Agent", network.defaultUserAgentProvider())
    }

    /** First 8 bytes of MD5("name/lang/versionId"), big-endian, sign bit cleared. */
    protected fun generateId(name: String, lang: String, versionId: Int): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }
            .reduce(Long::or) and Long.MAX_VALUE
    }

    // ------------------------------------------------------------- browse

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(popularAnimeRequest(page)).awaitSuccess()
        return response.use { popularAnimeParse(it) }
    }

    protected abstract fun popularAnimeRequest(page: Int): Request

    protected abstract fun popularAnimeParse(response: Response): AnimesPage

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(latestUpdatesRequest(page)).awaitSuccess()
        return response.use { latestUpdatesParse(it) }
    }

    protected abstract fun latestUpdatesRequest(page: Int): Request

    protected abstract fun latestUpdatesParse(response: Response): AnimesPage

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val response = client.newCall(searchAnimeRequest(page, query, filters)).awaitSuccess()
        return response.use { searchAnimeParse(it) }
    }

    protected abstract fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request

    protected abstract fun searchAnimeParse(response: Response): AnimesPage

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    // ------------------------------------------------------------- details

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(animeDetailsRequest(anime)).awaitSuccess()
        return response.use { animeDetailsParse(it) }
    }

    open fun animeDetailsRequest(anime: SAnime): Request =
        GET(baseUrl + anime.url, headers)

    protected abstract fun animeDetailsParse(response: Response): SAnime

    // ------------------------------------------------------------ episodes

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(episodeListRequest(anime)).awaitSuccess()
        return response.use { episodeListParse(it) }
    }

    protected open fun episodeListRequest(anime: SAnime): Request =
        GET(baseUrl + anime.url, headers)

    protected abstract fun episodeListParse(response: Response): List<SEpisode>

    // -------------------------------------------------------------- videos

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(videoListRequest(episode)).awaitSuccess()
        return response.use { videoListParse(it) }
    }

    protected open fun videoListRequest(episode: SEpisode): Request =
        GET(baseUrl + episode.url, headers)

    protected abstract fun videoListParse(response: Response): List<Video>

    /**
     * Resolve a lazily-populated [Video] into a playable one.
     * Sources that return ready URLs leave this alone.
     */
    open suspend fun resolveVideo(video: Video): Video = video

    open fun videoUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not implemented")

    @Deprecated("Implement getVideoList instead.")
    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> =
        Observable.error(UnsupportedOperationException("Not implemented"))

    /**
     * Ordering hook. The host applies user quality preferences afterwards, so
     * the default keeps the source's own order.
     */
    protected open fun List<Video>.sort(): List<Video> = this

    // -------------------------------------------------------- related anime

    /**
     * Related-anime suggestions.
     *
     * These members are part of the ABI and were already being overridden by
     * real extensions (AnimePahe overrides both `relatedAnimeListRequest` and
     * `relatedAnimeListParse`, Cineby the request, AniDB the flag) while this
     * class did not declare them — so those overrides were dead code and the
     * suggestions never appeared. Declaring them here activates the source's own
     * implementation.
     *
     * Signatures are fixed: `protected` visibility and these exact parameter and
     * return types, otherwise an override links as a separate method and the
     * default below runs instead.
     */
    open val supportsRelatedAnimes: Boolean get() = false

    /** Set by a source that has no usable related feed at all. */
    open val disableRelatedAnimes: Boolean get() = false

    /**
     * Set by a source whose own search makes a poor fallback, e.g. AniDB, where
     * searching the title returns the same entry rather than similar ones.
     */
    open val disableRelatedAnimesBySearch: Boolean get() = false

    /**
     * Fetches the source's own related list.
     *
     * Throws when the source does not implement it; the host treats that as
     * "no extension-provided suggestions" and falls back to a keyword search.
     */
    open suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        val response = client.newCall(relatedAnimeListRequest(anime)).awaitSuccess()
        return response.use { relatedAnimeListParse(it) }
    }

    protected open fun relatedAnimeListRequest(anime: SAnime): Request =
        GET(baseUrl + anime.url, headers)

    protected open fun relatedAnimeListParse(response: Response): List<SAnime> =
        throw UnsupportedOperationException("Not implemented")

    // -------------------------------------------------------------- helpers

    /** Stores [url] on [anime] with [baseUrl] stripped, so a domain change survives. */
    fun setUrlWithoutDomain(anime: SAnime, url: String) {
        anime.url = getUrlWithoutDomain(url)
    }

    fun setUrlWithoutDomain(episode: SEpisode, url: String) {
        episode.url = getUrlWithoutDomain(url)
    }

    protected fun getUrlWithoutDomain(orig: String): String = try {
        val uri = java.net.URI(orig.replace(" ", "%20"))
        buildString {
            append(uri.rawPath)
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    } catch (_: Exception) {
        orig
    }

    open fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    open fun getEpisodeUrl(episode: SEpisode): String = baseUrl + episode.url

    /** Called before a newly-discovered episode is stored. */
    open fun prepareNewEpisode(episode: SEpisode, anime: SAnime) = Unit

    override fun toString(): String = "$name (${lang.uppercase()})"
}
