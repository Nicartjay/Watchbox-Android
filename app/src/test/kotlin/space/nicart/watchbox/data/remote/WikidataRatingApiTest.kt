package space.nicart.watchbox.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import space.nicart.watchbox.data.remote.WikidataRatingApi.Companion.parse

/**
 * Tests for reading external scores out of a Wikidata claim list.
 *
 * The payloads here were captured from the live API and trimmed to the fields the
 * parser reads, rather than written by hand: the statement shape is deeply nested
 * and the interesting cases - two Rotten Tomatoes entries under one qualifier, a
 * publisher with no English label - are things that would not occur to invent.
 *
 * Every failure in here is silent. A mislabelled score still renders as a score,
 * and an earlier reading of the publisher ids had IMDb and Rotten Tomatoes
 * transposed, which attributed every figure to the wrong brand while looking
 * entirely correct on screen.
 */
class WikidataRatingApiTest {

    /**
     * Fight Club, as the API returns it.
     *
     * Carries the case that matters: Rotten Tomatoes appears twice under the same
     * qualifier, once as `7.4/10` (the mean critic score) and once as `81%` (the
     * Tomatometer), and only the second is the figure the brand is read by.
     */
    private val fightClub = """
        {"claims":{"P444":[
          {"mainsnak":{"datavalue":{"value":"7.4/10"}},
           "qualifiers":{"P447":[{"datavalue":{"value":{"numeric-id":105584}}}]}},
          {"mainsnak":{"datavalue":{"value":"81%"}},
           "qualifiers":{"P447":[{"datavalue":{"value":{"numeric-id":105584}}}]}},
          {"mainsnak":{"datavalue":{"value":"67/100"}},
           "qualifiers":{"P447":[{"datavalue":{"value":{"numeric-id":150248}}}]}},
          {"mainsnak":{"datavalue":{"value":"8.8/10"}},
           "qualifiers":{"P447":[{"datavalue":{"value":{"numeric-id":37312}}}]}}
        ]}}
    """.trimIndent()

    @Test
    fun `reads one score per publisher`() {
        val ratings = parse(fightClub)
        assertEquals(3, ratings.size)
        assertEquals(
            listOf(RatingSource.IMDB, RatingSource.ROTTEN_TOMATOES, RatingSource.METACRITIC),
            ratings.map { it.source },
        )
    }

    /** The transposition that made every score wrong: 8.8/10 is IMDb, 81% is RT. */
    @Test
    fun `attributes each score to the right publisher`() {
        val bySource = parse(fightClub).associate { it.source to it.display }
        assertEquals("8.8/10", bySource[RatingSource.IMDB])
        assertEquals("81%", bySource[RatingSource.ROTTEN_TOMATOES])
        assertEquals("67/100", bySource[RatingSource.METACRITIC])
    }

    /**
     * Rotten Tomatoes' two entries would otherwise both qualify, and whichever came
     * first would win - showing `RT 7.4/10` where every other client shows `81%`.
     */
    @Test
    fun `keeps the percentage for Rotten Tomatoes and drops the mean`() {
        val rt = parse(fightClub).single { it.source == RatingSource.ROTTEN_TOMATOES }
        assertTrue(rt.display.endsWith("%"), "expected a percentage, got ${rt.display}")
    }

    /** Units are kept: the three scales do not compare, so a bare number would mislead. */
    @Test
    fun `keeps the units each publisher reports in`() {
        val displays = parse(fightClub).map { it.display }
        assertTrue(displays.any { it.endsWith("/10") })
        assertTrue(displays.any { it.endsWith("%") })
        assertTrue(displays.any { it.endsWith("/100") })
    }

    /**
     * Fixed output order, so the row does not reshuffle between titles.
     *
     * Oppenheimer's statements are stored Metacritic-first, where Fight Club's are
     * Rotten-Tomatoes-first; without an explicit order the two pages would present
     * the same three scores in different positions.
     */
    @Test
    fun `orders scores consistently regardless of statement order`() {
        val reordered = """
            {"claims":{"P444":[
              {"mainsnak":{"datavalue":{"value":"90/100"}},
               "qualifiers":{"P447":[{"datavalue":{"value":{"numeric-id":150248}}}]}},
              {"mainsnak":{"datavalue":{"value":"93%"}},
               "qualifiers":{"P447":[{"datavalue":{"value":{"numeric-id":105584}}}]}},
              {"mainsnak":{"datavalue":{"value":"8.2/10"}},
               "qualifiers":{"P447":[{"datavalue":{"value":{"numeric-id":37312}}}]}}
            ]}}
        """.trimIndent()
        assertEquals(
            listOf(RatingSource.IMDB, RatingSource.ROTTEN_TOMATOES, RatingSource.METACRITIC),
            parse(reordered).map { it.source },
        )
    }

    // ------------------------------------------------------------ absent data

    /**
     * The ordinary answer for television. Measured over eight titles, Breaking Bad,
     * Arcane and One Piece all return this, so it has to read as "no row" rather
     * than as a failure.
     */
    @Test
    fun `returns nothing when the entity has no scores`() {
        assertTrue(parse("""{"claims":{}}""").isEmpty())
    }

    @Test
    fun `returns nothing when the property is absent`() {
        assertTrue(parse("""{"claims":{"P345":[]}}""").isEmpty())
    }

    /** A score with no publisher cannot be labelled, so it is not shown. */
    @Test
    fun `ignores a score with no publisher qualifier`() {
        val body = """{"claims":{"P444":[{"mainsnak":{"datavalue":{"value":"75%"}}}]}}"""
        assertTrue(parse(body).isEmpty())
    }

    /** Publishers outside the three the app renders - Letterboxd, AlloCiné - are skipped. */
    @Test
    fun `ignores an unrecognised publisher`() {
        val body = """
            {"claims":{"P444":[
              {"mainsnak":{"datavalue":{"value":"4.1/5"}},
               "qualifiers":{"P447":[{"datavalue":{"value":{"numeric-id":999999}}}]}}
            ]}}
        """.trimIndent()
        assertTrue(parse(body).isEmpty())
    }

    @Test
    fun `ignores a blank score`() {
        val body = """
            {"claims":{"P444":[
              {"mainsnak":{"datavalue":{"value":"   "}},
               "qualifiers":{"P447":[{"datavalue":{"value":{"numeric-id":37312}}}]}}
            ]}}
        """.trimIndent()
        assertTrue(parse(body).isEmpty())
    }

    // ------------------------------------------------------------ malformed input

    /**
     * The API answers errors as JSON with a different shape, and the value node is
     * an object for some datatypes. Neither may throw: a score is decoration, and
     * losing the page over one would be a poor trade.
     */
    @Test
    fun `survives an error response`() {
        assertTrue(parse("""{"error":{"code":"no-such-entity"}}""").isEmpty())
    }

    @Test
    fun `survives a non-primitive score value`() {
        val body = """
            {"claims":{"P444":[
              {"mainsnak":{"datavalue":{"value":{"amount":"+81"}}},
               "qualifiers":{"P447":[{"datavalue":{"value":{"numeric-id":105584}}}]}}
            ]}}
        """.trimIndent()
        assertTrue(parse(body).isEmpty())
    }
}
