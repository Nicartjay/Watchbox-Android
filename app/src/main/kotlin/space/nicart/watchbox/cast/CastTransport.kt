package space.nicart.watchbox.cast

/**
 * What both cast protocols must be able to do.
 *
 * Chromecast and DLNA are wildly different on the wire — protobuf over TLS versus
 * SOAP over HTTP — but the player only ever needs this much. Keeping the surface
 * this narrow is what lets [CastManager] treat them interchangeably and stops
 * protocol detail leaking into the UI.
 */
interface CastTransport {

    /** False when the protocol cannot run at all, e.g. no Play Services. */
    val isAvailable: Boolean

    val isConnected: Boolean

    /** Friendly name of the connected device, for the "Playing on …" label. */
    val deviceName: String?

    /**
     * Host of the connected device.
     *
     * The proxy needs this to bind an interface the device can actually reach;
     * see [CastNetwork.localAddressFor].
     */
    fun connectedDeviceHost(): String?

    suspend fun load(media: CastMedia, positionMs: Long): Boolean

    fun play()

    fun pause()

    fun stop()

    fun seekTo(positionMs: Long)

    fun positionMs(): Long

    fun durationMs(): Long

    fun isPlaying(): Boolean
}

/**
 * A stream on its way to a receiver.
 *
 * [headers] are the upstream request headers the extension supplied, usually a
 * `Referer` the CDN checks. Receivers cannot send headers themselves, so
 * [CastManager] uses this to decide whether the stream must be relayed through
 * the local proxy: non-empty means proxy, empty means hand the URL over directly.
 *
 * After that decision [url] is final — it points either at the CDN or at the
 * proxy — and the transports never look at [headers] again.
 */
data class CastMedia(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
    val subtitles: List<CastSubtitle> = emptyList(),
    val isMovie: Boolean = true,
)

/**
 * A subtitle track for a receiver.
 *
 * Must be WebVTT: Chromecast ignores SRT outright, and DLNA renderers that accept
 * SRT are the exception rather than the rule.
 */
data class CastSubtitle(
    val url: String,
    val label: String,
    val language: String,
)

/** A discovered receiver, whichever protocol found it. */
data class CastDevice(
    val id: String,
    val name: String,
    val host: String,
    val protocol: CastProtocol,
)

enum class CastProtocol { CHROMECAST, DLNA }
