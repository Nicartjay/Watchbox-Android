package space.nicart.watchbox.cast

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import space.nicart.watchbox.cast.dlna.DlnaDescriptionParser
import space.nicart.watchbox.cast.dlna.DlnaRenderer
import space.nicart.watchbox.cast.dlna.DlnaTransport
import space.nicart.watchbox.cast.dlna.SsdpDiscovery

/** What the player needs to know about the cast session. */
data class CastState(
    val isCasting: Boolean = false,
    val deviceName: String? = null,
    /** Chromecast and DLNA devices in one list, Chromecast first. */
    val devices: List<CastDevice> = emptyList(),
    val isDiscovering: Boolean = false,
    val errorMessage: String? = null,
) {
    val hasDevices: Boolean get() = devices.isNotEmpty()
}

/**
 * Single entry point for casting, over either protocol.
 *
 * Owns the decision the player should not have to make: whether a stream can be
 * handed to the receiver directly or has to be relayed through
 * [CastProxyServer]. Receivers cannot send request headers, so any stream whose
 * source requires a `Referer` must be proxied or it will simply fail on the TV.
 *
 * The proxy is skipped when there are no headers to inject, which keeps the
 * phone out of the data path and saves battery and uplink on streams that do not
 * need it.
 */
class CastManager(
    private val context: Context,
    private val client: OkHttpClient,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val proxy = CastProxyServer(client)
    private val chromecast = ChromecastTransport(context)
    private val dlna = DlnaTransport(client)

    private val ssdp = SsdpDiscovery(context)
    private val descriptions = DlnaDescriptionParser(client)
    private val routes = ChromecastDiscovery(context)

    /** Renderers by device id, so a picker selection can be resolved back. */
    private val renderers = mutableMapOf<String, DlnaRenderer>()

    /**
     * The two protocols' results, kept apart internally.
     *
     * Chromecast arrives asynchronously via router callbacks while DLNA completes as
     * one batch, so merging on write would let a late Chromecast callback overwrite
     * the DLNA results or vice versa.
     */
    private var chromecastDevices = emptyList<CastDevice>()
    private var dlnaDevices = emptyList<CastDevice>()

    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state.asStateFlow()

    /** Whichever transport currently holds a session. */
    private var active: CastTransport? = null

    val isCasting: Boolean get() = active?.isConnected == true

    fun initialise() {
        // Started once and left running: the router only discovers while a callback
        // is registered, and a device that appears between scans should show up
        // without the user pressing rescan.
        routes.start { discovered ->
            chromecastDevices = discovered
            publishDevices()
        }

        chromecast.initialise { connected ->
            // A Chromecast session can also start from the system UI, so state is
            // driven by the SDK rather than only by our own picker.
            if (connected) {
                active = chromecast
                _state.value = _state.value.copy(
                    isCasting = true,
                    deviceName = chromecast.deviceName,
                )
            } else if (active === chromecast) {
                active = null
                proxy.stop()
                _state.value = _state.value.copy(isCasting = false, deviceName = null)
            }
        }
    }

    fun release() {
        routes.stop()
        chromecast.release()
        proxy.stop()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    // ------------------------------------------------------------- discovery

    /**
     * Scans for DLNA renderers and refreshes the Chromecast scan.
     *
     * Both protocols end up in one list. They were previously separate - Chromecast
     * was not listed at all, on the assumption that the SDK's own picker would be
     * shown, which the app never did - so the panel was empty unless a session had
     * already been started from the notification shade.
     *
     * A hard timeout bounds the whole scan: description fetches fan out per device
     * with the shared client's generous timeouts, and one unresponsive device could
     * otherwise leave the spinner running for minutes.
     */
    fun discover() {
        scope.launch {
            _state.value = _state.value.copy(isDiscovering = true, errorMessage = null)

            // Restarted so a device that appeared since the last scan is picked up
            // even if the router sent no callback.
            routes.start { discovered ->
                chromecastDevices = discovered
                publishDevices()
            }

            val found = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                val locations = ssdp.discoverLocations()
                descriptions.resolveAll(locations)
            }.orEmpty()

            renderers.clear()
            found.forEach { renderers[it.avTransportControlUrl] = it }

            dlnaDevices = found.map {
                CastDevice(
                    id = it.avTransportControlUrl,
                    name = it.friendlyName,
                    host = it.host,
                    protocol = CastProtocol.DLNA,
                )
            }

            Log.i(TAG, "discovery: ${chromecastDevices.size} cast, ${dlnaDevices.size} dlna")

            _state.value = _state.value.copy(isDiscovering = false)
            publishDevices()
        }
    }

    /**
     * Merges both protocols into the published list.
     *
     * Chromecast first: it is the more capable receiver, handles HLS, and is what
     * most users have.
     */
    private fun publishDevices() {
        _state.value = _state.value.copy(devices = chromecastDevices + dlnaDevices)
    }

    // --------------------------------------------------------------- session

    /**
     * Starts casting [media] to [device].
     *
     * [positionMs] carries the local playback position so the TV resumes where
     * the phone left off rather than restarting.
     */
    fun castTo(device: CastDevice, media: CastMedia, positionMs: Long) {
        when (device.protocol) {
            CastProtocol.CHROMECAST -> castToChromecastDevice(device, media, positionMs)
            CastProtocol.DLNA -> castToDlna(device, media, positionMs)
        }
    }

    /**
     * Selects a Chromecast route and loads once the session is up.
     *
     * Selecting a route is asynchronous - the SDK connects, launches the receiver
     * app and only then has a media client - so the load is deferred until the
     * session reports connected rather than attempted immediately.
     */
    private fun castToChromecastDevice(device: CastDevice, media: CastMedia, positionMs: Long) {
        if (!routes.select(device.id)) {
            fail("That device is no longer available.")
            return
        }

        scope.launch {
            val connected = awaitChromecastSession()
            if (!connected) {
                fail("Could not connect to ${device.name}.")
                return@launch
            }

            active = chromecast
            val prepared = prepare(media, chromecast.connectedDeviceHost() ?: device.host)
            if (prepared == null) {
                fail("Could not reach this device from your network.")
                active = null
                return@launch
            }

            if (chromecast.load(prepared, positionMs)) {
                _state.value = _state.value.copy(
                    isCasting = true,
                    deviceName = chromecast.deviceName ?: device.name,
                    errorMessage = null,
                )
            } else {
                proxy.stop()
                active = null
                fail("${device.name} refused the stream.")
            }
        }
    }

    /** Polls for the session rather than adding a second listener. */
    private suspend fun awaitChromecastSession(): Boolean {
        val deadline = System.currentTimeMillis() + SESSION_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (chromecast.isConnected) return true
            delay(SESSION_POLL_MS)
        }
        return false
    }

    private fun castToDlna(device: CastDevice, media: CastMedia, positionMs: Long) {
        scope.launch {
            val renderer = renderers[device.id]
            if (renderer == null) {
                fail("That device is no longer available.")
                return@launch
            }

            dlna.connect(renderer)
            active = dlna

            val prepared = prepare(media, device.host)
            if (prepared == null) {
                fail("Could not reach this device from your network.")
                active = null
                return@launch
            }

            if (dlna.load(prepared, positionMs)) {
                _state.value = _state.value.copy(
                    isCasting = true,
                    deviceName = renderer.friendlyName,
                    errorMessage = null,
                )
            } else {
                proxy.stop()
                active = null
                fail("${renderer.friendlyName} refused the stream.")
            }
        }
    }

    /** Loads onto an already-connected Chromecast session. */
    fun castToConnectedChromecast(media: CastMedia, positionMs: Long) {
        scope.launch {
            if (!chromecast.isConnected) {
                fail("No Chromecast session is active.")
                return@launch
            }

            active = chromecast
            val host = chromecast.connectedDeviceHost()

            val prepared = prepare(media, host)
            if (prepared == null) {
                fail("Could not reach this device from your network.")
                return@launch
            }

            if (!chromecast.load(prepared, positionMs)) {
                fail("The Chromecast refused the stream.")
            }
        }
    }

    /**
     * Rewrites [media] to be fetchable by a receiver.
     *
     * When the stream needs no headers it is passed through untouched, so the
     * phone stays out of the data path. Otherwise the proxy is started on the
     * interface that can reach [deviceHost] and the URL is swapped for a local
     * one.
     */
    private fun prepare(media: CastMedia, deviceHost: String?): CastMedia? {
        if (media.headers.isEmpty()) return media

        // Falls back to the general outbound address when the receiver's own
        // address is unknown. A Chromecast route does not always expose one, and
        // failing outright here would block casting any header-requiring stream
        // to it - whereas the LAN address is almost always the right interface.
        val host = deviceHost ?: CastNetwork.outboundAddress() ?: return null
        if (!proxy.isRunning && proxy.start(host) == null) return null

        val proxied = proxy.publish(
            upstreamUrl = media.url,
            headers = media.headers,
            mimeType = media.mimeType,
            isHls = media.url.contains(".m3u8", true) ||
                media.mimeType.contains("mpegurl", true),
        ) ?: return null

        Log.i(TAG, "casting via local proxy (source requires headers)")
        return media.copy(url = proxied)
    }

    fun stopCasting() {
        active?.stop()
        active = null
        proxy.stop()
        _state.value = _state.value.copy(isCasting = false, deviceName = null)
    }

    fun dismissError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(
            isCasting = false,
            deviceName = null,
            errorMessage = message,
        )
    }

    // ------------------------------------------------------- remote controls

    fun play() = active?.play()

    fun pause() = active?.pause()

    fun seekTo(positionMs: Long) = active?.seekTo(positionMs)

    fun positionMs(): Long = active?.positionMs() ?: 0L

    fun durationMs(): Long = active?.durationMs() ?: 0L

    fun isRemotePlaying(): Boolean = active?.isPlaying() ?: false

    private companion object {
        const val TAG = "CastManager"

        /** Bounds the whole scan; per-device fetches have generous own timeouts. */
        const val DISCOVERY_TIMEOUT_MS = 12_000L

        /** How long to wait for a selected route to become a live session. */
        const val SESSION_TIMEOUT_MS = 15_000L
        const val SESSION_POLL_MS = 250L
    }
}
