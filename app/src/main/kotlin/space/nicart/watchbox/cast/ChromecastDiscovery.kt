package space.nicart.watchbox.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice as GmsCastDevice
import com.google.android.gms.cast.CastMediaControlIntent

/**
 * Discovers Chromecast receivers directly, instead of deferring to the system dialog.
 *
 * The app previously listed no Chromecasts at all on the reasoning that "the Cast
 * SDK owns the picker". It does own a picker - but the app never presented it, so
 * the only way to start a session was the notification shade or Google Home, and the
 * cast panel was empty for every user who had not already done that elsewhere.
 *
 * [MediaRouter] is the SDK's own discovery mechanism, so listing routes here does
 * not create a competing source of truth: session state still comes from
 * `SessionManager`, and both views read the same underlying router.
 *
 * ## Active scan
 *
 * Discovery only runs while a callback is registered with
 * [MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY]. Without that flag the router
 * returns whatever it happens to have cached - usually nothing on a cold start -
 * so the flag is what makes devices appear promptly rather than eventually.
 *
 * Everything here must run on the main thread: [MediaRouter] enforces it and throws
 * otherwise.
 */
class ChromecastDiscovery(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())

    private var router: MediaRouter? = null
    private var callback: MediaRouter.Callback? = null

    /** Routes by id, so a picker selection can be resolved back to a route. */
    private val routes = mutableMapOf<String, MediaRouter.RouteInfo>()

    /**
     * Starts an active scan, reporting the device list whenever it changes.
     *
     * Safe to call repeatedly; a second call replaces the previous callback rather
     * than stacking scans.
     */
    fun start(onDevicesChanged: (List<CastDevice>) -> Unit) {
        handler.post {
            runCatching {
                stopInternal()

                val mediaRouter = MediaRouter.getInstance(context.applicationContext)
                val selector = MediaRouteSelector.Builder()
                    // Filtered to the receiver this app actually loads media onto;
                    // an unfiltered selector also returns Bluetooth and local
                    // outputs, which cannot be cast to.
                    .addControlCategory(
                        CastMediaControlIntent.categoryForCast(
                            CastOptionsProvider.DEFAULT_MEDIA_RECEIVER_ID,
                        ),
                    )
                    .build()

                val routeCallback = object : MediaRouter.Callback() {
                    override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) =
                        publish(r, onDevicesChanged)

                    override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) =
                        publish(r, onDevicesChanged)

                    override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) =
                        publish(r, onDevicesChanged)
                }

                mediaRouter.addCallback(
                    selector,
                    routeCallback,
                    MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY,
                )

                router = mediaRouter
                callback = routeCallback

                // Emitted immediately as well: routes already known to the router
                // arrive through no callback at all.
                publish(mediaRouter, onDevicesChanged)
            }.onFailure {
                Log.w(TAG, "route discovery unavailable: ${it.javaClass.simpleName}")
                onDevicesChanged(emptyList())
            }
        }
    }

    fun stop() {
        handler.post { stopInternal() }
    }

    private fun stopInternal() {
        val current = callback ?: return
        runCatching { router?.removeCallback(current) }
        callback = null
    }

    private fun publish(
        mediaRouter: MediaRouter,
        onDevicesChanged: (List<CastDevice>) -> Unit,
    ) {
        val discovered = runCatching {
            mediaRouter.routes.filter { it.isCastRoute() }
        }.getOrDefault(emptyList())

        routes.clear()
        discovered.forEach { routes[it.id] = it }

        // Deduplicated by name, keeping the first of each.
        //
        // Ending a session leaves the router listing the same television more than once: the
        // ids differ - so a map keyed on `route.id` treats them as separate devices - while the
        // name is identical. Every stop added another copy, and the list grew without limit.
        //
        // Name is the right key because it is what the user is picking from: two rows reading
        // "Living Room TV" are the same choice however the router numbers them. `routes` still
        // holds every id, so whichever one is selected still resolves.
        val unique = discovered.distinctBy { it.name.trim().lowercase() }

        if (unique.size != discovered.size) {
            Log.i(TAG, "collapsed ${discovered.size} routes to ${unique.size} by name")
        }

        onDevicesChanged(
            unique.map { route ->
                CastDevice(
                    id = route.id,
                    name = route.name,
                    // Resolved from the route's extras when present. It is only
                    // needed to pick the network interface the proxy binds to, and
                    // the SDK reports it again once a session exists.
                    host = route.castDeviceHost(),
                    protocol = CastProtocol.CHROMECAST,
                )
            },
        )
    }

    /** Selects a route, which starts a Cast session through the SDK. */
    fun select(deviceId: String): Boolean {
        val route = routes[deviceId] ?: return false

        handler.post {
            runCatching { router?.selectRoute(route) }
                .onFailure { Log.w(TAG, "could not select route: ${it.message}") }
        }
        return true
    }

    /** Returns to the local output, ending any session. */
    fun unselect() {
        handler.post {
            runCatching {
                router?.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
            }
        }
    }

    /**
     * Excludes the default and Bluetooth routes.
     *
     * The router always reports a "Phone" default route, and including it would put
     * a row in the picker that casts to the device already playing.
     */
    private fun MediaRouter.RouteInfo.isCastRoute(): Boolean =
        !isDefault && !isBluetooth && isEnabled

    private fun MediaRouter.RouteInfo.castDeviceHost(): String? = runCatching {
        GmsCastDevice.getFromBundle(extras)?.inetAddress?.hostAddress
    }.getOrNull()

    private companion object {
        const val TAG = "ChromecastDiscovery"
    }
}
