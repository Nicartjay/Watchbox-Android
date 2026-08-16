package space.nicart.watchbox

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import eu.kanade.tachiyomi.network.NetworkHelper
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import space.nicart.watchbox.core.network.HttpClientFactory
import space.nicart.watchbox.cast.CastManager
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.data.remote.TmdbApi
import space.nicart.watchbox.data.remote.CountryResolver
import space.nicart.watchbox.data.remote.UpdateChecker
import space.nicart.watchbox.data.remote.UpdateInstaller
import space.nicart.watchbox.extension.ExtensionInstaller
import space.nicart.watchbox.extension.ExtensionManager
import space.nicart.watchbox.extension.ExtensionRepoApi
import space.nicart.watchbox.data.remote.AniSkipApi
import space.nicart.watchbox.data.remote.ArmApi
import space.nicart.watchbox.data.remote.SubtitleApi
import space.nicart.watchbox.domain.AnimeRepository
import space.nicart.watchbox.domain.SkipRepository
import space.nicart.watchbox.domain.SubtitleRepository
import space.nicart.watchbox.extension.installExtensionInjekt
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Application + service locator.
 *
 * A hand-rolled container rather than Hilt/Koin: the graph is small, and this
 * keeps the build free of an annotation processor.
 *
 * Note the ordering constraint in [onCreate] — the Injekt graph the extension
 * runtime depends on must exist before any extension class is instantiated, so
 * it is installed first and eagerly.
 */
class WatchBoxApplication : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        // Extensions resolve NetworkHelper and Application through Injekt from
        // their own constructors, so this must run before any source is loaded.
        val networkHelper = installExtensionInjekt(this)

        container = AppContainer(this, networkHelper)

        // Checks for extension updates on every launch. The repository list is
        // supplied lazily because the store is read from disk, which must not
        // happen on the main thread during onCreate.
        container.extensionManager.init(
            repoProvider = { container.store.currentSettings().repos },
        )

        // Initialised eagerly so a Chromecast session started from the system UI
        // is already visible the first time the player opens.
        container.castManager.initialise()

        // The relay preference is restored off the main thread: the store reads from disk, and
        // the value is only needed once a cast is actually started.
        CoroutineScope(Dispatchers.IO).launch {
            container.castManager.setForceProxy(
                container.store.currentSettings().castForceProxy,
            )
        }

        // The artwork language is observed, not read once.
        //
        // Changing it has to take effect while Settings is still open - the alternative is a
        // preference that appears to do nothing until the app is restarted. TmdbApi drops its
        // cache on a change, so already-seen titles are re-resolved rather than keeping the
        // logos chosen under the previous language.
        CoroutineScope(Dispatchers.IO).launch {
            container.store.settings
                .map { it.artworkLanguage }
                .distinctUntilChanged()
                .collect { container.setArtworkLanguage(it) }
        }
    }

    /**
     * Shared Coil loader.
     *
     * Deliberately reuses the extensions' OkHttp client: source artwork usually
     * lives on the same hosts as the pages it was scraped from, and several
     * require the session cookies and User-Agent that client carries.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { container.networkHelper.client }
        .crossfade(true)
        .crossfade(220)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .respectCacheHeaders(false)
        .networkCachePolicy(CachePolicy.ENABLED)
        .build()
}

class AppContainer(
    application: Application,
    val networkHelper: NetworkHelper,
) {

    val store: WatchBoxStore = WatchBoxStore(application)

    private val plainClient: HttpClient = HttpClientFactory.createPlainClient()

    private val repoApi = ExtensionRepoApi(plainClient)

    /**
     * Artwork enrichment only. Extensions expose a portrait poster and nothing
     * else, so backdrops, title logos and episode stills come from here.
     */
    private val tmdbApi = TmdbApi(plainClient, BuildConfig.TMDB_API_KEY)

    private val installer = ExtensionInstaller(application, plainClient)

    val extensionManager = ExtensionManager(
        context = application,
        repoApi = repoApi,
        installer = installer,
    )

    val repository = AnimeRepository(extensionManager, tmdbApi)

    /**
     * Applies the artwork-language preference.
     *
     * Exposed on the container because [tmdbApi] is private to it: the language belongs to
     * the artwork layer, and nothing outside needs a handle on the client itself.
     */
    fun setArtworkLanguage(code: String) = tmdbApi.setArtworkLanguage(code)

    /** Online subtitle search, on the shared app-level client. */
    /**
     * Opening/ending timestamps, and the id mapping they need.
     *
     * Both reuse the plain client rather than the extensions' one: neither service is a content
     * source, so neither wants a source's cookies or Referer.
     */
    val skipRepository = SkipRepository(
        aniSkip = AniSkipApi(plainClient),
        arm = ArmApi(plainClient),
    )

    val subtitleRepository = SubtitleRepository(
        context = application,
        api = SubtitleApi(plainClient),
        store = store,
    )

    /**
     * Casting. One per process because it owns the local proxy socket and the
     * Cast SDK session, neither of which can be duplicated per screen.
     *
     * Reuses the extensions' OkHttp client so the proxy's upstream fetches carry
     * the same cookie jar and connection pool the player already uses.
     */
    val castManager = CastManager(application, networkHelper.client)

    /**
     * In-app updates from GitHub Releases.
     *
     * Compares release tags against BuildConfig.VERSION_NAME rather than
     * versionCode: CI derives the code from the run number, and the GitHub API
     * does not expose an asset's versionCode without downloading the APK.
     */
    /**
     * Resolves the viewer's country for TMDB availability.
     *
     * Shares the plain client: this is a keyless public endpoint, so it needs none of the
     * extension client's cookie jar or interceptors.
     */
    val countryResolver = CountryResolver(plainClient)

    val updateChecker = UpdateChecker(plainClient, BuildConfig.VERSION_NAME)

    val updateInstaller = UpdateInstaller(application, plainClient)
}
