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
    /**
     * True for HLS or DASH.
     *
     * The two are played back differently offline. A progressive file is one cache entry under
     * the download's own key; an adaptive stream is a manifest plus hundreds of segments, each
     * keyed by its own URL, so it is reopened at [downloadUri] and the cached manifest names
     * the rest. Media3 also refuses a custom cache key on an adaptive download outright.
     */
    val isAdaptive: Boolean = false,
    /**
     * True when the download is a single remuxed file rather than a Media3 cache entry.
     *
     * Set for streams FFmpeg had to fetch, which are the ones served through a proxy inside the
     * extension. Playback reads these straight from [downloadUri] as a plain file - no cache key
     * and no manifest - and they cannot be paused, because an ffmpeg session has no resume point.
     */
    val isRemuxed: Boolean = false,
    /**
     * The URI the download was fetched from.
     *
     * Kept only for adaptive streams, where it is the cache key for the manifest itself. Its
     * signature has long expired, so it is never fetched again - it is an index lookup, and the
     * request is served from the cache. A progressive download does not need it.
     */
    val downloadUri: String = "",
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
    /**
     * Subtitle files fetched alongside the video, as paths relative to the download root.
     *
     * Superseded by [subtitleTracks], which carries the name as well. Kept because entries
     * written before that existed hold only paths, and dropping the field would lose their
     * subtitles entirely. New downloads leave it empty.
     */
    val subtitlePaths: List<String> = emptyList(),
    /**
     * Subtitle files with the name they were downloaded under.
     *
     * The name has to be captured here, at download time, because nothing on disk carries it:
     * a searched file is named by its provider id and a source track by its index, both
     * deliberately, since release names contain characters that are not safe in a path. Read
     * back from the filename the label became a language code at best - and the stored subtitle
     * preference at worst, which is how a track came to be listed as "OFF".
     */
    val subtitleTracks: List<DownloadedSubtitle> = emptyList(),
    val createdAt: Long = 0L,
    val completedAt: Long = 0L,
) {
    /** Unique per episode, unlike the per-title key history uses. */
    val key: String get() = "$sourceId::$animeUrl::$episodeUrl"

    /** The title-level key, for grouping a show's episodes together in the Library. */
    val titleKey: String get() = "$sourceId::$animeUrl"

    val isComplete: Boolean get() = state == DownloadState.COMPLETED

    /**
     * Every saved subtitle, whichever field it was recorded in.
     *
     * Legacy paths are named from what the filename gives up - a language where the source
     * track encoded one, nothing for a searched file named by provider id. Deliberately not
     * migrated on disk: the original names were never written, so a rewrite could not recover
     * them and would only touch the user's files for no gain.
     */
    val allSubtitles: List<DownloadedSubtitle>
        get() = subtitleTracks + subtitlePaths.map(DownloadedSubtitle::fromLegacyPath)

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

/**
 * One subtitle saved with a download.
 *
 * Carries the name it was offered under, so the panel can show what the user actually chose
 * rather than a code recovered from a filename.
 */
@Serializable
data class DownloadedSubtitle(
    /** A `file://` URI on disk. */
    val url: String,
    /**
     * The name this was downloaded under - a release name for a searched file, the source's
     * own track label otherwise. Blank when it could not be recovered from a legacy entry.
     */
    val label: String = "",
    /** ISO code where known, blank otherwise. Never a preference value. */
    val language: String = "",
) {
    companion object {
        /**
         * Best effort for an entry stored before names were kept.
         *
         * A source track was written as `src-<index>-<lang>.<ext>`, so its language survives.
         * A searched file was written as `sub-<id>.<ext>` and gives up nothing, which is
         * why the label may come back blank - the caller shows a generic name for those.
         */
        fun fromLegacyPath(path: String): DownloadedSubtitle {
            val name = path.substringAfterLast('/').substringBeforeLast('.')
            val language = name
                .takeIf { it.startsWith("src-") }
                ?.substringAfterLast('-')
                ?.takeIf { it.isNotBlank() && it != "sub" }
                .orEmpty()

            return DownloadedSubtitle(url = path, label = "", language = language)
        }
    }
}
