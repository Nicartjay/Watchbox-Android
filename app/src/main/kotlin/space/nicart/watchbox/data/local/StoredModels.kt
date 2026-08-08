package space.nicart.watchbox.data.local

import kotlinx.serialization.Serializable

/**
 * A resumable playback entry.
 *
 * Identity is `sourceId` + anime `url`, because that is the only pair an
 * extension guarantees to be stable. Titles change between fetches and there is
 * no global id in this ecosystem.
 *
 * [progress] is always a 0f..1f fraction and [positionMs] is authoritative for
 * resuming; storing both avoids the rounding drift that comes from keeping only
 * a percentage.
 */
@Serializable
data class WatchHistoryEntry(
    val sourceId: Long,
    val animeUrl: String,
    val title: String,
    val posterUrl: String? = null,
    val sourceName: String = "",
    val episodeUrl: String = "",
    val episodeName: String = "",
    val episodeNumber: Float = -1f,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val progress: Float = 0f,
    val updatedAt: Long = 0L,
) {
    /** Treat the last 3% — or the final minute — as finished. */
    val isFinished: Boolean
        get() = progress >= 0.97f ||
            (durationMs > 0 && durationMs - positionMs <= 60_000L && progress > 0.5f)

    /** One entry per title, not per episode. */
    val key: String get() = "$sourceId::$animeUrl"

    val episodeLabel: String
        get() = when {
            episodeName.isNotBlank() -> episodeName
            episodeNumber >= 0 -> "Episode ${episodeNumber.tidy()}"
            else -> ""
        }

    companion object {
        const val MAX_ENTRIES = 60
    }
}

/** A saved title ("My List"). */
@Serializable
data class WatchlistEntry(
    val sourceId: Long,
    val animeUrl: String,
    val title: String,
    val posterUrl: String? = null,
    val sourceName: String = "",
    val addedAt: Long = 0L,
) {
    val key: String get() = "$sourceId::$animeUrl"

    companion object {
        const val MAX_ENTRIES = 300
    }
}

private fun Float.tidy(): String =
    if (this == toLong().toFloat()) toLong().toString() else toString()
