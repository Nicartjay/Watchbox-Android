package space.nicart.watchbox.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for reading a season out of an episode name.
 *
 * The extension ABI has no season field: `getEpisodeList` returns one flat list, so for
 * a multi-season show the season only exists inside the name string. Sorting by episode
 * number alone interleaves the seasons - S3E1, S2E1, S1E1, S3E2 - which is what these
 * tests exist to prevent regressing.
 */
class EpisodeSeasonTest {

    @Test
    fun `reads the common leading marker forms`() {
        assertEquals(3, parseSeason("S3 E1 - The Spider and the Fly"))
        assertEquals(1, parseSeason("S1 E1 - Sacrificial Soldiers"))
        assertEquals(2, parseSeason("Season 2 Episode 5"))
        assertEquals(4, parseSeason("s4e12"))
        assertEquals(2, parseSeason("2x01"))
        assertEquals(10, parseSeason("S10 E3"))
    }

    @Test
    fun `is absent when the name carries no season`() {
        assertNull(parseSeason("Episode 12"))
        assertNull(parseSeason("The Spider and the Fly"))
        assertNull(parseSeason(""))
    }

    /**
     * A digit later in the title must not be read as a season. Guessing wrong is worse
     * than not guessing: it splits one season into several, and hides episodes behind a
     * selector tab the user has no reason to open.
     */
    @Test
    fun `ignores numbers that are part of the title`() {
        assertNull(parseSeason("Season of the Witch 2"))
        assertNull(parseSeason("Episode 3 - Serenity"))
        assertNull(parseSeason("Attack on Titan 2"))
    }

    @Test
    fun `sorts by season before episode number`() {
        val episodes = listOf(
            entry("S3 E1", 1f, 3),
            entry("S2 E1", 1f, 2),
            entry("S1 E1", 1f, 1),
            entry("S3 E2", 2f, 3),
            entry("S1 E2", 2f, 1),
        )

        val ordered = episodes.sortedWith(compareBy({ it.season ?: 0 }, { it.number }))

        assertEquals(
            listOf("S1 E1", "S1 E2", "S2 E1", "S3 E1", "S3 E2"),
            ordered.map { it.name },
        )
    }

    /** A source that numbers straight through has no season, and must keep its order. */
    @Test
    fun `leaves seasonless episodes in numeric order`() {
        val episodes = listOf(entry("Episode 3", 3f), entry("Episode 1", 1f))

        val ordered = episodes.sortedWith(compareBy({ it.season ?: 0 }, { it.number }))

        assertEquals(listOf("Episode 1", "Episode 3"), ordered.map { it.name })
    }

    private fun entry(name: String, number: Float, season: Int? = null) = EpisodeEntry(
        url = "u/$name",
        name = name,
        number = number,
        season = season,
        dateUpload = 0L,
        scanlator = null,
    )
}
