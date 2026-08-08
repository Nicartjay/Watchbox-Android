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
 * One playable stream returned by an extension for an episode.
 *
 * ## Constructor shapes
 *
 * The extension APKs surveyed call three different constructors, all of which
 * must keep working:
 *
 * ```
 * Video(String, String, String, Headers, List, List)          // most common
 * Video(String, String, String, Headers, List, List, int, …)  // default-arg synthetic
 * Video(String, String, String, Uri, Headers, int, …)         // legacy, Uri ignored
 * ```
 *
 * @param url        page the stream was found on, used for Referer purposes
 * @param quality    human-readable label, e.g. `"1080p"`
 * @param videoUrl   the actual media URL; null when it must be resolved lazily
 * @param headers    request headers required by the CDN, usually a Referer
 */
@Suppress("DataClassPrivateConstructor")
class Video(
    val url: String,
    val quality: String,
    val videoUrl: String?,
    val headers: Headers? = null,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
) {

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

    /** Numeric height parsed out of [quality], for sorting. 0 when unknown. */
    val resolutionOrZero: Int
        get() = RESOLUTION.find(quality)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Video) return false
        return url == other.url && quality == other.quality && videoUrl == other.videoUrl
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + quality.hashCode()
        result = 31 * result + (videoUrl?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "Video(quality=$quality, videoUrl=$videoUrl)"

    private companion object {
        val RESOLUTION = Regex("""(\d{3,4})\s*[pP]""")
    }
}
