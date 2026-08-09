package space.nicart.watchbox.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the movie-versus-series distinction on a detail page.
 *
 * The extension ABI has no notion of "movie": `getEpisodeList` returns one entry for a
 * film and many for a series, so the episode count is the only signal every source
 * agrees on. Pinning that inference here matters because it is the sort of thing that
 * gets "tidied" into a source field that does not exist.
 */
class AnimeDetailMovieTest {

    private fun detail(episodeCount: Int) = AnimeDetail(
        sourceId = 1L,
        sourceName = "Test",
        url = "/title",
        title = "Test Title",
        posterUrl = null,
        description = "",
        author = null,
        artist = null,
        genres = listOf("Horror"),
        status = AnimeStatus.COMPLETED,
        year = "2026",
        episodes = List(episodeCount) { index ->
            EpisodeEntry(
                url = "/ep$index",
                name = "Episode $index",
                number = (index + 1).toFloat(),
                dateUpload = 0L,
                scanlator = null,
            )
        },
    )

    // ------------------------------------------------------------- inference

    @Test
    fun `a single entry is a movie`() {
        assertTrue(detail(episodeCount = 1).isMovie)
    }

    @Test
    fun `two or more entries is a series`() {
        assertFalse(detail(episodeCount = 2).isMovie)
        assertFalse(detail(episodeCount = 24).isMovie)
    }

    @Test
    fun `a title with no entries counts as a movie`() {
        // A source that failed to return episodes should not render an empty episode
        // section; the Play button still resolves through startTarget.
        assertTrue(detail(episodeCount = 0).isMovie)
    }

    // -------------------------------------------------------------- meta line

    @Test
    fun `a movie does not advertise an episode count`() {
        // "1 episodes" is both ungrammatical and uninformative.
        val meta = detail(episodeCount = 1).metaLine
        assertFalse(meta.contains("episode", ignoreCase = true), "meta was: $meta")
    }

    @Test
    fun `a series still shows its episode count`() {
        val meta = detail(episodeCount = 12).metaLine
        assertTrue(meta.contains("12 episodes"), "meta was: $meta")
    }

    @Test
    fun `the meta line keeps the other fields for a movie`() {
        // Removing the count must not strip year, status or genre with it.
        val meta = detail(episodeCount = 1).metaLine
        assertTrue(meta.contains("2026"))
        assertTrue(meta.contains("Completed"))
        assertTrue(meta.contains("Horror"))
    }

    @Test
    fun `an empty title reports no episode count either`() {
        val meta = detail(episodeCount = 0).metaLine
        assertFalse(meta.contains("episode", ignoreCase = true), "meta was: $meta")
        assertEquals(true, meta.isNotBlank(), "the line should still carry year and status")
    }
}
