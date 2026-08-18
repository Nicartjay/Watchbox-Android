package space.nicart.watchbox.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import space.nicart.watchbox.data.remote.WikidataRatingApi.Companion.parse

/**
 * Tests for reading the critic score out of a Wikidata claim list.
 *
 * This source is the fallback, used when the primary service 502s - which it does
 * for most requests. It carries only a Tomatometer percentage: it has no audience
 * score at all, and its IMDb and Metacritic figures are no longer shown.
 *
 * The payloads were captured from the live API and trimmed to the fields the parser
 * reads, rather than written by hand. The interesting case - Rotten Tomatoes
 * appearing twice under one qualifier - is not something that would occur to invent.
 *
 * Every failure in here is silent. An earlier reading of the publisher ids had this
 * one transposed with IMDb's, which attributed every figure to the wrong brand while
 * looking entirely correct on screen.
 */
class WikidataRatingApiTest {

    /**
     * Fight Club, as the API returns it.
     *
     * Carries the case that matters: Rotten Tomatoes appears twice under the same
     * qualifier, once as `7.4/10` (the mean critic score) and once as `81%` (the
     * Tomatometer), and only the second is the figure the brand is read by. The
     * other two statements are IMDb and Metacritic, which must be ignored.
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
    fun `reads only the critic meter`() {
        val ratings = parse(fightClub)
        assertEquals(1, ratings.size)
        assertEquals(RatingSource.ROTTEN_TOMATOES, ratings.single().source)
    }

    /**
     * The transposition that made every score wrong. 81% is the Tomatometer; the
     * 8.8/10 in this payload is IMDb's, and reading the publisher ids the other way
     * round attributed it to Rotten Tomatoes.
     */
    @Test
    fun `takes the percentage and not another publisher's figure`() {
        assertEquals("81%", parse(fightClub).single().display)
    }

    /**
     * Rotten Tomatoes' two entries would otherwise both qualify, and whichever came
     * first would win - showing 7.4/10 where every other client shows 81%. Only the
     * percentage maps onto a Fresh or Rotten mark at all.
     */
    @Test
    fun `ignores the mean critic score published under the same qualifier`() {
        assertTrue(parse(fightClub).none { it.display.endsWith("/10") })
    }

    // ------------------------------------------------------------------ state

    /** Their published split: at or above 60% is Fresh, below it is Rotten. */
    @Test
    fun `marks a score above the threshold as fresh`() {
        assertEquals(TomatoState.FRESH, parse(fightClub).single().state)
    }

    @Test
    fun `marks a score below the threshold as rotten`() {
        val body = """
            {"claims":{"P444":[
              {"mainsnak":{"datavalue":{"value":"42%"}},
               "qualifiers":{"P447":[{"datavalue":{"value":{"numeric-id":105584}}}]}}
            ]}}
        """.trimIndent()
        assertEquals(TomatoState.ROTTEN, parse(body).single().state)
    }

    @Test
    fun `treats exactly the threshold as fresh`() {
        val body = """
            {"claims":{"P444":[
              {"mainsnak":{"datavalue":{"value":"60%"}},
               "qualifiers":{"P447":[{"datavalue":{"value":{"numeric-id":105584}}}]}}
            ]}}
        """.trimIndent()
        assertEquals(TomatoState.FRESH, parse(body).single().state)
    }

    /**
     * Never Certified Fresh from this source. That is an award with its own criteria
     * which Wikidata does not record, so inferring it from a high score would claim
     * something untrue.
     */
    @Test
    fun `never reports certified fresh`() {
        val body = """
            {"claims":{"P444":[
              {"mainsnak":{"datavalue":{"value":"98%"}},
               "qualifiers":{"P447":[{"datavalue":{"value":{"numeric-id":105584}}}]}}
            ]}}
        """.trimIndent()
        assertEquals(TomatoState.FRESH, parse(body).single().state)
    }

    /** No count is recorded here, so it is left absent rather than guessed at. */
    @Test
    fun `reports no review count`() {
        assertEquals(null, parse(fightClub).single().voteCount)
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

    /** A score with no publisher cannot be attributed, so it is not shown. */
    @Test
    fun `ignores a score with no publisher qualifier`() {
        val body = """{"claims":{"P444":[{"mainsnak":{"datavalue":{"value":"75%"}}}]}}"""
        assertTrue(parse(body).isEmpty())
    }

    /** Publishers other than Rotten Tomatoes are skipped entirely now. */
    @Test
    fun `ignores another publisher's percentage`() {
        val body = """
            {"claims":{"P444":[
              {"mainsnak":{"datavalue":{"value":"88%"}},
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
               "qualifiers":{"P447":[{"datavalue":{"value":{"numeric-id":105584}}}]}}
            ]}}
        """.trimIndent()
        assertTrue(parse(body).isEmpty())
    }

    /** A percentage that is not a number cannot be classified, so it is dropped. */
    @Test
    fun `ignores an unparseable percentage`() {
        val body = """
            {"claims":{"P444":[
              {"mainsnak":{"datavalue":{"value":"fresh%"}},
               "qualifiers":{"P447":[{"datavalue":{"value":{"numeric-id":105584}}}]}}
            ]}}
        """.trimIndent()
        assertTrue(parse(body).isEmpty())
    }

    // ------------------------------------------------------------ malformed input

    /**
     * The API answers errors as JSON with a different shape, and the value node is an
     * object for some datatypes. Neither may throw: a score is decoration, and losing
     * the page over one would be a poor trade.
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
