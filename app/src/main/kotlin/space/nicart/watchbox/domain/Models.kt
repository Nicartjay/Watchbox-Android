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
    /** The extension's own poster. Always present; used as the fallback. */
    val posterUrl: String?,
    val sourceName: String = "",
    /** Wide TMDB backdrop, for the hero. Null when no match was found. */
    val backdropUrl: String? = null,
    /** Transparent TMDB title logo, for the hero. */
    val logoUrl: String? = null,
    /** TMDB poster, generally cleaner than the source's own. */
    val tmdbPosterUrl: String? = null,
    val tmdbId: Int? = null,
    val year: String? = null,
    val genres: List<String> = emptyList(),
) {
    /** Stable identity across sources. */
    val key: String get() = "$sourceId::$url"

    /** Poster for rails and grids: prefer TMDB, fall back to the source. */
    val displayPoster: String? get() = tmdbPosterUrl ?: posterUrl

    /** Hero background: a wide backdrop if we have one, else the poster. */
    val heroImage: String? get() = backdropUrl ?: tmdbPosterUrl ?: posterUrl

    /** `2024 · Action`, matching Nuvio's hero meta line. */
    val metaLine: String
        get() = listOfNotNull(year, genres.firstOrNull(), sourceName.takeIf { it.isNotBlank() })
            .joinToString(" · ")
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
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val tmdbId: Int? = null,
    val year: String? = null,
    val rating: Double = 0.0,
    val description: String,
    val author: String?,
    val artist: String?,
    val genres: List<String>,
    val status: AnimeStatus,
    val episodes: List<EpisodeEntry>,
) {
    val key: String get() = "$sourceId::$url"

    /** Hero background: TMDB backdrop when available, else the source poster. */
    val heroImage: String? get() = backdropUrl ?: posterUrl

    val metaLine: String
        get() = listOfNotNull(
            year,
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
    /** TMDB still, so episode cards can be thumbnails rather than text rows. */
    val stillUrl: String? = null,
    /** TMDB episode title, used when the source only supplies "Episode 12". */
    val tmdbName: String? = null,
    val overview: String = "",
    val rating: Double = 0.0,
    val runtimeMinutes: Int? = null,
    val airDate: String? = null,
) {
    /**
     * Display label. Sources are inconsistent about whether `name` already
     * contains the episode number, so a bare number is only prefixed when the
     * name does not obviously carry one.
     */
    val displayName: String
        get() = when {
            // A source name like "Episode 12" carries no information TMDB does
            // not already have, so prefer the real title when we have one.
            !tmdbName.isNullOrBlank() && looksGeneric -> tmdbName
            name.isNotBlank() -> name
            !tmdbName.isNullOrBlank() -> tmdbName
            number >= 0 -> "Episode ${number.tidy()}"
            else -> "Episode"
        }

    /** True when the source's own name is just an episode number. */
    private val looksGeneric: Boolean
        get() = name.isBlank() || GENERIC_NAME.matches(name.trim())

    val code: String get() = number.takeIf { it >= 0 }?.let { "E${it.tidy()}" } ?: ""

    val numberLabel: String? get() = number.takeIf { it >= 0 }?.tidy()

    private companion object {
        val GENERIC_NAME = Regex("""(?i)^(episode|ep\.?|cap[íi]tulo)?\s*\d+(\.\d+)?$""")
    }
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

/** Overlays TMDB artwork onto an episode, leaving source fields authoritative. */
internal fun EpisodeEntry.withArt(
    art: space.nicart.watchbox.data.remote.TmdbEpisodeArt?,
): EpisodeEntry {
    if (art == null) return this
    return copy(
        stillUrl = art.stillUrl,
        tmdbName = art.name.takeIf { it.isNotBlank() },
        overview = art.overview,
        rating = art.rating,
        runtimeMinutes = art.runtimeMinutes,
        airDate = art.airDate,
    )
}

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

/** `1h 24m` / `24m`. Used for episode runtimes from TMDB. */
fun formatRuntime(minutes: Int): String = when {
    minutes <= 0 -> ""
    minutes < 60 -> "${minutes}m"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

/** `H:MM:SS` past an hour, else `M:SS`. */
fun formatTimecode(millis: Long): String {
    if (millis <= 0L) return "0:00"
    val total = millis / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
