package space.nicart.watchbox.data.local

import kotlinx.serialization.Serializable

/**
 * A downloaded, or downloading, episode.
 *
 * Identity is `sourceId` + anime `url` + episode `url`, extending the pair the rest of the
 * app keys on. History keeps one entry per title because resuming is a per-title question;
 * a download is inherently per-episode, so the episode has to be part of the key.
 *
 * What is deliberately *not* here is the stream URL or its headers. Extensions sign their
 * URLs and hand back credentials that expire in about two minutes, so a stored URL is a
 * dead link by the time anything reads it. [streamLabel] is kept instead: re-running
 * resolution and matching that label back gets an equivalent stream with a fresh
 * credential, which is what both starting and resuming a download actually need.
 *
 * Byte progress is not here either. It changes several times a second, and every write to
 * this store rewrites the whole preferences blob and re-emits to every collector in the
 * app. Live progress belongs in memory and is read from Media3's own index; only the state
 * transitions below are worth persisting.
 */
@Serializable
data class DownloadEntry(
    val sourceId: Long,
    val animeUrl: String,
    val episodeUrl: String,
    val title: String,
    val episodeName: String = "",
    val episodeNumber: Float = -1f,
    val posterUrl: String? = null,
    val sourceName: String = "",
    /**
     * The server/quality label the stream was chosen by, e.g. `Yoru · 1080p · HLS`.
     *
     * Matched back against a fresh resolve rather than compared for equality, because a
     * source may reorder or re-word its list between calls.
     */
    val streamLabel: String = "",
    /** Which volume the file was written to, so a pulled SD card can be reported. */
    val volumeId: String = "",
    val state: DownloadState = DownloadState.QUEUED,
    /**
     * Size on disk once complete, in bytes.
     *
     * Written at completion rather than tracked: this is what the Settings screen totals
     * and what the Library shows beside each row.
     */
    val sizeBytes: Long = 0L,
    /** Subtitle files fetched alongside the video, as paths relative to the download root. */
    val subtitlePaths: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val completedAt: Long = 0L,
) {
    /** Unique per episode, unlike the per-title key history uses. */
    val key: String get() = "$sourceId::$animeUrl::$episodeUrl"

    /** The title-level key, for grouping a show's episodes together in the Library. */
    val titleKey: String get() = "$sourceId::$animeUrl"

    val isComplete: Boolean get() = state == DownloadState.COMPLETED

    companion object {
        /**
         * Bounded well above what storage allows in practice.
         *
         * At even a gigabyte an episode a phone runs out of room long before this, so the
         * cap exists to stop a runaway registry rather than to limit the user.
         */
        const val MAX_ENTRIES = 500
    }
}

/**
 * Where a download has got to.
 *
 * Mirrors Media3's own download states rather than inventing a parallel vocabulary, so
 * reconciling the registry against its index is a direct mapping.
 */
@Serializable
enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    /** Stopped by the user, or waiting for Wi-Fi. Resumable from what is already on disk. */
    PAUSED,
    COMPLETED,
    FAILED,
}
