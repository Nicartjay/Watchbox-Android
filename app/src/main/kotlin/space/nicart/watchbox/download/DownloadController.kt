package space.nicart.watchbox.download

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.nicart.watchbox.data.local.DownloadEntry
import space.nicart.watchbox.data.local.DownloadState
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.data.remote.SubtitleQuery
import space.nicart.watchbox.domain.AnimeRepository
import space.nicart.watchbox.domain.EpisodeEntry
import space.nicart.watchbox.domain.StreamOption
import space.nicart.watchbox.domain.SubtitleRepository

/**
 * The app's handle on downloading.
 *
 * Sits between the UI and two stores that both hold part of the truth: Media3's own index
 * knows the bytes, and the registry knows what the download *is* - which title, which
 * episode, which quality was asked for. Neither can be derived from the other, so this keeps
 * them in step and hands the UI one merged view.
 *
 * Live byte progress is exposed from memory rather than persisted. It changes several times a
 * second, and every registry write rewrites the whole preferences blob and re-emits to every
 * collector in the app; persisting progress would make downloading the most expensive thing
 * in the process.
 */
@UnstableApi
class DownloadController(
    private val context: Context,
    private val engine: DownloadEngine,
    private val store: WatchBoxStore,
    private val storage: DownloadStorage,
    private val resolver: DownloadStreamResolver,
    private val repository: AnimeRepository,
    /**
     * Online subtitles, for downloads that should work with no network at all.
     *
     * Nullable so the engine can be built without it - a download with no subtitle is a
     * working download, and the search is the one part of this that depends on a third-party
     * service being reachable.
     */
    private val subtitleRepository: SubtitleRepository? = null,
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())

    /** Live progress by [DownloadEntry.key]. Empty for anything not currently running. */
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    /**
     * Starts listening to the engine and brings the registry back in line with disk.
     *
     * Called once the first time downloads are used rather than at startup: touching the
     * manager opens a database and scans the cache index, and most sessions never download
     * anything.
     */
    fun start() {
        if (started) return
        started = true

        scope.launch {
            val settings = store.currentSettings()
            engine.useVolume(settings.downloadVolume)

            val manager = engine.manager()
            manager.addListener(listener)
            applyRequirements(settings.downloadWifiOnly)
            reconcile()
            pollProgress(manager)
        }
    }

    /**
     * Polls the running downloads for their byte counts.
     *
     * Necessary because `onDownloadChanged` fires on a *state* transition - queued to
     * downloading, downloading to complete - and not as bytes arrive. Listening alone
     * therefore reports 0% and then 100%, which is why the notification appeared to work
     * while the app's own UI did not: `DownloadNotificationHelper` polls
     * `getCurrentDownloads()` on its own timer, and nothing here did.
     *
     * Runs only while something is actually downloading, and drops to a sleep otherwise, so an
     * idle app is not waking up twice a second to ask about nothing.
     */
    private suspend fun pollProgress(manager: DownloadManager) {
        while (true) {
            val active = manager.currentDownloads

            if (active.isEmpty()) {
                // Clear once rather than every tick, so a finished download's last reading
                // does not linger and the map does not churn while idle.
                if (_progress.value.isNotEmpty()) _progress.value = emptyMap()
                delay(IDLE_POLL_MS)
                continue
            }

            _progress.value = active.associate { download ->
                download.request.id to DownloadProgress(
                    percent = download.percentDownloaded.takeIf { it >= 0f } ?: 0f,
                    bytesDownloaded = download.bytesDownloaded,
                    // Total is only knowable for a progressive file, where the server sends a
                    // length. A segmented download has no total until it finishes, so this is
                    // zero there and the UI shows a percentage instead of "x of y".
                    totalBytes = download.contentLength.takeIf { it > 0 } ?: 0L,
                )
            }

            delay(ACTIVE_POLL_MS)
        }
    }

    private var started = false

    /**
     * Queues [episode] for download at the quality [stream] names.
     *
     * The URL on [stream] is used for this attempt only. What is persisted is the label, so a
     * resume hours later can re-resolve and match it - a signed URL is dead within minutes.
     */
    fun enqueue(
        sourceId: Long,
        animeUrl: String,
        title: String,
        posterUrl: String?,
        sourceName: String,
        episode: EpisodeEntry,
        stream: StreamOption,
    ) {
        scope.launch {
            val settings = store.currentSettings()
            engine.useVolume(settings.downloadVolume)

            val entry = DownloadEntry(
                sourceId = sourceId,
                animeUrl = animeUrl,
                episodeUrl = episode.url,
                title = title,
                episodeName = episode.name,
                episodeNumber = episode.number,
                posterUrl = posterUrl,
                sourceName = sourceName,
                streamLabel = stream.label,
                // Both needed to play it back offline. An adaptive stream is reopened at the
                // manifest URI it was downloaded from - its segments are keyed individually
                // and listed inside that cached manifest - while a progressive file is found
                // by the download's own key.
                isAdaptive = stream.isHls || stream.isDash,
                downloadUri = stream.url,
                volumeId = settings.downloadVolume ?: VOLUME_INTERNAL,
                state = DownloadState.QUEUED,
                createdAt = System.currentTimeMillis(),
            )
            store.saveDownload(entry)

            // The engine needs to know which download it is working on before the first
            // request, because the data source asks the resolver for headers rather than
            // being handed them.
            resolver.setActive(sourceId, episode.url, stream.label)

            val request = DownloadRequest.Builder(entry.key, android.net.Uri.parse(stream.url))
                // Declared, not sniffed. A manifest handed to the progressive downloader is
                // fetched as one opaque file - the segments it points at are never followed -
                // so an HLS download would "succeed" as an unplayable playlist.
                .setMimeType(
                    when {
                        stream.isHls -> MimeTypes.APPLICATION_M3U8
                        stream.isDash -> MimeTypes.APPLICATION_MPD
                        else -> null
                    },
                )
                // Progressive only, because Media3 rejects a custom key on anything adaptive -
                // "customCacheKey must be null for type: 2" and the download crashes.
                //
                // The two need different treatment anyway. A progressive file is one cache
                // entry keyed by its URL, and since these URLs are signed and expire it needs a
                // stable key or the bytes become unfindable. An adaptive stream is a manifest
                // plus hundreds of segments, each already keyed by its own URL and listed
                // inside the cached manifest - so it is found by reopening the same manifest
                // URI, which Media3 records in its own index and playback reads back.
                .apply {
                    if (!stream.isHls && !stream.isDash) setCustomCacheKey(entry.key)
                }
                .build()

            withContext(Dispatchers.Main) {
                DownloadService.sendAddDownload(
                    context,
                    MediaDownloadService::class.java,
                    request,
                    /* foreground = */ false,
                )
            }

            // After the video is queued, not before. A subtitle is a courtesy; failing to find
            // one must not stop the download the user actually asked for, and the search is a
            // network round-trip that would otherwise delay it.
            fetchSubtitle(entry, episode)
        }
    }

    /**
     * Fetches a subtitle in the preferred language for a queued download.
     *
     * Best-effort throughout. No match, no IMDb or TMDB id to search with, a provider that is
     * down - each ends with the download having no external subtitle, which is the same
     * position as before this ran. Source-embedded tracks are unaffected: they travel inside
     * the media and need no help.
     *
     * The file is written beside the video rather than into the subtitle cache, which is
     * emptied at every episode change and would take an offline copy with it.
     */
    private suspend fun fetchSubtitle(entry: DownloadEntry, episode: EpisodeEntry) {
        val subtitles = subtitleRepository ?: return
        val settings = store.currentSettings()
        val language = settings.subtitleLanguage.takeIf { it.isNotBlank() } ?: return

        runCatching {
            val detail = repository.detail(entry.sourceId, entry.animeUrl).getOrNull()
            val isSeries = detail?.isMovie == false

            val query = SubtitleQuery(
                imdbId = detail?.imdbId,
                tmdbId = detail?.tmdbId,
                // Null for a film, which the catalogue holds as a single entry with no
                // season - sending either field filters every result away.
                season = if (isSeries) episode.season ?: 1 else null,
                episode = if (isSeries) episode.number.takeIf { it >= 0f }?.toInt() else null,
                language = language,
                title = entry.title,
            )
            if (query.isUnusable) return@runCatching

            val results = subtitles.search(query)
            val best = subtitles.bestMatch(results, language) ?: return@runCatching

            val option = subtitles.downloadForOffline(
                result = best,
                targetDir = storage.subtitleDir(entry.volumeId, entry.key),
            ) ?: return@runCatching

            // Re-read rather than reusing the entry captured above: the download may have
            // started, or finished, while the search was in flight.
            val current = entryFor(entry.key) ?: return@runCatching
            store.saveDownload(
                current.copy(subtitlePaths = current.subtitlePaths + option.url),
            )
        }
    }

    /** Stops a download, keeping what is already on disk so it can resume. */
    fun pause(key: String) {
        scope.launch {
            DownloadService.sendSetStopReason(
                context,
                MediaDownloadService::class.java,
                key,
                STOP_REASON_USER,
                /* foreground = */ false,
            )
            entryFor(key)?.let { store.saveDownload(it.copy(state = DownloadState.PAUSED)) }
        }
    }

    /** Resumes a paused download, re-resolving its URL first. */
    /**
     * Resumes a paused download, or restarts one that cannot be resumed.
     *
     * A pause is a stop reason and is lifted by clearing it. A download orphaned by the old
     * URL-based cache key is a different case: its bytes are unreachable, so un-stopping it
     * would resume onto data nothing can read. Those are removed and re-added from scratch,
     * which needs a fresh resolve because the URL it was queued with is long expired.
     */
    fun resume(key: String) {
        scope.launch {
            val entry = entryFor(key) ?: return@launch
            resolver.setActive(entry.sourceId, entry.episodeUrl, entry.streamLabel)

            // Only a progressive download can be stale in this sense. An adaptive one has no
            // custom key by design, so the absence of one is not evidence of anything.
            val stale = !entry.isAdaptive &&
                entry.state == DownloadState.FAILED &&
                runCatching {
                    engine.manager().downloadIndex.getDownload(key)
                }.getOrNull()?.request?.customCacheKey == null

            if (stale) {
                // Cleared first, or the re-added request merges with the unreadable entry
                // already in the index and inherits its key.
                withContext(Dispatchers.Main) {
                    DownloadService.sendRemoveDownload(
                        context,
                        MediaDownloadService::class.java,
                        key,
                        /* foreground = */ false,
                    )
                }
                store.saveDownload(entry.copy(state = DownloadState.QUEUED, sizeBytes = 0L))
                requeue(entry)
                return@launch
            }

            DownloadService.sendSetStopReason(
                context,
                MediaDownloadService::class.java,
                key,
                Download.STOP_REASON_NONE,
                /* foreground = */ false,
            )
            store.saveDownload(entry.copy(state = DownloadState.QUEUED))
        }
    }

    /**
     * Re-queues a download from its stored label, resolving a fresh URL first.
     *
     * Used where the original request cannot be reused - a stale cache key, or a URL that has
     * expired past the point the data source can refresh it.
     */
    private suspend fun requeue(entry: DownloadEntry) {
        val streams = repository.streams(entry.sourceId, entry.episodeUrl).getOrNull()
        val match = streams?.let { matchLabel(it, entry.streamLabel) }

        if (match == null) {
            store.saveDownload(entry.copy(state = DownloadState.FAILED))
            return
        }

        val request = DownloadRequest.Builder(entry.key, android.net.Uri.parse(match.url))
            .setMimeType(
                when {
                    match.isHls -> MimeTypes.APPLICATION_M3U8
                    match.isDash -> MimeTypes.APPLICATION_MPD
                    else -> null
                },
            )
            // Progressive only; see the note in enqueue. Media3 throws on an adaptive stream
            // carrying one.
            .apply {
                if (!match.isHls && !match.isDash) setCustomCacheKey(entry.key)
            }
            .build()

        withContext(Dispatchers.Main) {
            DownloadService.sendAddDownload(
                context,
                MediaDownloadService::class.java,
                request,
                /* foreground = */ false,
            )
        }
    }

    /** Cancels a download and deletes whatever it had written. */
    fun remove(key: String) {
        scope.launch {
            // Volume read before the entry goes, since it names where the sidecar files are.
            val volumeId = entryFor(key)?.volumeId

            DownloadService.sendRemoveDownload(
                context,
                MediaDownloadService::class.java,
                key,
                /* foreground = */ false,
            )
            storage.deleteSubtitles(volumeId, key)
            store.removeDownload(key)
        }
    }

    /** Cancels every download and deletes what they had written. */
    fun removeAll() {
        scope.launch {
            withContext(Dispatchers.Main) {
                DownloadService.sendRemoveAllDownloads(
                    context,
                    MediaDownloadService::class.java,
                    /* foreground = */ false,
                )
            }
        }
    }

    /** Applies the Wi-Fi-only preference to the running queue. */
    fun applyRequirements(wifiOnly: Boolean) {
        val requirements = if (wifiOnly) {
            Requirements(Requirements.NETWORK_UNMETERED)
        } else {
            Requirements(Requirements.NETWORK)
        }
        DownloadService.sendSetRequirements(
            context,
            MediaDownloadService::class.java,
            requirements,
            /* foreground = */ false,
        )
    }

    /**
     * Brings the registry back in line with what is really on disk.
     *
     * Both halves can drift. A crash mid-download leaves an entry the engine has never heard
     * of; a cache cleared by hand leaves an entry whose bytes are gone; a pulled SD card
     * leaves entries that are neither missing nor available. The registry is treated as an
     * index over the engine's index, so where they disagree the engine wins - except for
     * volumes that are absent, which are left alone rather than deleted, because the files
     * come back when the card does.
     */
    private suspend fun reconcile() {
        val manager = engine.manager()
        val known = mutableMapOf<String, Download>()

        manager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val download = cursor.download
                known[download.request.id] = download
            }
        }

        val entries = store.currentDownloads()

        val reconciled = entries.mapNotNull { entry ->
            // Left as it stands. The bytes are on a volume that is not mounted, so the
            // engine legitimately knows nothing about them and deleting the record would
            // lose a download that is merely offline.
            if (!storage.isVolumeAvailable(entry.volumeId)) return@mapNotNull entry

            // Dropped. The volume is mounted and the engine has no record, so whatever was
            // written is gone - a cleared cache, or a crash before the index was flushed -
            // and an entry pointing at nothing is worse than no entry at all.
            val download = known[entry.key] ?: return@mapNotNull null

            // Progressive downloads written before the cache key was stable are unplayable.
            //
            // Those were keyed by the stream URL, and those URLs are signed and expire, so the
            // bytes sit under a key nothing can recompute. No migration is possible - the key
            // cannot be derived from an expired URL - so they are marked failed rather than
            // left looking complete, and the row offers a retry that works instead of playback
            // that cannot.
            //
            // Adaptive downloads are excluded: Media3 forbids a custom key on them, so a null
            // one is correct there and says nothing about whether the download is readable.
            // They are found through their manifest URI instead.
            val adaptive = entry.isAdaptive ||
                download.request.mimeType == MimeTypes.APPLICATION_M3U8 ||
                download.request.mimeType == MimeTypes.APPLICATION_MPD

            val orphaned = download.state == Download.STATE_COMPLETED &&
                !adaptive &&
                download.request.customCacheKey == null

            entry.copy(
                state = if (orphaned) {
                    DownloadState.FAILED
                } else {
                    download.state.toDownloadState()
                },
                sizeBytes = download.bytesDownloaded,
                completedAt = if (download.state == Download.STATE_COMPLETED && !orphaned) {
                    entry.completedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
                } else {
                    entry.completedAt
                },
            )
        }

        store.replaceDownloads(reconciled)
    }

    private suspend fun entryFor(key: String): DownloadEntry? =
        store.currentDownloads().firstOrNull { it.key == key }

    private val listener = object : androidx.media3.exoplayer.offline.DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: androidx.media3.exoplayer.offline.DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            // Progress is owned by the poller above; this only records the transition. The
            // two have very different write costs, and only one of them needs to survive a
            // restart.
            scope.launch {
                val entry = entryFor(download.request.id) ?: return@launch
                val next = download.state.toDownloadState()
                if (entry.state == next && entry.sizeBytes == download.bytesDownloaded) return@launch

                store.saveDownload(
                    entry.copy(
                        state = next,
                        sizeBytes = download.bytesDownloaded,
                        completedAt = if (next == DownloadState.COMPLETED) {
                            System.currentTimeMillis()
                        } else {
                            entry.completedAt
                        },
                    ),
                )
            }
        }

        override fun onDownloadRemoved(
            downloadManager: androidx.media3.exoplayer.offline.DownloadManager,
            download: Download,
        ) {
            _progress.value = _progress.value - download.request.id
        }
    }

    private companion object {
        /** Distinguishes a user pause from the engine stopping for an unmet requirement. */
        const val STOP_REASON_USER = 1

        /**
         * How often a running download's byte count is re-read.
         *
         * Twice a second: fast enough that a progress bar moves rather than steps, slow enough
         * that it costs nothing next to the transfer itself.
         */
        const val ACTIVE_POLL_MS = 500L

        /** Idle interval, so nothing is woken up to ask about an empty queue. */
        const val IDLE_POLL_MS = 2_000L
    }
}

/** Live progress for one running download. */
data class DownloadProgress(
    val percent: Float,
    val bytesDownloaded: Long,
    /**
     * Total size, or zero when it is not knowable.
     *
     * Only a progressive file reports a length up front. A segmented download has no total
     * until its last segment arrives, so the UI falls back to a percentage there rather than
     * printing "1.2 GB of 0 B".
     */
    val totalBytes: Long = 0L,
)

/** Maps Media3's download state onto the registry's. */
@UnstableApi
private fun Int.toDownloadState(): DownloadState = when (this) {
    Download.STATE_QUEUED -> DownloadState.QUEUED
    Download.STATE_DOWNLOADING -> DownloadState.DOWNLOADING
    Download.STATE_STOPPED -> DownloadState.PAUSED
    Download.STATE_COMPLETED -> DownloadState.COMPLETED
    Download.STATE_FAILED -> DownloadState.FAILED
    // RESTARTING and REMOVING are both transient; treating them as queued keeps the UI
    // from flashing a state the user cannot act on.
    else -> DownloadState.QUEUED
}
