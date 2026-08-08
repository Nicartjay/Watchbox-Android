package space.nicart.watchbox.domain

import space.nicart.watchbox.data.model.Subject
import space.nicart.watchbox.data.model.SubjectType
import space.nicart.watchbox.data.remote.TmdbEpisode

/**
 * UI-facing models.
 *
 * The screens never touch DTOs directly: ONEROOM covers are portrait-only and
 * TMDB supplies the backdrops/logos, so a single merged model keeps that fan-in
 * out of the composables.
 */

/** A poster-rail / grid item. */
data class MediaCard(
    val subjectId: String,
    val detailPath: String,
    val title: String,
    val posterUrl: String?,
    val subjectType: Int,
    val year: String?,
    val rating: String?,
    val genres: List<String>,
    val isUpcoming: Boolean,
    val releaseDate: String,
) {
    val isSeries: Boolean get() = subjectType == SubjectType.TV

    /** Secondary line under the poster: `2024 · Drama`. */
    val detailLine: String
        get() = listOfNotNull(year, genres.firstOrNull()).joinToString(" · ")

    companion object {
        fun from(subject: Subject): MediaCard = MediaCard(
            subjectId = subject.subjectId,
            detailPath = subject.detailPath,
            title = subject.title,
            posterUrl = subject.coverUrl,
            subjectType = subject.subjectType,
            year = subject.year,
            rating = subject.imdbRatingValue.takeIf { it.isNotBlank() && it != "0" },
            genres = subject.genres,
            isUpcoming = subject.isUpcoming,
            releaseDate = subject.releaseDate,
        )
    }
}

/** A hero-pager entry. Prefers a TMDB backdrop, falls back to the API banner. */
data class HeroItem(
    val card: MediaCard,
    val backdropUrl: String?,
    val logoUrl: String?,
)

/** A titled horizontal rail on the home screen. */
data class MediaRow(
    val id: String,
    val title: String,
    val items: List<MediaCard>,
)

/** Everything the home screen renders. */
data class HomeContent(
    val hero: List<HeroItem>,
    val rows: List<MediaRow>,
)

/** Fully-merged detail payload. */
data class MediaDetail(
    val subjectId: String,
    val detailPath: String,
    val title: String,
    val overview: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val logoUrl: String?,
    val subjectType: Int,
    val year: String?,
    val runtimeMinutes: Int?,
    val genres: List<String>,
    val country: String,
    val imdbRating: String?,
    val tmdbRating: Double,
    val tmdbId: Int?,
    val isUpcoming: Boolean,
    val releaseDate: String,
    val seasons: List<SeasonSummary>,
    val cast: List<CastMember>,
    val recommendations: List<MediaCard>,
    val dominantColorHex: String?,
) {
    val isSeries: Boolean get() = subjectType == SubjectType.TV

    /** Anime heuristic from the web app: animation genre + Japanese origin. */
    val isAnime: Boolean
        get() = genres.any { it.equals("Animation", true) } &&
            (country.contains("Japan", true) || country.isBlank())

    val metaLine: String
        get() = listOfNotNull(
            year,
            runtimeMinutes?.let { formatRuntime(it) },
            country.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
}

data class SeasonSummary(
    val season: Int,
    val episodeCount: Int,
    val posterUrl: String?,
    val label: String,
)

data class CastMember(
    val name: String,
    val character: String,
    val photoUrl: String?,
)

/** One episode row/card. */
data class EpisodeItem(
    val season: Int,
    val episode: Int,
    val title: String,
    val overview: String,
    val stillUrl: String?,
    val airDate: String?,
    val runtimeMinutes: Int?,
    val rating: Double,
    /** True when only TMDB lists it; the API has no resource for it yet. */
    val tmdbOnly: Boolean = false,
) {
    val code: String get() = "S%02dE%02d".format(season, episode)

    companion object {
        fun from(tmdb: TmdbEpisode, season: Int, tmdbOnly: Boolean): EpisodeItem = EpisodeItem(
            season = season,
            episode = tmdb.episodeNumber,
            title = tmdb.name.ifBlank { "Episode ${tmdb.episodeNumber}" },
            overview = tmdb.overview,
            stillUrl = space.nicart.watchbox.data.remote.TmdbImage.still(tmdb.stillPath),
            airDate = tmdb.airDate,
            runtimeMinutes = tmdb.runtime,
            rating = tmdb.voteAverage,
            tmdbOnly = tmdbOnly,
        )
    }
}

/** A resolved, ready-to-play stream set. */
data class PlaybackSource(
    val serverName: String,
    val serverId: String?,
    val streams: List<PlayableStream>,
    val subtitles: List<PlayableSubtitle>,
    val hosts: List<AlternateHost>,
    val audioTracks: List<AudioTrack>,
    val introRange: ClosedRange<Long>?,
    val outroRange: ClosedRange<Long>?,
) {
    val best: PlayableStream? get() = streams.maxByOrNull { it.height }
}

data class PlayableStream(
    val url: String,
    val label: String,
    val height: Int,
    val isHls: Boolean,
    val streamId: String? = null,
    val format: String = "MP4",
)

data class PlayableSubtitle(
    val url: String,
    val language: String,
    val label: String,
)

data class AlternateHost(val label: String, val url: String, val isHls: Boolean)

data class AudioTrack(
    val language: String,
    val label: String,
    val url: String,
    val isHls: Boolean,
    val subtitles: List<PlayableSubtitle>,
)

// ------------------------------------------------------------------ helpers

fun formatRuntime(minutes: Int): String = when {
    minutes <= 0 -> ""
    minutes < 60 -> "${minutes}m"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

/** `H:MM:SS` past an hour, else `M:SS`. The web CloudStream player gets this wrong. */
fun formatTimecode(millis: Long): String {
    if (millis <= 0L) return "0:00"
    val total = millis / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
