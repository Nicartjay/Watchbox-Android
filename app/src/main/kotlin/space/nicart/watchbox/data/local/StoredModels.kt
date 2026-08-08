package space.nicart.watchbox.data.local

import kotlinx.serialization.Serializable

/**
 * A resumable playback entry.
 *
 * Schema-compatible with the web app's `watchbox_watch_history` records so the
 * two clients describe the same thing, but consolidated into a **single**
 * definition — the web version declares the storage key in three places
 * (`js/player/api.js:19`, `js/detail/state.js:9`, `js/app.js:244`) and reads or
 * writes it from five, which is how its progress units drifted between an
 * integer percent and a 0..1 fraction.
 *
 * Here [progress] is always a 0f..1f fraction and [positionMs] is authoritative
 * for resuming.
 */
@Serializable
data class WatchHistoryEntry(
    val subjectId: String,
    val detailPath: String,
    val title: String,
    val coverUrl: String? = null,
    val subjectType: Int = 0,
    val season: Int = 1,
    val episode: Int = 1,
    val maxEpisode: Int = 1,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val progress: Float = 0f,
    val serverId: String? = null,
    val updatedAt: Long = 0L,
) {
    /** Treat the last 3% (or final 60s) as finished. */
    val isFinished: Boolean
        get() = progress >= 0.97f ||
            (durationMs > 0 && durationMs - positionMs <= 60_000L && progress > 0.5f)

    val isSeries: Boolean get() = subjectType == 2

    /** Stable identity: one entry per title, not per episode. */
    val key: String get() = subjectId.ifBlank { detailPath }

    companion object {
        const val MAX_ENTRIES = 60
    }
}

/** A saved title ("My List"). */
@Serializable
data class WatchlistEntry(
    val subjectId: String,
    val detailPath: String,
    val title: String,
    val coverUrl: String? = null,
    val subjectType: Int = 0,
    val genre: String = "",
    val imdbRating: String = "",
    val addedAt: Long = 0L,
) {
    val key: String get() = subjectId.ifBlank { detailPath }

    companion object {
        const val MAX_ENTRIES = 300
    }
}
