package space.nicart.watchbox.download

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadManager
import java.io.File
import java.util.concurrent.Executors
import androidx.media3.datasource.DataSource

/**
 * The download engine, one per process.
 *
 * Built on Media3's [DownloadManager] rather than a hand-rolled byte copy because the
 * sources this app talks to serve HLS and DASH as often as plain files - real captured
 * labels include `1080p · HLS` and `2160p · DASH` beside `2160p · BluRay · MKV`. A manifest
 * is not a file that can be fetched: it is a playlist of hundreds of segments, each needing
 * the extension's headers, and `HlsDownloader`/`DashDownloader` already walk that graph.
 * Writing that by hand would mean reimplementing segment enumeration, encryption key
 * fetching and variant selection, all of which ship in a dependency already on the
 * classpath.
 *
 * [SimpleCache] with a no-op evictor is the store. Eviction is deliberately disabled: an
 * evicting cache is right for a streaming buffer, where the oldest bytes are the least
 * wanted, and exactly wrong for a download, where losing an arbitrary segment silently
 * breaks a file the user believes they have.
 */
@UnstableApi
class DownloadEngine(
    private val context: Context,
    private val storage: DownloadStorage,
    /**
     * Supplies a live URL and headers for a download that has none, or whose credential has
     * expired. Keyed by the download's own id.
     */
    private val resolver: DownloadStreamResolver,
) {

    private val databaseProvider by lazy { StandaloneDatabaseProvider(context) }

    /**
     * Where segments and files land.
     *
     * Rooted at the chosen volume so the whole cache moves with the preference. Media3 owns
     * this directory outright and prunes anything absent from its index, so nothing else
     * writes here - subtitles go in a sibling directory.
     */
    private val cacheDir: File
        get() = File(storage.resolveRoot(volumeId), CACHE_DIR).apply { mkdirs() }

    /** Set before the manager is first touched, from the stored preference. */
    var volumeId: String? = null
        private set

    private var cache: SimpleCache? = null
    private var manager: DownloadManager? = null

    /**
     * The manager, created on first use.
     *
     * Lazy rather than eager because constructing it opens a database and scans the cache
     * index, which is work the great majority of launches never need - most sessions never
     * touch a download at all.
     */
    fun manager(): DownloadManager = manager ?: build().also { manager = it }

    /** Applies the stored volume preference. Must be called before [manager]. */
    fun useVolume(id: String?) {
        if (id == volumeId) return
        // A volume change invalidates the cache and index, both of which are rooted in the
        // old directory. Releasing rather than migrating: moving gigabytes between volumes
        // silently is worse than leaving them where they are, and the reconciler reports
        // what is on the other volume rather than losing it.
        release()
        volumeId = id
    }

    private fun build(): DownloadManager {
        val simpleCache = SimpleCache(cacheDir, NoOpCacheEvictor(), databaseProvider)
        cache = simpleCache

        // The extension's headers are applied per request through the resolver, and a
        // credential failure re-resolves rather than aborting.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(DEFAULT_USER_AGENT)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)

        val upstream = ReResolvingDataSource.Factory(httpFactory) {
            resolver.currentStream()
        }

        // The downloader writes through the cache rather than to a file of its own: that is
        // what lets a segmented format be stored as the many parts it arrives in and still
        // be read back as one stream at playback.
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(upstream)

        val downloaderFactory = DefaultDownloaderFactory(
            cacheDataSourceFactory,
            Executors.newFixedThreadPool(DOWNLOAD_THREADS),
        )

        return DownloadManager(
            context,
            DefaultDownloadIndex(databaseProvider),
            downloaderFactory,
        ).apply {
            // One at a time. Two large downloads sharing a connection each take twice as
            // long to become usable, and on a television sharing bandwidth with playback
            // the difference is watchable versus not.
            maxParallelDownloads = 1
        }
    }

    /** Frees the cache and index handles. Called on a volume change. */
    fun release() {
        manager?.release()
        manager = null
        runCatching { cache?.release() }
        cache = null
    }

    /**
     * A data source factory that reads a downloaded stream from disk before the network.
     *
     * This is how a download is played rather than a `file://` path, and it has to be: a
     * segmented download is hundreds of files plus an index, not one playable file, so there
     * is no path to hand the player. Reading through the same cache the downloader wrote to
     * lets HLS, DASH and progressive all be played back identically, and a partially
     * downloaded stream plays the part it has and fetches the rest.
     *
     * [upstream] is the network factory to fall back to, supplied by the caller so playback
     * keeps its own header handling rather than inheriting the downloader's.
     */
    fun cacheAwareFactory(upstream: DataSource.Factory): DataSource.Factory {
        // Touches manager() so the cache is opened and its index read. Without that the
        // cache reports itself empty and every downloaded stream would be re-fetched.
        manager()
        val simpleCache = cache ?: return upstream

        return CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(upstream)
            // Read-only. Playback must not add to the download cache: the evictor is a no-op,
            // so anything written here would be kept forever and counted as a download the
            // user never asked for.
            .setCacheWriteDataSinkFactory(null)
            // Matches what the downloader wrote under. A DataSpec carries the key when the
            // caller set one, and falls back to the URL otherwise - which for a signed,
            // expiring URL means never matching anything.
            .setCacheKeyFactory { spec -> spec.key ?: spec.uri.toString() }
    }

    /** Whether [key] has a complete download in the cache. */
    fun isDownloaded(key: String): Boolean {
        val index = runCatching { manager().downloadIndex.getDownload(key) }.getOrNull()
        return index?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
    }

    private companion object {
        const val CACHE_DIR = "media"

        /**
         * Matches the player's own agent.
         *
         * A source that gates on it would otherwise serve playback and refuse the download,
         * which reads as the download being broken rather than as a header mismatch.
         */
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000

        /**
         * Threads within a single download, not concurrent downloads.
         *
         * A segmented format fetches many small files, and doing that one at a time leaves
         * the connection idle between requests.
         */
        const val DOWNLOAD_THREADS = 4
    }
}
