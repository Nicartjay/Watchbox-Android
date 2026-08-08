package space.nicart.watchbox.domain

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video

/**
 * UI-facing models.
 *
 * Screens never touch the extension types directly. Those are mutable
 * `java.io.Serializable` interfaces with snake_case fields, shaped by the
 * extension ABI rather than by anything Compose wants, and a source may hand
 * back the same instance twice with different contents. Mapping to immutable
 * values here keeps recomposition predictable and stops ABI concerns leaking
 * into the UI.
 *
 * The `sourceId` on each model is what makes an entry re-openable: it survives
 * the extension being uninstalled and reinstalled.
 */

/** A poster-rail or grid entry. */
data class AnimeCard(
    val sourceId: Long,
    /** Source-relative path; unique within a source. */
    val url: String,
    val title: String,
    val posterUrl: String?,
    val sourceName: String = "",
) {
    /** Stable identity across sources. */
    val key: String get() = "$sourceId::$url"
}

/** One horizontal rail on the home screen, backed by a single source. */
data class AnimeRow(
    val sourceId: Long,
    val sourceName: String,
    val title: String,
    val items: List<AnimeCard>,
    /** True when this rail came from `getLatestUpdates` rather than popular. */
    val isLatest: Boolean = false,
)

/** Home screen payload. */
data class HomeFeed(
    val hero: List<AnimeCard>,
    val rows: List<AnimeRow>,
)

/** Fully-resolved detail for one title. */
data class AnimeDetail(
    val sourceId: Long,
    val sourceName: String,
    val url: String,
    val title: String,
    val posterUrl: String?,
    val description: String,
    val author: String?,
    val artist: String?,
    val genres: List<String>,
    val status: AnimeStatus,
    val episodes: List<EpisodeEntry>,
) {
    val key: String get() = "$sourceId::$url"

    val metaLine: String
        get() = listOfNotNull(
            status.label.takeIf { status != AnimeStatus.UNKNOWN },
            genres.firstOrNull(),
            "${episodes.size} episodes".takeIf { episodes.isNotEmpty() },
        ).joinToString(" · ")
}

enum class AnimeStatus(val label: String) {
    UNKNOWN("Unknown"),
    ONGOING("Ongoing"),
    COMPLETED("Completed"),
    LICENSED("Licensed"),
    PUBLISHING_FINISHED("Finished"),
    CANCELLED("Cancelled"),
    ON_HIATUS("On hiatus"),
    ;

    companion object {
        fun from(raw: Int): AnimeStatus = when (raw) {
            SAnime.ONGOING -> ONGOING
            SAnime.COMPLETED -> COMPLETED
            SAnime.LICENSED -> LICENSED
            SAnime.PUBLISHING_FINISHED -> PUBLISHING_FINISHED
            SAnime.CANCELLED -> CANCELLED
            SAnime.ON_HIATUS -> ON_HIATUS
            else -> UNKNOWN
        }
    }
}

/** One episode. */
data class EpisodeEntry(
    val url: String,
    val name: String,
    val number: Float,
    val dateUpload: Long,
    val scanlator: String?,
) {
    /**
     * Display label. Sources are inconsistent about whether `name` already
     * contains the episode number, so a bare number is only prefixed when the
     * name does not obviously carry one.
     */
    val displayName: String
        get() = when {
            name.isBlank() && number >= 0 -> "Episode ${number.tidy()}"
            name.isBlank() -> "Episode"
            else -> name
        }

    val numberLabel: String? get() = number.takeIf { it >= 0 }?.tidy()
}

/** A playable stream plus its tracks. */
data class StreamOption(
    val label: String,
    val url: String,
    val headers: Map<String, String>,
    val subtitles: List<SubtitleOption>,
    val audioTracks: List<SubtitleOption>,
    val resolution: Int,
) {
    val isHls: Boolean get() = url.contains(".m3u8", ignoreCase = true)
}

data class SubtitleOption(val label: String, val url: String, val language: String)

// ------------------------------------------------------------------ mapping

internal fun SAnime.toCard(source: AnimeCatalogueSource): AnimeCard = AnimeCard(
    sourceId = source.id,
    url = url,
    title = title.ifBlank { "Untitled" },
    posterUrl = thumbnail_url?.takeIf { it.isNotBlank() },
    sourceName = source.name,
)

internal fun SEpisode.toEntry(): EpisodeEntry = EpisodeEntry(
    url = url,
    name = name,
    number = episode_number,
    dateUpload = date_upload,
    scanlator = scanlator?.takeIf { it.isNotBlank() },
)

internal fun Video.toStreamOption(): StreamOption {
    val resolved = videoUrl ?: url
    return StreamOption(
        label = quality.ifBlank { "Default" },
        url = resolved,
        headers = headers?.let { h ->
            (0 until h.size).associate { h.name(it) to h.value(it) }
        }.orEmpty(),
        subtitles = subtitleTracks.map {
            SubtitleOption(
                label = it.lang.ifBlank { "Subtitle" },
                url = it.url,
                language = it.lang,
            )
        },
        audioTracks = audioTracks.map {
            SubtitleOption(
                label = it.lang.ifBlank { "Audio" },
                url = it.url,
                language = it.lang,
            )
        },
        resolution = resolutionOrZero,
    )
}

/** Drops a trailing `.0` so "Episode 12.0" reads as "Episode 12". */
private fun Float.tidy(): String =
    if (this == toLong().toFloat()) toLong().toString() else toString()

/** `H:MM:SS` past an hour, else `M:SS`. */
fun formatTimecode(millis: Long): String {
    if (millis <= 0L) return "0:00"
    val total = millis / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
