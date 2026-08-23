package space.nicart.watchbox.download

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.nicart.watchbox.data.local.DownloadEntry
import space.nicart.watchbox.data.local.DownloadState
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.domain.EpisodeEntry
import space.nicart.watchbox.domain.StreamOption

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
    fun resume(key: String) {
        scope.launch {
            val entry = entryFor(key) ?: return@launch
            resolver.setActive(entry.sourceId, entry.episodeUrl, entry.streamLabel)
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

    /** Cancels a download and deletes whatever it had written. */
    fun remove(key: String) {
        scope.launch {
            DownloadService.sendRemoveDownload(
                context,
                MediaDownloadService::class.java,
                key,
                /* foreground = */ false,
            )
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

            entry.copy(
                state = download.state.toDownloadState(),
                sizeBytes = download.bytesDownloaded,
                completedAt = if (download.state == Download.STATE_COMPLETED) {
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
            // Progress in memory, state transitions on disk. The two have very different
            // write costs, and only one of them needs to survive a restart.
            _progress.value = _progress.value + (
                download.request.id to DownloadProgress(
                    percent = download.percentDownloaded.takeIf { it >= 0f } ?: 0f,
                    bytesDownloaded = download.bytesDownloaded,
                )
                )

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
    }
}

/** Live progress for one running download. */
data class DownloadProgress(
    val percent: Float,
    val bytesDownloaded: Long,
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
