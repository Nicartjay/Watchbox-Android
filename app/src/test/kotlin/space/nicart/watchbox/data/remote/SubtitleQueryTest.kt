package space.nicart.watchbox.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for the one place a subtitle search is described.
 *
 * Shared because the player and the download flow had each built this query themselves and
 * drifted: the player repaired the stored language before searching, the download flow passed
 * it through raw. A device whose preference held a track label - the common case, since
 * selecting an embedded track used to store one - got results in the player and "none found"
 * when downloading the very same episode.
 *
 * The repair is the part worth pinning. It is invisible when it works and produces an empty
 * list rather than an error when it does not, which is indistinguishable from a title nobody
 * has subtitled.
 */
class SubtitleQueryTest {

    private fun query(
        imdbId: String? = "tt1234567",
        tmdbId: Int? = 42,
        title: String = "A Show",
        isMovie: Boolean = false,
        season: Int? = 1,
        episodeNumber: Float? = 1f,
        storedLanguage: String = "en",
    ) = SubtitleQuery.forEpisode(
        imdbId = imdbId,
        tmdbId = tmdbId,
        title = title,
        isMovie = isMovie,
        season = season,
        episodeNumber = episodeNumber,
        storedLanguage = storedLanguage,
    )

    // ------------------------------------------------------- the language

    @Test
    fun `passes a code through`() {
        assertEquals("en", SubtitleQuery.normaliseLanguage("en"))
    }

    @Test
    fun `repairs an english language name`() {
        // The bug: a stored "English" was sent as the language and matched nothing.
        assertEquals("en", SubtitleQuery.normaliseLanguage("English"))
    }

    @Test
    fun `repairs a name carrying a region in brackets`() {
        // "Portuguese (Brazil)" also made the URL itself unparseable.
        assertEquals("pt", SubtitleQuery.normaliseLanguage("Portuguese (Brazil)"))
    }

    @Test
    fun `repairs a three letter code`() {
        assertEquals("ja", SubtitleQuery.normaliseLanguage("jpn"))
    }

    @Test
    fun `reduces a region tagged code to its base`() {
        assertEquals("en", SubtitleQuery.normaliseLanguage("en-US"))
    }

    @Test
    fun `falls back to english for something unrecognisable`() {
        // A default that returns results beats one known to return none.
        assertEquals("en", SubtitleQuery.normaliseLanguage("Klingon"))
        assertEquals("en", SubtitleQuery.normaliseLanguage(""))
    }

    @Test
    fun `off is not treated as a language to search for`() {
        // "off" means do not *show* subtitles, which is a playback preference and no reason to
        // refuse to look for a file the user asked to download. It has no code, so it falls
        // back rather than being sent.
        assertEquals("en", SubtitleQuery.normaliseLanguage("off"))
    }

    @Test
    fun `a stored label still yields a searchable query`() {
        // End to end on the reported failure: this returned nothing before.
        val result = query(storedLanguage = "English")

        assertNotNull(result)
        assertEquals("en", result.language)
    }

    // --------------------------------------------------- series and films

    @Test
    fun `a series carries its season and episode`() {
        val result = query(isMovie = false, season = 2, episodeNumber = 5f)

        assertEquals(2, result?.season)
        assertEquals(5, result?.episode)
    }

    @Test
    fun `an unnumbered season defaults to one`() {
        // Sources that do not number seasons still have a first one, and the catalogue indexes
        // it as season 1.
        assertEquals(1, query(season = null)?.season)
    }

    @Test
    fun `a film sends neither season nor episode`() {
        // The catalogue holds a film as a single entry with no season, so sending either field
        // filters every result away.
        val result = query(isMovie = true, season = 1, episodeNumber = 1f)

        assertNull(result?.season)
        assertNull(result?.episode)
    }

    @Test
    fun `a negative episode number is dropped rather than sent`() {
        // -1 is the "unknown" marker, and sending it as an episode matches nothing.
        assertNull(query(episodeNumber = -1f)?.episode)
    }

    @Test
    fun `a fractional episode number truncates`() {
        // 12.5 specials exist; the catalogue indexes whole numbers.
        assertEquals(12, query(episodeNumber = 12.5f)?.episode)
    }

    // ------------------------------------------------------- unsearchable

    @Test
    fun `null when neither id is known`() {
        // Both providers index by id, so there is nothing to ask with. The caller reports this
        // as unsupported rather than as an empty result.
        assertNull(query(imdbId = null, tmdbId = null))
    }

    @Test
    fun `null when the imdb id is blank and there is no tmdb id`() {
        assertNull(query(imdbId = "   ", tmdbId = null))
    }

    @Test
    fun `a tmdb id alone is enough`() {
        assertNotNull(query(imdbId = null, tmdbId = 42))
    }

    @Test
    fun `an imdb id alone is enough`() {
        assertNotNull(query(imdbId = "tt1234567", tmdbId = null))
    }

    @Test
    fun `carries the title for providers that match on it`() {
        assertEquals("A Show", query(title = "A Show")?.title)
    }
}
