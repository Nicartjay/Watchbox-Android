package space.nicart.watchbox

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import io.ktor.client.HttpClient
import space.nicart.watchbox.core.network.HttpClientFactory
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.data.remote.OneRoomApi
import space.nicart.watchbox.data.remote.TmdbApi
import space.nicart.watchbox.data.remote.WatchBoxApi
import space.nicart.watchbox.data.source.NativeSourceResolver
import space.nicart.watchbox.domain.MediaRepository

/**
 * Application + service locator.
 *
 * A hand-rolled container rather than Hilt/Koin: the graph is small (one store,
 * three API clients, one repository) and this keeps the build free of an
 * annotation processor and of the Compose-Multiplatform artifacts Koin drags in.
 */
class WatchBoxApplication : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /**
     * Shared Coil loader. Generous caches because poster rails and hero
     * backdrops are revisited constantly.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
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

class AppContainer(application: Application) {

    val store: WatchBoxStore = WatchBoxStore(application)

    private val oneRoomClient: HttpClient = HttpClientFactory.createOneRoomClient(store)
    private val plainClient: HttpClient = HttpClientFactory.createPlainClient()

    private val workerBase: suspend () -> String = { store.currentSettings().workerBaseUrl }

    private val oneRoomApi = OneRoomApi(oneRoomClient)
    private val tmdbApi = TmdbApi(plainClient)
    private val watchBoxApi = WatchBoxApi(plainClient, workerBase)
    private val resolver = NativeSourceResolver(plainClient, workerBase)

    val repository = MediaRepository(
        oneRoom = oneRoomApi,
        tmdb = tmdbApi,
        watchBox = watchBoxApi,
        resolver = resolver,
        store = store,
        // Dev-only providers are WAF-blocked from datacentre egress, so they are
        // only worth attempting from a debug build on a residential connection.
        allowDevOnlyServers = BuildConfig.DEBUG,
    )
}
