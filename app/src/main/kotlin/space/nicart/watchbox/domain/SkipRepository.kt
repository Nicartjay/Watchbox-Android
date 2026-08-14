package space.nicart.watchbox.domain

import android.util.Log
import space.nicart.watchbox.data.remote.AniSkipApi
import space.nicart.watchbox.data.remote.ArmApi
import space.nicart.watchbox.data.remote.SkipInterval

/**
 * Opening and ending timestamps for an episode, when they can be established.
 *
 * Two hops, because AniSkip is keyed on a MyAnimeList id and this app has only a TMDB one:
 * [ArmApi] converts the id, then [AniSkipApi] returns the intervals. Either hop failing means no
 * skip button, which is the correct outcome - the feature is a convenience, and a button placed
 * from a wrong mapping would jump to the wrong point.
 *
 * Nothing here reports errors upward. A title with no mapping is the ordinary case rather than a
 * fault: only anime is in the mapping table at all, and a film or live-action series will never
 * have skip times.
 */
class SkipRepository(
    private val aniSkip: AniSkipApi,
    private val arm: ArmApi,
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
        val tmdbId = detail?.tmdbId?.takeIf { it > 0 } ?: return emptyList()
        val number = episode?.number?.toInt()?.takeIf { it > 0 } ?: return emptyList()

        // A film has no opening to skip, and asking would spend two requests to be told so.
        if (detail.isMovie) return emptyList()

        val malId = arm.malId(tmdbId, episode.season) ?: return emptyList()

        val intervals = aniSkip.skipTimes(
            malId = malId,
            episodeNumber = number,
            episodeLengthSeconds = episodeLengthMs / 1000,
        )

        if (intervals.isNotEmpty()) {
            Log.i(TAG, "skip times for mal=$malId ep=$number: ${intervals.size} interval(s)")
        }

        return intervals
    }

    private companion object {
        const val TAG = "SkipRepository"
    }
}
