package space.nicart.watchbox.download

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import space.nicart.watchbox.data.local.DownloadEntry
import space.nicart.watchbox.data.local.DownloadState
import space.nicart.watchbox.domain.AnimeDetail
import space.nicart.watchbox.domain.EpisodeEntry
import space.nicart.watchbox.domain.StreamOption

/** Progress through preparing the queue, not through transferring the video bytes. */
data class ShortReelsDownloadState(
    val total: Int = 0,
    val processed: Int = 0,
    val queued: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val cancelled: Int = 0,
    val activeRequests: Int = 0,
    val lastError: String? = null,
) {
    val isRunning: Boolean get() = activeRequests > 0
}

/** No prompts: honor the saved height, or the extension's own ordering for Auto. */
internal fun automaticDownloadStream(
    streams: List<StreamOption>,
    preferredHeight: Int?,
): StreamOption? {
    val usable = streams.filter { it.url.isNotBlank() }
    if (preferredHeight == null) return usable.firstOrNull()
    return usable.filter { it.resolution in 1..preferredHeight }.maxByOrNull { it.resolution }
        ?: usable.filter { it.resolution > 0 }.minByOrNull { it.resolution }
        ?: usable.firstOrNull()
}

internal fun shouldQueueAutomaticDownload(existing: DownloadEntry?): Boolean =
    existing == null || existing.state == DownloadState.FAILED

/**
 * Application-owned preparation: navigating away from Detail does not cancel a series.
 * Resolve sequentially to avoid flooding a provider, while the download engines run their
 * own bounded queues. Duplicate taps reserve the same episode only once.
 */
internal class ShortReelsDownloadQueue(
    private val scope: CoroutineScope,
    private val existingDownloads: suspend () -> List<DownloadEntry>,
    private val onStart: suspend (AnimeDetail) -> Unit,
    private val prepare: suspend (AnimeDetail, EpisodeEntry) -> Unit,
    private val onIdle: suspend () -> Unit,
) {
    private val _states = MutableStateFlow<Map<String, ShortReelsDownloadState>>(emptyMap())
    val states: StateFlow<Map<String, ShortReelsDownloadState>> = _states.asStateFlow()
    private val serial = Mutex()
    private val reserved = ConcurrentHashMap.newKeySet<String>()
    private val ids = AtomicLong()
    private data class Request(val titleKey: String, val job: Job)
    private val requests = ConcurrentHashMap<Long, Request>()

    fun enqueue(detail: AnimeDetail, episodes: List<EpisodeEntry>) {
        val fresh = episodes.distinctBy { it.url }.filter { reserved.add(episodeKey(detail, it)) }
        if (fresh.isEmpty()) return
        update(detail.key) { previous ->
            val base = if (previous.isRunning) previous else ShortReelsDownloadState()
            base.copy(total = base.total + fresh.size, activeRequests = base.activeRequests + 1)
        }

        val id = ids.incrementAndGet()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var processed = 0
            try {
                serial.withLock {
                    val existing = existingDownloads().associateBy { it.key }
                    val newEntries = fresh.count { episodeKey(detail, it) !in existing }
                    check(existing.size + newEntries <= DownloadEntry.MAX_ENTRIES) {
                        "Download limit reached. Remove older downloads before adding this series."
                    }
                    if (fresh.any { shouldQueueAutomaticDownload(existing[episodeKey(detail, it)]) }) {
                        onStart(detail)
                    }
                    fresh.forEach { episode ->
                        currentCoroutineContext().ensureActive()
                        if (!shouldQueueAutomaticDownload(existing[episodeKey(detail, episode)])) {
                            update(detail.key) { it.copy(processed = it.processed + 1, skipped = it.skipped + 1) }
                        } else {
                            try {
                                prepare(detail, episode)
                                update(detail.key) { it.copy(processed = it.processed + 1, queued = it.queued + 1) }
                            } catch (error: TimeoutCancellationException) {
                                failed(detail.key, "Timed out preparing ${episode.displayName}.")
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                failed(detail.key, error.message ?: "Could not prepare ${episode.displayName}.")
                            }
                        }
                        processed++
                        reserved.remove(episodeKey(detail, episode))
                    }
                }
            } catch (error: CancellationException) {
                update(detail.key) {
                    it.copy(
                        processed = it.processed + fresh.size - processed,
                        cancelled = it.cancelled + fresh.size - processed,
                    )
                }
                throw error
            } catch (error: Exception) {
                update(detail.key) {
                    it.copy(
                        processed = it.processed + fresh.size - processed,
                        failed = it.failed + fresh.size - processed,
                        lastError = error.message ?: "Could not prepare downloads.",
                    )
                }
            } finally {
                fresh.forEach { reserved.remove(episodeKey(detail, it)) }
                requests.remove(id)
                update(detail.key) { it.copy(activeRequests = (it.activeRequests - 1).coerceAtLeast(0)) }
                withContext(NonCancellable) { runCatching { onIdle() } }
            }
        }
        requests[id] = Request(detail.key, job)
        job.start()
    }

    /** Already-added downloads remain in the ordinary download queue. */
    fun cancel(titleKey: String) {
        requests.values.filter { it.titleKey == titleKey }.forEach { it.job.cancel() }
    }

    suspend fun cancelAll() {
        requests.values.map { it.job }.forEach { it.cancelAndJoin() }
    }

    private fun failed(titleKey: String, message: String) = update(titleKey) {
        it.copy(processed = it.processed + 1, failed = it.failed + 1, lastError = message)
    }

    private fun update(key: String, transform: (ShortReelsDownloadState) -> ShortReelsDownloadState) {
        _states.update { states -> states + (key to transform(states[key] ?: ShortReelsDownloadState())) }
    }

    private fun episodeKey(detail: AnimeDetail, episode: EpisodeEntry): String =
        "${detail.key}::${episode.url}"

}
