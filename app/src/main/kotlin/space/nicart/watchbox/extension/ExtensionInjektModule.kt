package space.nicart.watchbox.extension

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

/**
 * Registers the objects extension code looks up through Injekt.
 *
 * This is not optional plumbing. `AnimeHttpSource` reads
 * `network: NetworkHelper by injectLazy()`, and `ConfigurableAnimeSource`
 * resolves an [Application] to open its preference file — both from the
 * extension's own constructor, before the host can hand it anything.
 *
 * A missing binding here does not fail at load time. It surfaces later as an
 * Injekt lookup exception on the first network call from a source, which is easy
 * to misread as a broken extension, so the graph is installed once during
 * [Application.onCreate] and never lazily.
 */
class ExtensionInjektModule(private val app: Application) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingleton<Application>(app)
        addSingleton<Context>(app)
        addSingletonFactory { NetworkHelper(app) }
    }
}

/**
 * Installs the graph and returns the shared [NetworkHelper].
 *
 * The instance is returned so the host can reuse the same OkHttp client and
 * cookie jar the extensions use, instead of opening a second connection pool.
 */
fun installExtensionInjekt(app: Application): NetworkHelper {
    Injekt.importModule(ExtensionInjektModule(app))
    return Injekt.get()
}
