package eu.kanade.tachiyomi.animesource.model

import android.net.Uri
import okhttp3.Headers

/**
 * A subtitle or audio track attached to a [Video].
 *
 * See the note in [SAnime] for why this package reproduces the Aniyomi ABI.
 */
data class Track(val url: String, val lang: String)

/**
 * What a [TimeStamp] marks. Added in lib 16.
 *
 * Extensions read the constants as static fields, so their names are ABI.
 */
enum class ChapterType {
    Opening,
    Ending,
    Recap,
    MixedOp,
    Other,
}

/**
 * A chapter marker a lib 16 source attaches to a [Video].
 *
 * @param start start of the marker, in seconds
 * @param end   end of the marker, in seconds
 */
data class TimeStamp(
    val start: Double,
    val end: Double,
    val name: String,
    val type: ChapterType = ChapterType.Other,
)

/**
 * One playable stream returned by an extension for an episode.
 *
 * ## Two generations of the same class
 *
 * Lib 14 built a video from a page URL, a free-text quality label and a media URL. Lib 16
 * made it a data class keyed on the media URL, renamed the label `videoTitle`, and added
 * structured fields beside it. Extensions of both generations are installed side by side, and
 * lib 16 extensions still bundle extractors written against the lib 14 shape, so every one of
 * these must link:
 *
 * ```
 * Video(String, String, String, Headers, List, List)           // lib 14, most common
 * Video(String, String, String, Headers, List, List, int, …)   // lib 14 default-arg synthetic
 * Video(String, String, String, Uri, Headers, int, …)          // legacy, Uri ignored
 * Video(String, String, Integer, Integer, Headers, boolean, …) // lib 16, fourteen parameters
 * Video.copy$default(Video, …)                                 // lib 16 data-class copy
 * ```
 *
 * The lib 14 shape is mapped onto the lib 16 fields, as Aniyomi does, rather than the other
 * way round: [quality] reads [videoTitle], and a missing media URL is stored as
 * [UNRESOLVED_URL]. [videoUrl] is non-null because lib 16 code reads it without a null check,
 * and that code also handles videos its bundled lib 14 extractors built.
 *
 * @param videoUrl     the media URL, or [UNRESOLVED_URL] when a lib 14 source left it unset
 * @param videoTitle   human-readable label, e.g. `"1080p"`
 * @param resolution   height in pixels, when the source knows it
 * @param headers      request headers required by the CDN, usually a Referer
 * @param preferred    the source's own pick; several videos may carry it
 * @param internalData opaque state the source reads back in `resolveVideo`
 * @param initialized  true when [videoUrl] is final and `resolveVideo` need not be called
 */
data class Video(
    val videoUrl: String = "",
    val videoTitle: String = "",
    val resolution: Int? = null,
    val bitrate: Int? = null,
    val headers: Headers? = null,
    val preferred: Boolean = false,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
    val timestamps: List<TimeStamp> = emptyList(),
    val mpvArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegVideoArgs: List<Pair<String, String>> = emptyList(),
    val internalData: String = "",
    val initialized: Boolean = false,
) {

    /**
     * Page the stream was found on, which lib 14 sources resolve a missing URL from.
     *
     * Outside the constructor so it is not part of the lib 16 data-class shape. A [copy]
     * drops it, which is harmless: it is only read before the URL is resolved.
     */
    private var pageUrl: String = ""

    /** Lib 14 name for [videoTitle]. */
    val quality: String
        get() = videoTitle

    /** Lib 14 page URL. Empty for a video a lib 16 source built. */
    val url: String
        get() = pageUrl

    /** The lib 14 shape. A null [videoUrl] becomes [UNRESOLVED_URL]. */
    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        headers: Headers? = null,
        subtitleTracks: List<Track> = emptyList(),
        audioTracks: List<Track> = emptyList(),
    ) : this(
        videoUrl = videoUrl ?: UNRESOLVED_URL,
        videoTitle = quality,
        headers = headers,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
    ) {
        pageUrl = url
    }

    /**
     * Legacy shape that carried a `Uri`. The parameter is accepted and dropped:
     * the host never needed it, and newer extensions stopped passing it.
     */
    @Deprecated("The uri parameter is ignored; use the primary constructor.")
    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        @Suppress("UNUSED_PARAMETER") uri: Uri? = null,
        headers: Headers? = null,
    ) : this(url, quality, videoUrl, headers)

    /** Mutable so the host can mark progress without reallocating. */
    @Volatile
    @Transient
    var status: State = State.QUEUE

    enum class State {
        QUEUE,
        LOAD_VIDEO,
        READY,
        ERROR,
    }

    /**
     * Height for sorting, 0 when unknown.
     *
     * The source's own [resolution] when it gave one, otherwise read out of the label, which
     * is all a lib 14 source ever provides.
     */
    val resolutionOrZero: Int
        get() = resolution
            ?: RESOLUTION.find(videoTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: 0

    // Headers routinely carry cookies and tokens, so they stay out of anything that may be
    // logged - which is why the generated data-class toString is replaced.
    override fun toString(): String = "Video(videoTitle=$videoTitle, videoUrl=$videoUrl)"

    companion object {
        /** What a lib 14 source's null media URL is stored as, matching Aniyomi. */
        internal const val UNRESOLVED_URL = "null"

        private val RESOLUTION = Regex("""(\d{3,4})\s*[pP]""")
    }
}
