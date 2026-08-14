package space.nicart.watchbox.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for skip-interval handling and TMDB-to-MAL season resolution.
 *
 * Pinned because both failure directions are silent. A dropped interval means a button that never
 * appears, which is indistinguishable from "this episode has no data"; a wrongly resolved season
 * means a button that appears and jumps to the wrong place, which reads as a broken player.
 *
 * The HTTP calls themselves are not tested - they need the live services, and their behaviour was
 * verified directly instead: AniSkip answers 404 for an unknown id, 400 for id 0, and
 * `found:false` for an episode with no data; ARM answers an empty array for anything that is not
 * anime, which is what stops a film acquiring skip times.
 */
class SkipIntervalTest {

    private fun interval(startMs: Long, endMs: Long, kind: SkipKind = SkipKind.OPENING) =
        SkipInterval(kind = kind, startMs = startMs, endMs = endMs)

    // ------------------------------------------------------------------ contains

    /** Real values from the live API: Frieren episode 1's opening. */
    @Test
    fun `a position inside the opening is covered`() {
        val opening = interval(34_540L, 124_540L)

        assertTrue(opening.contains(34_540L), "the first frame counts as inside")
        assertTrue(opening.contains(80_000L))
        assertTrue(opening.contains(124_539L))
    }

    /**
     * The end is exclusive. Skipping seeks *to* `endMs`, so treating that instant as inside would
     * leave the button on screen for a frame after it had been used.
     */
    @Test
    fun `the end of the interval is not covered`() {
        assertFalse(interval(34_540L, 124_540L).contains(124_540L))
    }

    @Test
    fun `a position before the opening is not covered`() {
        assertFalse(interval(34_540L, 124_540L).contains(0L))
        assertFalse(interval(34_540L, 124_540L).contains(34_539L))
    }

    @Test
    fun `a position after the opening is not covered`() {
        assertFalse(interval(34_540L, 124_540L).contains(200_000L))
    }

    /** An ending near the end of a 24-minute episode, again from real API values. */
    @Test
    fun `an ending interval works the same way`() {
        val ending = interval(1_330_583L, 1_421_210L, SkipKind.ENDING)

        assertTrue(ending.contains(1_400_000L))
        assertFalse(ending.contains(1_300_000L))
        assertEquals(SkipKind.ENDING, ending.kind)
    }

    // ----------------------------------------------------- picking the active one

    /**
     * The button shows the first interval containing the position. Openings and endings do not
     * overlap in practice, so first-match keeps it single-purpose rather than ambiguous.
     */
    @Test
    fun `the matching interval is selected from a list`() {
        val intervals = listOf(
            interval(34_540L, 124_540L, SkipKind.OPENING),
            interval(1_330_583L, 1_421_210L, SkipKind.ENDING),
        )

        assertEquals(SkipKind.OPENING, intervals.first { it.contains(60_000L) }.kind)
        assertEquals(SkipKind.ENDING, intervals.first { it.contains(1_400_000L) }.kind)
        assertNull(intervals.firstOrNull { it.contains(600_000L) })
    }

    @Test
    fun `an empty interval list never matches`() {
        assertNull(emptyList<SkipInterval>().firstOrNull { it.contains(60_000L) })
    }

    // ------------------------------------------------------- season resolution

    /**
     * Mirrors `ArmApi.malId`. TMDB treats a long show as one entry while MAL splits it per
     * season, so one TMDB id maps to several MAL ids and the season is what tells them apart.
     * These are the real ids for Frieren, which has one TMDB id and two MAL ids.
     */
    private fun resolve(mappings: List<ArmMapping>, season: Int?): Int? {
        if (mappings.isEmpty()) return null
        season?.let { wanted ->
            mappings.firstOrNull { it.season == wanted }?.let { return it.malId }
        }
        return mappings.singleOrNull()?.malId
    }

    @Test
    fun `the season picks the right mal id`() {
        val frieren = listOf(
            ArmMapping(malId = 52991, season = 1),
            ArmMapping(malId = 59978, season = 2),
        )

        assertEquals(52991, resolve(frieren, season = 1))
        assertEquals(59978, resolve(frieren, season = 2))
    }

    /**
     * A source that reports no season still works for a show with one entry — many extensions
     * give no season hint at all.
     */
    @Test
    fun `a single mapping resolves without a season`() {
        assertEquals(32281, resolve(listOf(ArmMapping(malId = 32281, season = null)), season = null))
        assertEquals(52991, resolve(listOf(ArmMapping(malId = 52991, season = 1)), season = null))
    }

    /**
     * The important refusal. With several seasons and no way to tell which, picking arbitrarily
     * would show season one's timestamps on season three — worse than showing no button.
     */
    @Test
    fun `an unknown season among several mappings resolves to nothing`() {
        val several = listOf(
            ArmMapping(malId = 52991, season = 1),
            ArmMapping(malId = 59978, season = 2),
        )

        assertNull(resolve(several, season = null))
        assertNull(resolve(several, season = 7))
    }

    /** No mapping is the ordinary answer for anything that is not anime. */
    @Test
    fun `no mapping resolves to nothing`() {
        assertNull(resolve(emptyList(), season = 1))
        assertNull(resolve(emptyList(), season = null))
    }
}
