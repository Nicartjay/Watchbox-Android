package space.nicart.watchbox.domain

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Streams for an episode of a lib 16 source, as the flat progressive list the player takes.
 *
 * Lib 16 replaced "the videos for this episode" with two steps: a list of hosters, then the
 * videos on each, some of which still need [AnimeHttpSource.resolveVideo] before they play.
 * Rather than give the player a second, hoster-shaped model, every hoster is loaded and its
 * videos are folded into the same cumulative list a
 * [eu.kanade.tachiyomi.animesource.ProgressiveVideoSource] emits, with the hoster's name
 * leading each label so the server picker groups them by it.
 *
 * ## When the first batch goes out
 *
 * The player plays whatever the first emission offers and never switches on its own, so the
 * first emission decides what the viewer gets. Emitting whichever hoster answered first would
 * let a fast server override the order the source chose - and sources sort hosters by the
 * viewer's own settings in the extension, such as sub or dub. So the first batch waits until
 * every eager hoster ranked above the first one with streams has answered, which is the order
 * Aniyomi honours. A video the source marks `preferred` is the source's explicit choice and
 * releases the batch at once. After that, each hoster that finishes widens the list.
 *
 * ## Lazy hosters
 *
 * A `lazy` hoster is one the source considers too expensive to load unasked. With no
 * per-hoster picker to ask from, they are loaded after every eager hoster has finished, so
 * they never delay the first stream but still reach the picker.
 *
 * ## Failure
 *
 * A hoster that fails contributes nothing and the others carry on. Only when no hoster
 * produced a stream does the flow fail, with the first error seen, so the message names what
 * actually went wrong.
 */
internal fun AnimeHttpSource.hosterStreams(episode: SEpisode): Flow<List<StreamOption>> =
    channelFlow {
        val source = this@hosterStreams
        val hosters = getHosterList(episode).let { listed ->
            // A source's own sorting is preference code and can throw like any other; the
            // order it was listed in is still usable.
            runCatching { with(source) { listed.sortHosters() } }.getOrDefault(listed)
        }
        val loaded = arrayOfNulls<List<HosterStream>>(hosters.size)
        val lock = Mutex()
        val resolveGate = Semaphore(MAX_PARALLEL_RESOLVES)
        var started = false
        var firstFailure: Throwable? = null

        suspend fun load(index: Int) {
            val streams = try {
                source.loadHoster(hosters[index], resolveGate)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Throwable, not Exception: an extension linked against a member this app
                // lacks fails with a LinkageError, and that must not take the others down.
                lock.withLock { if (firstFailure == null) firstFailure = t }
                emptyList()
            }

            lock.withLock {
                loaded[index] = streams
                if (!started) started = readyToStart(hosters, loaded)
                if (started) send(loaded.cumulative())
            }
        }

        val (lazy, eager) = hosters.indices.partition { hosters[it].lazy }
        coroutineScope { eager.forEach { launch { load(it) } } }
        coroutineScope { lazy.forEach { launch { load(it) } } }

        if (!started) firstFailure?.let { throw it }
    }

/** A stream, with the one fact about its video that ordering still needs. */
private class HosterStream(val option: StreamOption, val preferred: Boolean)

/**
 * Whether the first batch may go out.
 *
 * True once a preferred stream has arrived, or once some hoster has streams and every eager
 * hoster ranked above it has answered. Lazy hosters do not hold the batch: they only start
 * after every eager one is done.
 */
private fun readyToStart(hosters: List<Hoster>, loaded: Array<List<HosterStream>?>): Boolean {
    if (loaded.any { batch -> batch.orEmpty().any { it.preferred } }) return true
    val firstWithStreams = loaded.indexOfFirst { !it.isNullOrEmpty() }
    if (firstWithStreams == -1) return false
    return (0 until firstWithStreams).none { !hosters[it].lazy && loaded[it] == null }
}

/** Every stream so far, in hoster order, with the source's preferred ones first. */
private fun Array<List<HosterStream>?>.cumulative(): List<StreamOption> =
    filterNotNull()
        .flatten()
        // Stable, so hoster order survives within each group.
        .sortedByDescending { it.preferred }
        .map { it.option }

/** One hoster's videos, sorted by the source and resolved, as labelled streams. */
private suspend fun AnimeHttpSource.loadHoster(
    hoster: Hoster,
    resolveGate: Semaphore,
): List<HosterStream> {
    val listed = hoster.videoList ?: getVideoList(hoster)
    val sorted = runCatching { sortedVideos(listed) }.getOrDefault(listed)
    val server = hoster.hosterName.takeUnless { it.isBlank() || it == Hoster.NO_HOSTER_LIST }

    val resolved = coroutineScope {
        sorted.map { video -> async { resolveGate.withPermit { resolveOrNull(video) } } }
            .awaitAll()
    }

    return resolved.filterNotNull()
        .map { video ->
            val option = video.toStreamOption()
            HosterStream(
                option = option.copy(label = streamLabel(server, video.videoTitle, option.label)),
                preferred = video.preferred,
            )
        }
        .filter { it.option.url.isNotBlank() }
}

/**
 * Fills in what the source left for later, or null when it could not.
 *
 * Two kinds of "later": a lib 14 extractor bundled into a lib 16 source still marks a missing
 * URL as [Video.UNRESOLVED_URL], and a lib 16 video that is not `initialized` wants
 * [AnimeHttpSource.resolveVideo]. Resolved here rather than when it is picked because every
 * other source hands the player finished streams, and a download re-resolves through this
 * same path.
 */
private suspend fun AnimeHttpSource.resolveOrNull(video: Video): Video? = try {
    val withUrl = if (video.videoUrl == Video.UNRESOLVED_URL) {
        video.copy(videoUrl = getVideoUrl(video))
    } else {
        video
    }
    if (withUrl.initialized) withUrl else resolveVideo(withUrl)
} catch (e: CancellationException) {
    throw e
} catch (_: Throwable) {
    null
}

/**
 * `Server · title`, the shape [space.nicart.watchbox.ui.player.StreamFacets] splits.
 *
 * Lib 16 titles usually name only the quality, because the hoster carries the server. A title
 * that already names its server is left alone rather than repeating it.
 */
internal fun streamLabel(server: String?, title: String, fallback: String): String = when {
    server == null -> fallback
    title.isBlank() -> server
    title.contains(server, ignoreCase = true) -> title
    else -> "$server · $title"
}

/**
 * Resolutions in flight at once across one episode's hosters.
 *
 * Most sources resolve nothing - the default returns the video as-is - but one that does
 * makes a request per video, and a server listing a dozen qualities should not open a
 * dozen connections to it at once.
 */
private const val MAX_PARALLEL_RESOLVES = 4
