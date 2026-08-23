package space.nicart.watchbox.ui.download

import space.nicart.watchbox.data.local.DownloadEntry
import space.nicart.watchbox.data.local.DownloadState
import space.nicart.watchbox.download.DownloadProgress

/**
 * One episode's download status, as the UI needs it.
 *
 * Merges the two halves that hold the truth: the registry knows what a download is and
 * whether it finished, and the engine knows how far a running one has got. Neither alone can
 * answer "what should this button look like".
 */
data class EpisodeDownloadStatus(
    val state: DownloadState,
    /** 0..1, meaningful only while [state] is downloading. */
    val fraction: Float,
    val sizeBytes: Long,
    /** Total size where the server declared one, else zero. Segmented formats report none. */
    val totalBytes: Long = 0L,
    /** True when the file is on a volume that is not currently mounted. */
    val unavailable: Boolean,
) {
    val isActive: Boolean
        get() = state == DownloadState.QUEUED || state == DownloadState.DOWNLOADING
}

/**
 * Builds the per-episode status map the episode list renders from.
 *
 * Keyed by episode URL rather than by the registry's own composite key, because that is what
 * the episode list has in hand for each row.
 */
internal fun buildStatusMap(
    entries: List<DownloadEntry>,
    progress: Map<String, DownloadProgress>,
    sourceId: Long,
    animeUrl: String,
    mountedVolumes: Set<String>,
): Map<String, EpisodeDownloadStatus> = entries
    .filter { it.sourceId == sourceId && it.animeUrl == animeUrl }
    .associate { entry ->
        val live = progress[entry.key]
        entry.episodeUrl to EpisodeDownloadStatus(
            state = entry.state,
            // The live figure where there is one, and the stored percentage otherwise: a
            // download resumed after a restart has real bytes on disk but no listener has
            // reported on it yet, and showing zero would look like it had lost them.
            fraction = ((live?.percent ?: 0f) / 100f).coerceIn(0f, 1f),
            sizeBytes = live?.bytesDownloaded?.takeIf { it > 0 } ?: entry.sizeBytes,
            totalBytes = live?.totalBytes ?: 0L,
            unavailable = entry.volumeId.isNotBlank() && entry.volumeId !in mountedVolumes,
        )
    }

/** `1.4 GB`, `812 MB`, `— ` for nothing. Used wherever a file size is shown. */
fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "%.0f KB".format(bytes / 1_000.0)
}
