package space.nicart.watchbox.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for reading a TMDB id out of a source entry URL.
 *
 * Some extensions are TMDB front-ends and name their entries by id
 * (`/movie/1719380`). Enriching those by title instead throws the exact answer away
 * and guesses, which is how a card ends up showing another title's poster: several
 * unrelated entries share a name, and TMDB's search order has nothing to do with
 * which one the source meant.
 *
 * The false-positive cases matter more than the positive ones. This runs against
 * *every* installed source, and reading an unrelated path number as a TMDB id would
 * attach confidently wrong artwork - worse than a fuzzy title match, because a wrong
 * id is never reconsidered while a failed search still falls back.
 */
class TmdbUrlRefTest {

    @Test
    fun `reads the id and type from a bare path`() {
        assertEquals(1719380 to TmdbType.MOVIE, TmdbApi.parseTmdbRef("/movie/1719380"))
        assertEquals(94997 to TmdbType.TV, TmdbApi.parseTmdbRef("/tv/94997"))
    }

    @Test
    fun `accepts an absolute url, a trailing slash and a query`() {
        assertEquals(
            603 to TmdbType.MOVIE,
            TmdbApi.parseTmdbRef("https://www.cineby.at/movie/603"),
        )
        assertEquals(603 to TmdbType.MOVIE, TmdbApi.parseTmdbRef("/movie/603/"))
        assertEquals(603 to TmdbType.MOVIE, TmdbApi.parseTmdbRef("/movie/603?lang=en"))
        assertEquals(603 to TmdbType.MOVIE, TmdbApi.parseTmdbRef("/movie/603#top"))
    }

    @Test
    fun `is case insensitive on the type segment`() {
        assertEquals(1 to TmdbType.MOVIE, TmdbApi.parseTmdbRef("/Movie/1"))
        assertEquals(1 to TmdbType.TV, TmdbApi.parseTmdbRef("/TV/1"))
    }

    /**
     * The common case by far: most extensions use slugs, not TMDB ids. These must
     * parse to null so the title search still runs untouched.
     */
    @Test
    fun `ignores the url schemes other extensions use`() {
        assertNull(TmdbApi.parseTmdbRef("/anime/attack-on-titan"))
        assertNull(TmdbApi.parseTmdbRef("/series/frieren/episode-1"))
        assertNull(TmdbApi.parseTmdbRef("https://example.com/watch?id=abc123"))
        assertNull(TmdbApi.parseTmdbRef("/title/tt0111161"))
        assertNull(TmdbApi.parseTmdbRef(""))
    }

    /**
     * A `/movie/` or `/tv/` segment that is not the whole path says nothing about
     * TMDB: the number belongs to the source's own scheme, and TMDB has no reason
     * to agree with it.
     */
    @Test
    fun `rejects a type segment that is not the whole path`() {
        assertNull(TmdbApi.parseTmdbRef("/anime/movie/123"))
        assertNull(TmdbApi.parseTmdbRef("/movie/123/season/2"))
        assertNull(TmdbApi.parseTmdbRef("/movie/123/episode/4"))
        assertNull(TmdbApi.parseTmdbRef("/catalog/tv/456"))
    }

    @Test
    fun `rejects a non-numeric or absent id`() {
        assertNull(TmdbApi.parseTmdbRef("/movie/"))
        assertNull(TmdbApi.parseTmdbRef("/movie/abc"))
        assertNull(TmdbApi.parseTmdbRef("/movie/12a"))
        assertNull(TmdbApi.parseTmdbRef("/tv"))
    }

    /**
     * Zero is not a TMDB id, and an overlong digit run would overflow an Int and
     * wrap to something arbitrary. Both must be refused rather than turned into a
     * lookup for the wrong entry.
     */
    @Test
    fun `rejects zero and an overlong id`() {
        assertNull(TmdbApi.parseTmdbRef("/movie/0"))
        assertNull(TmdbApi.parseTmdbRef("/movie/00"))
        assertNull(TmdbApi.parseTmdbRef("/movie/99999999999999999999"))
    }

    /** Sources are not careful about stray whitespace. */
    @Test
    fun `tolerates surrounding whitespace`() {
        assertEquals(603 to TmdbType.MOVIE, TmdbApi.parseTmdbRef("  /movie/603  "))
    }
}
