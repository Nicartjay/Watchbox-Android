package space.nicart.watchbox.download

import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.runBlocking
import space.nicart.watchbox.domain.AnimeRepository
import space.nicart.watchbox.domain.StreamOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a persisted download record back into a live stream.
 *
 * Downloads store `(sourceId, animeUrl, episodeUrl, streamLabel)` and nothing more, because
 * a stream URL from an extension carries a signature that expires in about two minutes. So
 * every download needs its URL resolved fresh - once when it starts, and again whenever the
 * credential dies mid-transfer.
 *
 * The label is matched back rather than compared: a source may reorder its list or reword an
 * entry between calls, so an exact match is tried first and then progressively looser ones,
 * ending at "the same resolution from any server". That last fallback is deliberate - a
 * download half-finished at 1080p should continue at 1080p from a different mirror rather
 * than fail because one server went away.
 */
@UnstableApi
class DownloadStreamResolver(
    private val repository: AnimeRepository,
) {

    /**
     * Every download that may need a URL resolved, keyed by its own key.
     *
     * A map rather than one slot, which is what it used to be. With several downloads running,
     * each new one overwrote the slot, so a credential failure on any of the others re-resolved
     * against the *last* episode queued - matching a label from the wrong episode, or finding
     * nothing and aborting. Only whichever download happened to be last could recover, which is
     * exactly the "one works, the rest fail" shape.
     */
    private val active = ConcurrentHashMap<String, ActiveDownload>()

    /** Headers last resolved per download, so every segment does not re-resolve. */
    private val headers = ConcurrentHashMap<String, Map<String, String>>()

    fun setActive(
        key: String,
        sourceId: Long,
        episodeUrl: String,
        streamLabel: String,
        /**
         * Headers already known for this download, from the resolve that queued it.
         *
         * Seeded here so the first request carries them without a second trip through the
         * extension. A segmented download opens hundreds of connections; resolving for each one
         * would make the download slower than streaming it.
         */
        knownHeaders: Map<String, String> = emptyMap(),
    ) {
        active[key] = ActiveDownload(sourceId, episodeUrl, streamLabel)
        if (knownHeaders.isNotEmpty()) headers[key] = knownHeaders
    }

    /**
     * Headers to send for [key], without touching the network.
     *
     * Cached from the resolve that queued the download, or from the last refresh. Empty when
     * nothing is known, which is correct for a source that needs none.
     */
    fun headersFor(key: String): Map<String, String> = headers[key].orEmpty()

    fun clearActive(key: String) {
        active.remove(key)
        headers.remove(key)
    }

    /**
     * A freshly resolved URL and headers for the download identified by [key].
     *
     * Blocking, because Media3's data source contract is synchronous and this is called from
     * a download thread that is already off the main thread. Returning null aborts the
     * attempt, which the engine surfaces as a failed download rather than retrying forever.
     */
    fun currentStream(key: String): ReResolvingDataSource.ResolvedStream? {
        val target = active[key] ?: return null

        val streams = runBlocking {
            repository.streams(target.sourceId, target.episodeUrl).getOrNull()
        }.orEmpty()

        val match = matchLabel(streams, target.streamLabel) ?: return null

        // Cached so the segments that follow this refresh do not each resolve again.
        if (match.headers.isNotEmpty()) headers[key] = match.headers

        return ReResolvingDataSource.ResolvedStream(
            url = match.url,
            headers = match.headers,
        )
    }

    private data class ActiveDownload(
        val sourceId: Long,
        val episodeUrl: String,
        val streamLabel: String,
    )
}

/**
 * Finds the stream that best corresponds to a label recorded earlier.
 *
 * Tried in descending order of confidence, because the alternative to a loose match is a
 * download that cannot resume:
 *
 *  1. The identical label, which is the common case within a session.
 *  2. Same server and same resolution, which survives a reworded size or codec tag.
 *  3. Same resolution from any server, which survives a server disappearing.
 *  4. The closest resolution at or below, which survives a release being pulled.
 *
 * Returns null only when the source returned nothing at all.
 */
internal fun matchLabel(streams: List<StreamOption>, label: String): StreamOption? {
    if (streams.isEmpty()) return null

    streams.firstOrNull { it.label == label }?.let { return it }

    val wantedServer = label.substringBefore('·').trim().takeIf { it.isNotBlank() }
    val wantedHeight = HEIGHT.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()

    if (wantedHeight != null) {
        streams.firstOrNull {
            it.resolution == wantedHeight &&
                wantedServer != null &&
                it.label.startsWith(wantedServer)
        }?.let { return it }

        streams.filter { it.resolution == wantedHeight }
            .maxByOrNull { it.resolution }
            ?.let { return it }

        streams.filter { it.resolution in 1..wantedHeight }
            .maxByOrNull { it.resolution }
            ?.let { return it }
    }

    // Nothing about the label could be honoured. The best available beats refusing to
    // resume a download that already has bytes on disk.
    return streams.maxByOrNull { it.resolution } ?: streams.firstOrNull()
}

private val HEIGHT = Regex("""(\d{3,4})\s*[pP]""")
