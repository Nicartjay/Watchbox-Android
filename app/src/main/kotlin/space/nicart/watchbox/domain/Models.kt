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
    /**
     * The same backdrop at full resolution, for a full-bleed hero.
     *
     * Separate from [backdropUrl] because that one is w1280 - narrower than the panel a
     * full-screen hero fills, so it upscales visibly. Only the TV home asks for this.
     */
    val heroBackdropUrl: String? = null,
    /** The same backdrop at card size, for landscape cards on TV. */
    val cardBackdropUrl: String? = null,
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

    /**
     * Background for a full-bleed hero: the highest resolution available.
     *
     * Falls back through the smaller transforms so a card whose lookup predates the
     * full-resolution field still shows something rather than nothing.
     */
    val fullBleedImage: String?
        get() = heroBackdropUrl ?: backdropUrl ?: tmdbPosterUrl ?: posterUrl

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
    /** IMDb id, when TMDB matched one. Used to look up subtitles online. */
    val imdbId: String? = null,
    val year: String? = null,
    val rating: Double = 0.0,
    val description: String,
    val author: String?,
    val artist: String?,
    val genres: List<String>,
    val status: AnimeStatus,
    val episodes: List<EpisodeEntry>,
    /** Related-anime suggestions, empty until they have been fetched. */
    val suggestions: List<AnimeCard> = emptyList(),
) {
    val key: String get() = "$sourceId::$url"

    /**
     * True when this is a single-item title rather than a series.
     *
     * Inferred from the episode count rather than a source field, because the ABI has
     * no notion of "movie" - `getEpisodeList` returns one entry for a film and many for
     * a series, and that is the only signal every source agrees on. The player already
     * infers it the same way for cast metadata.
     */
    val isMovie: Boolean get() = episodes.size <= 1

    /** Hero background: TMDB backdrop when available, else the source poster. */
    val heroImage: String? get() = backdropUrl ?: posterUrl

    val metaLine: String
        get() = listOfNotNull(
            year,
            status.label.takeIf { status != AnimeStatus.UNKNOWN },
            genres.firstOrNull(),
            // Omitted for a film: "1 episodes" is both wrong and uninformative.
            "${episodes.size} episodes".takeIf { !isMovie },
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
    /**
     * Season number, or null when the source gives no hint of one.
     *
     * Parsed from the episode name rather than supplied by the extension API:
     * `getEpisodeList` returns one flat list with no season field, and sources that
     * carry several seasons encode it in the name ("S3 E1 - Title").
     */
    val season: Int? = null,
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

    /**
     * Sort key that keeps seasons apart.
     *
     * Episodes with no season sort as season 0 so a source that numbers straight
     * through is left in its own order rather than being shuffled among parsed ones.
     */
    val sortKey: Pair<Int, Float> get() = (season ?: 0) to number

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

data class SubtitleOption(
    val label: String,
    val url: String,
    val language: String,
    /**
     * True for a subtitle the user fetched online rather than one the source supplied.
     *
     * Marked so the player can tell them apart in the panel, and so they can be dropped when
     * the episode changes - an external track is matched to one specific release and is worse
     * than nothing on a different one.
     */
    val isExternal: Boolean = false,
)

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
    season = parseSeason(name),
    dateUpload = date_upload,
    scanlator = scanlator?.takeIf { it.isNotBlank() },
)

/**
 * Pulls a season number out of an episode name.
 *
 * Only the leading marker is considered. Matching anywhere in the string would read
 * the "2" out of a title like "Season of the Witch 2", and a wrong season is worse
 * than none: it splits one season into several tabs.
 */
internal fun parseSeason(name: String): Int? {
    val groups = SEASON_MARKER.find(name.trim())?.groupValues ?: return null
    // Either alternative may have matched, so take whichever group captured.
    return groups.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull()
}

/** `S3 E1 - ...`, `Season 3 Episode 1`, `3x01`. */
private val SEASON_MARKER = Regex(
    """^(?:s(?:eason)?\s*(\d{1,3})(?!\d)|(\d{1,3})\s*x\s*\d+)""",
    RegexOption.IGNORE_CASE,
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
