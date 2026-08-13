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

    /**
     * Switches the active subtitle track mid-session, or turns them off with -1.
     *
     * [index] is a position in the [CastMedia.subtitles] that were loaded, so callers must use
     * the same list they cast with rather than the player's own - subtitles that could not be
     * published are dropped on the way to the receiver, and the two lists can differ.
     *
     * Defaults to doing nothing: only Chromecast has a track-switching protocol. DLNA sets its
     * subtitle in the DIDL metadata at load time and has no way to change it afterwards, so a
     * silent no-op is the honest implementation rather than a pretence of support.
     */
    fun setSubtitleTrack(index: Int) = Unit
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
    /**
     * Which entry of [subtitles] should start enabled, or -1 for none.
     *
     * Carried so the receiver starts with the track the viewer already had on locally. Activating
     * the first track unconditionally turned subtitles on for someone who had them off, and
     * ignored their choice when several were available.
     */
    val selectedSubtitleIndex: Int = -1,
    /**
     * Which HLS segment format the stream uses, when it could be determined.
     *
     * A Cast receiver assumes MPEG2-TS unless told otherwise, and handed fragmented MP4 instead
     * it loads the manifest, reports the duration, downloads segments and then never decodes a
     * frame - stuck at 0:00 with no error at all. Passing this through is what lets
     * [ChromecastTransport] set `hlsSegmentFormat` on the MediaInfo.
     *
     * Null means undetermined, which leaves the receiver on its own default rather than
     * asserting a format that might be wrong.
     */
    val hlsSegmentFormat: String? = null,
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
    /**
     * True when [url] is a `file://` path on this device rather than a network URL.
     *
     * A receiver fetches subtitles itself, so a local file is unreachable to it. [CastManager]
     * republishes these through the local proxy - the same one already serving the video - and
     * converts them to WebVTT on the way out.
     */
    val isLocalFile: Boolean = false,
    /**
     * The URL this track had before being republished through the proxy.
     *
     * Kept so a mid-session track change can be matched back to the player's own list. The two
     * lists are not index-aligned - a subtitle that could not be published is dropped on the way
     * to the receiver - so positions cannot be used to identify a track across the boundary.
     */
    val sourceUrl: String = url,
)

/** A discovered receiver, whichever protocol found it. */
data class CastDevice(
    val id: String,
    val name: String,
    /**
     * The device's address, when known.
     *
     * Null for a Chromecast that has not been connected yet: the route's extras do
     * not always carry an address, and the SDK only reports one once a session
     * exists. It is needed solely to choose the interface the proxy binds to, which
     * happens after connecting, so a null here is not a problem to solve earlier.
     */
    val host: String?,
    val protocol: CastProtocol,
)

enum class CastProtocol { CHROMECAST, DLNA }
