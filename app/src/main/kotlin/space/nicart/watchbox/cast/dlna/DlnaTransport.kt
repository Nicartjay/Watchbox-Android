package space.nicart.watchbox.cast.dlna

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import space.nicart.watchbox.cast.CastMedia
import space.nicart.watchbox.cast.CastTransport

/**
 * DLNA/UPnP transport.
 *
 * Speaks SOAP over HTTP to a renderer's AVTransport service. Unlike Chromecast
 * there is no SDK and no session: the renderer is a stateless endpoint we poll.
 *
 * ## Quirk handling
 *
 * Renderers are far less uniform than Chromecasts, and three of go2tv's
 * workarounds are reproduced here because each fixes a real class of device:
 *
 *  - **Error 701 / "transition not available"** — several TVs (Samsung notably)
 *    refuse SetAVTransportURI while something is already loaded, so a Stop is
 *    issued and the call retried.
 *  - **Escaped nested DIDL** — some renderers reject fully-escaped metadata, so a
 *    retry un-escapes quotes and ampersands inside `CurrentURIMetaData`.
 *  - **HTTP 405** — a handful of devices only accept the older `M-POST` form with
 *    `MAN` and `01-SOAPACTION` headers instead of `SOAPAction`.
 *
 * Without these, casting works on some TVs and fails inexplicably on others.
 */
class DlnaTransport(
    private val client: OkHttpClient,
) : CastTransport {

    @Volatile
    private var renderer: DlnaRenderer? = null

    @Volatile
    private var lastKnownDurationMs: Long = 0L

    override val isAvailable: Boolean get() = true

    override val isConnected: Boolean get() = renderer != null

    override val deviceName: String? get() = renderer?.friendlyName

    override fun connectedDeviceHost(): String? = renderer?.host

    fun connect(target: DlnaRenderer) {
        renderer = target
        lastKnownDurationMs = 0L
    }

    override suspend fun load(media: CastMedia, positionMs: Long): Boolean {
        val target = renderer ?: return false
        val metadata = DlnaSoap.didlLite(media)

        val loaded = setUri(target, media.url, metadata)
            // Retry with un-escaped metadata: some renderers reject fully
            // escaped nested DIDL.
            || setUri(target, media.url, metadata, legacyEscaping = true)
            // Last resort: stop whatever is loaded, then retry. Fixes AVTransport
            // error 701 on renderers that refuse a transition while busy.
            || run {
                stopInternal(target)
                delay(STOP_SETTLE_MS)
                setUri(target, media.url, metadata)
            }

        if (!loaded) return false

        soap(target, "Play", DlnaSoap.play())

        if (positionMs > SEEK_THRESHOLD_MS) {
            // Renderers ignore a seek issued before playback has actually begun.
            delay(SEEK_AFTER_PLAY_MS)
            seekTo(positionMs)
        }

        media.durationMs.takeIf { it > 0 }?.let { lastKnownDurationMs = it }
        return true
    }

    private suspend fun setUri(
        target: DlnaRenderer,
        url: String,
        metadata: String,
        legacyEscaping: Boolean = false,
    ): Boolean {
        val body = DlnaSoap.setAvTransportUri(url, metadata).let { envelope ->
            if (legacyEscaping) {
                envelope.replace("&amp;quot;", "\"").replace("&amp;amp;", "&")
            } else {
                envelope
            }
        }

        val response = soap(target, "SetAVTransportURI", body) ?: return false

        // A 200 can still carry a SOAP fault, so the body has to be inspected.
        val failed = response.contains("<errorCode>", true) ||
            response.contains("faultcode", true)

        if (failed) {
            Log.d(TAG, "SetAVTransportURI fault (legacyEscaping=$legacyEscaping)")
            return false
        }
        return true
    }

    override fun play() {
        renderer?.let { blocking { soap(it, "Play", DlnaSoap.play()) } }
    }

    override fun pause() {
        renderer?.let { blocking { soap(it, "Pause", DlnaSoap.pause()) } }
    }

    override fun stop() {
        renderer?.let { target -> blocking { stopInternal(target) } }
        renderer = null
    }

    private suspend fun stopInternal(target: DlnaRenderer) {
        soap(target, "Stop", DlnaSoap.stop())
    }

    override fun seekTo(positionMs: Long) {
        renderer?.let { blocking { soap(it, "Seek", DlnaSoap.seek(positionMs)) } }
    }

    override fun positionMs(): Long {
        val target = renderer ?: return 0L
        val response = blocking { soap(target, "GetPositionInfo", DlnaSoap.getPositionInfo()) }
            ?: return 0L

        DlnaSoap.parseClockTime(response, "TrackDuration")
            ?.takeIf { it > 0 }
            ?.let { lastKnownDurationMs = it }

        return DlnaSoap.parseClockTime(response, "RelTime") ?: 0L
    }

    override fun durationMs(): Long = lastKnownDurationMs

    override fun isPlaying(): Boolean {
        val target = renderer ?: return false
        val response = blocking { soap(target, "GetTransportInfo", DlnaSoap.getTransportInfo()) }
            ?: return false
        return DlnaSoap.parseTransportState(response) == "PLAYING"
    }

    /**
     * Sends one SOAP action, falling back to `M-POST` on HTTP 405.
     *
     * Returns the body on success, or null on transport failure, so callers can
     * distinguish "no answer" from "answered with a fault".
     */
    private suspend fun soap(
        target: DlnaRenderer,
        action: String,
        body: String,
    ): String? = withContext(Dispatchers.IO) {
        val url = target.avTransportControlUrl
        val payload = body.toRequestBody(SOAP_CONTENT_TYPE)

        val standard = Request.Builder()
            .url(url)
            .post(payload)
            .header("SOAPAction", DlnaSoap.soapAction(action))
            .header("Content-Type", SOAP_CONTENT_TYPE_VALUE)
            .header("Connection", "close")
            .build()

        val result = runCatching {
            client.newCall(standard).execute().use { response ->
                if (response.code == HTTP_METHOD_NOT_ALLOWED) null
                else response.body.string()
            }
        }.getOrNull()

        if (result != null) return@withContext result

        // M-POST fallback: SOAPAction is replaced by MAN + a namespaced action.
        val mPost = Request.Builder()
            .url(url)
            .method("M-POST", payload)
            .header("MAN", """"http://schemas.xmlsoap.org/soap/envelope/"; ns=01""")
            .header("01-SOAPACTION", DlnaSoap.soapAction(action))
            .header("Content-Type", SOAP_CONTENT_TYPE_VALUE)
            .header("Connection", "close")
            .build()

        runCatching {
            client.newCall(mPost).execute().use { it.body.string() }
        }.onFailure {
            Log.d(TAG, "$action failed: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    /**
     * Bridges the synchronous [CastTransport] control methods onto suspending
     * calls.
     *
     * The interface is synchronous because Chromecast's SDK is, and DLNA is the
     * odd one out. Calls are short and already off the main thread when invoked
     * from the player's ticker, but they are kept individually brief so a
     * misbehaving renderer cannot block for long.
     */
    private fun <T> blocking(block: suspend () -> T): T? = runCatching {
        runBlocking { block() }
    }.getOrNull()

    private companion object {
        const val TAG = "Dlna"
        const val SOAP_CONTENT_TYPE_VALUE = "text/xml; charset=\"utf-8\""
        val SOAP_CONTENT_TYPE = SOAP_CONTENT_TYPE_VALUE.toMediaType()
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val STOP_SETTLE_MS = 350L
        const val SEEK_AFTER_PLAY_MS = 800L
        const val SEEK_THRESHOLD_MS = 5_000L
    }
}
