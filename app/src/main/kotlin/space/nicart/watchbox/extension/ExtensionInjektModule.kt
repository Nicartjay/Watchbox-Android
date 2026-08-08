package space.nicart.watchbox.extension

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

/**
 * Registers the objects extension code looks up through Injekt.
 *
 * This is not optional plumbing. Extensions resolve these from their own
 * constructors and static initialisers, before the host can hand them anything:
 *
 *  * `AnimeHttpSource` reads `network: NetworkHelper by injectLazy()`.
 *  * `ConfigurableAnimeSource` resolves an [Application] for its preference file.
 *  * Many sources parse JSON APIs with `private val json: Json by injectLazy()`.
 *
 * A missing binding does not fail at load time. The extension installs, reports
 * its sources, and then dies on first use with
 * `InjektionException: No registered instance or factory for type ...` —
 * which surfaced as Cineby loading but returning nothing.
 *
 * [Json] is configured leniently on purpose: these are third-party scrapers
 * pointed at APIs that add fields without warning, and a strict parser turns any
 * upstream change into a hard failure. Matching Aniyomi's settings also means a
 * source that works there behaves the same here.
 */
class ExtensionInjektModule(private val app: Application) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingleton<Application>(app)
        addSingleton<Context>(app)
        addSingletonFactory { NetworkHelper(app) }

        addSingletonFactory {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }

        // A handful of sources use protobuf APIs. The opt-in is on the
        // ProtoBuf reference itself, not on anything we design.
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        addSingletonFactory<ProtoBuf> { ProtoBuf }
    }
}

/**
 * Installs the graph and returns the shared [NetworkHelper].
 *
 * The instance is returned so the host can reuse the same OkHttp client and
 * cookie jar the extensions use, rather than opening a second connection pool.
 */
fun installExtensionInjekt(app: Application): NetworkHelper {
    Injekt.importModule(ExtensionInjektModule(app))
    return Injekt.get()
}
