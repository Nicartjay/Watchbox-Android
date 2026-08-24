package space.nicart.watchbox.domain

import android.util.Log
import space.nicart.watchbox.data.remote.AniSkipApi
import space.nicart.watchbox.data.remote.ArmApi
import space.nicart.watchbox.data.remote.IntroDbApi
import space.nicart.watchbox.data.remote.SkipInterval

/**
 * Opening and ending timestamps for an episode, when they can be established.
 *
 * Two sources, because each covers what the other cannot:
 *
 *  * **AniSkip**, keyed on a MyAnimeList id, so [ArmApi] converts the TMDB id first. It holds
 *    anime and only anime - a live-action series is never in the mapping table however popular.
 *  * **IntroDB**, keyed on an IMDb id, which TMDB reports for everything. This is what gives a
 *    regular series a skip button at all.
 *
 * AniSkip is asked first for anime because its intervals are judged against the episode's own
 * length, so a submission from a different release is rejected rather than misplaced. IntroDB has
 * no such check, which makes it the better fallback than the better default.
 *
 * Nothing here reports errors upward. A title with no timestamps is the ordinary case rather than
 * a fault, and no skip button is the correct outcome - one placed from a wrong mapping would jump
 * to the wrong point, which is worse than none.
 */
class SkipRepository(
    private val aniSkip: AniSkipApi,
    private val arm: ArmApi,
    private val introDb: IntroDbApi,
) {

    /**
     * Intervals for one episode of [detail], or empty when unavailable.
     *
     * [episodeLengthMs] comes from the player once the stream is prepared. AniSkip uses it to
     * judge whether a submitted interval fits this release, so a wrong value yields nothing
     * rather than misplaced times - which is why this is called after playback starts rather
     * than when the episode is chosen.
     */
    suspend fun skipTimes(
        detail: AnimeDetail?,
        episode: EpisodeEntry?,
        episodeLengthMs: Long,
    ): List<SkipInterval> {
        if (detail == null || episode == null) return emptyList()
        val number = episode.number.toInt().takeIf { it > 0 } ?: return emptyList()

        // A film has no opening to skip, and asking would spend requests to be told so.
        if (detail.isMovie) return emptyList()

        aniSkipTimes(detail, episode, number, episodeLengthMs)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }

        // Only reached for a title AniSkip has nothing for, which is every live-action series and
        // any anime nobody has submitted. Season defaults to 1 rather than bailing: a source that
        // numbers straight through gives no season, and IntroDB indexes by it.
        val imdbId = detail.imdbId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val intervals = introDb.skipTimes(
            imdbId = imdbId,
            season = episode.season ?: 1,
            episodeNumber = number,
        )

        if (intervals.isNotEmpty()) {
            Log.i(TAG, "introdb: $imdbId ep=$number -> ${intervals.size} interval(s)")
        }

        return intervals
    }

    /** AniSkip's intervals for an anime episode, or empty for anything it does not hold. */
    private suspend fun aniSkipTimes(
        detail: AnimeDetail,
        episode: EpisodeEntry,
        number: Int,
        episodeLengthMs: Long,
    ): List<SkipInterval> {
        val tmdbId = detail.tmdbId?.takeIf { it > 0 } ?: return emptyList()
        val malId = arm.malId(tmdbId, episode.season) ?: return emptyList()

        val intervals = aniSkip.skipTimes(
            malId = malId,
            episodeNumber = number,
            episodeLengthSeconds = episodeLengthMs / 1000,
        )

        if (intervals.isNotEmpty()) {
            Log.i(TAG, "aniskip: mal=$malId ep=$number -> ${intervals.size} interval(s)")
        }

        return intervals
    }

    private companion object {
        const val TAG = "SkipRepository"
    }
}
