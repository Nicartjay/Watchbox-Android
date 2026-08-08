package space.nicart.watchbox

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import eu.kanade.tachiyomi.network.NetworkHelper
import io.ktor.client.HttpClient
import space.nicart.watchbox.core.network.HttpClientFactory
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.extension.ExtensionInstaller
import space.nicart.watchbox.extension.ExtensionManager
import space.nicart.watchbox.extension.ExtensionRepoApi
import space.nicart.watchbox.domain.AnimeRepository
import space.nicart.watchbox.extension.installExtensionInjekt

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
        container.extensionManager.init()
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

    private val installer = ExtensionInstaller(application, plainClient)

    val extensionManager = ExtensionManager(
        context = application,
        repoApi = repoApi,
        installer = installer,
    )

    val repository = AnimeRepository(extensionManager)
}
