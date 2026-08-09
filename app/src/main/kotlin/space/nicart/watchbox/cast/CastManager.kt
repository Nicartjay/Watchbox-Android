package space.nicart.watchbox.cast

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    val devices: List<CastDevice> = emptyList(),
    val isDiscovering: Boolean = false,
    val errorMessage: String? = null,
)

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

    /** Renderers by device id, so a picker selection can be resolved back. */
    private val renderers = mutableMapOf<String, DlnaRenderer>()

    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state.asStateFlow()

    /** Whichever transport currently holds a session. */
    private var active: CastTransport? = null

    val isCasting: Boolean get() = active?.isConnected == true

    fun initialise() {
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
        chromecast.release()
        proxy.stop()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    // ------------------------------------------------------------- discovery

    /**
     * Scans for DLNA renderers.
     *
     * Chromecasts are not listed here: the Cast SDK owns their discovery and
     * presents its own picker, and duplicating it would mean two lists that can
     * disagree.
     */
    fun discover() {
        scope.launch {
            _state.value = _state.value.copy(isDiscovering = true, errorMessage = null)

            val locations = ssdp.discoverLocations()
            val found = descriptions.resolveAll(locations)

            renderers.clear()
            found.forEach { renderers[it.avTransportControlUrl] = it }

            _state.value = _state.value.copy(
                isDiscovering = false,
                devices = found.map {
                    CastDevice(
                        id = it.avTransportControlUrl,
                        name = it.friendlyName,
                        host = it.host,
                        protocol = CastProtocol.DLNA,
                    )
                },
            )
        }
    }

    // --------------------------------------------------------------- session

    /**
     * Starts casting [media] to [device].
     *
     * [positionMs] carries the local playback position so the TV resumes where
     * the phone left off rather than restarting.
     */
    fun castTo(device: CastDevice, media: CastMedia, positionMs: Long) {
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

        val host = deviceHost ?: return null
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
    }
}
