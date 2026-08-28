package space.nicart.watchbox.data.local

import kotlinx.serialization.Serializable
import space.nicart.watchbox.domain.AnimeDetail
import space.nicart.watchbox.domain.AnimeStatus
import space.nicart.watchbox.domain.EpisodeEntry

/**
 * A downloaded title's page, stored so it can be opened with the network off.
 *
 * Written when a download starts. Without it a downloaded episode played while its own page
 * failed to load, which made the library look broken in exactly the situation downloads exist
 * for - the episode list is part of the detail, so there was nothing to open the download from.
 *
 * A purpose-built shape rather than a serialised [AnimeDetail]. The domain model carries
 * `Pair` fields, TMDB extras, suggestions and studio logos, none of which are reachable offline
 * and all of which would have to be made serialisable to store it whole. What is kept here is
 * what the page needs to render and navigate.
 */
@Serializable
data class OfflineDetail(
    val sourceId: Long,
    val sourceName: String,
    val url: String,
    val title: String,
    /**
     * Where the artwork was cached on disk, or null when it could not be fetched.
     *
     * A local path rather than the remote URL: the point is to render without a network, and
     * the image loader would otherwise have nothing to read.
     */
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val year: String? = null,
    val rating: Double = 0.0,
    val description: String = "",
    val genres: List<String> = emptyList(),
    /**
     * [AnimeStatus] by name.
     *
     * Stored as a string because the domain enum is not serialisable, and making it so would
     * put a persistence concern in the model every screen uses. An unrecognised name decodes to
     * UNKNOWN, so a renamed constant degrades rather than failing the whole read.
     */
    val statusName: String = AnimeStatus.UNKNOWN.name,
    val episodes: List<OfflineEpisode> = emptyList(),
    val savedAt: Long = 0L,
) {
    val key: String get() = "$sourceId::$url"

    /**
     * Rebuilds enough of an [AnimeDetail] for the page to render.
     *
     * The parts that only exist online - suggestions, TMDB extras, studios - come back empty,
     * which is what their own defaults already mean, so the sections that depend on them simply
     * do not appear.
     */
    fun toDetail(): AnimeDetail = AnimeDetail(
        sourceId = sourceId,
        sourceName = sourceName,
        url = url,
        title = title,
        posterUrl = posterPath,
        backdropUrl = backdropPath,
        year = year,
        rating = rating,
        description = description,
        author = null,
        artist = null,
        genres = genres,
        status = AnimeStatus.entries.firstOrNull { it.name == statusName } ?: AnimeStatus.UNKNOWN,
        episodes = episodes.map { it.toEntry() },
    )

    companion object {
        /**
         * Captures [detail], with artwork already cached to the given paths.
         *
         * The paths are passed in rather than derived, because fetching an image can fail and a
         * stored path that points at nothing is worse than none: the loader would show a broken
         * placeholder instead of falling back.
         */
        fun from(
            detail: AnimeDetail,
            posterPath: String?,
            backdropPath: String?,
            savedAt: Long,
        ): OfflineDetail = OfflineDetail(
            sourceId = detail.sourceId,
            sourceName = detail.sourceName,
            url = detail.url,
            title = detail.title,
            posterPath = posterPath,
            backdropPath = backdropPath,
            year = detail.year,
            rating = detail.rating,
            description = detail.description,
            genres = detail.genres,
            statusName = detail.status.name,
            episodes = detail.episodes.map(OfflineEpisode::from),
            savedAt = savedAt,
        )
    }
}

/** One episode of a cached page. */
@Serializable
data class OfflineEpisode(
    val url: String,
    val name: String,
    val number: Float,
    val season: Int? = null,
    val dateUpload: Long = 0L,
    val tmdbName: String? = null,
    val overview: String = "",
    val rating: Double = 0.0,
    val runtimeMinutes: Int? = null,
    val airDate: String? = null,
) {
    fun toEntry(): EpisodeEntry = EpisodeEntry(
        url = url,
        name = name,
        number = number,
        season = season,
        dateUpload = dateUpload,
        scanlator = null,
        tmdbName = tmdbName,
        overview = overview,
        rating = rating,
        runtimeMinutes = runtimeMinutes,
        airDate = airDate,
    )

    companion object {
        // The still is deliberately dropped: caching one thumbnail per episode would multiply
        // the stored artwork by the length of a series for something shown at card size.
        fun from(entry: EpisodeEntry): OfflineEpisode = OfflineEpisode(
            url = entry.url,
            name = entry.name,
            number = entry.number,
            season = entry.season,
            dateUpload = entry.dateUpload,
            tmdbName = entry.tmdbName,
            overview = entry.overview,
            rating = entry.rating,
            runtimeMinutes = entry.runtimeMinutes,
            airDate = entry.airDate,
        )
    }
}
