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
 * A stream prepared for a receiver.
 *
 * [url] is already final: if the source needed headers, it points at the local
 * proxy rather than the CDN. Receivers cannot send headers themselves, which is
 * the whole reason the proxy exists.
 */
data class CastMedia(
    val url: String,
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
