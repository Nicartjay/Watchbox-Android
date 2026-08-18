package space.nicart.watchbox.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import space.nicart.watchbox.data.remote.SheguRatingApi.Companion.parse

/**
 * Tests for reading external scores from the info service.
 *
 * The payload here was captured from the live endpoint. Its shape is undocumented
 * and the route is unstable - measured over ten titles it answered three and 502'd
 * for seven - so the property under test throughout is that a body this does not
 * understand yields an empty list rather than an exception. An empty list means the
 * caller falls through to the fallback source and the page is unaffected.
 */
class SheguRatingApiTest {

    /** Fight Club, exactly as the service answered it. */
    private val fightClub = """
        {"tmdb_id":550,"imdb_id":"tt0137523","type":"movie","title":"Fight Club","year":1999,
         "imdb_rating":"8.8","tomato_meter":81,"tomato_meter_count":251,
         "tomato_meter_state":"none","audience_score":96,"audience_score_count":73860,
         "content_rating":"R","runtime":139}
    """.trimIndent()

    @Test
    fun `reads both meters from a live payload`() {
        val ratings = parse(fightClub)
        assertEquals(
            listOf(RatingSource.ROTTEN_TOMATOES, RatingSource.POPCORNMETER),
            ratings.map { it.source },
        )
    }

    /**
     * The service also reports an IMDb figure, which is deliberately dropped: TMDB's
     * own score is already on the page, and a second number out of ten beside it
     * reads as a duplicate rather than as a second opinion.
     */
    @Test
    fun `ignores the imdb score the service reports`() {
        assertTrue(parse(fightClub).none { it.display.endsWith("/10") })
    }

    /** Both meters are shares of favourable reviews, so the percent sign is kept. */
    @Test
    fun `formats each score as a percentage`() {
        val bySource = parse(fightClub).associate { it.source to it.display }
        assertEquals("81%", bySource[RatingSource.ROTTEN_TOMATOES])
        assertEquals("96%", bySource[RatingSource.POPCORNMETER])
    }

    @Test
    fun `carries the review counts it reports`() {
        val bySource = parse(fightClub).associateBy { it.source }
        assertEquals(251, bySource[RatingSource.ROTTEN_TOMATOES]?.voteCount)
        assertEquals(73860, bySource[RatingSource.POPCORNMETER]?.voteCount)
    }

    // --------------------------------------------------------- tomato states

    /**
     * The trap in this payload: `tomato_meter_state` reads `"none"` on a title
     * scoring 81%, so it means "not certified" rather than "no score". Reading it as
     * the latter would show a faded tomato beside a Fresh score.
     */
    @Test
    fun `treats a none state as fresh when the score is above the threshold`() {
        val rt = parse(fightClub).single { it.source == RatingSource.ROTTEN_TOMATOES }
        assertEquals(TomatoState.FRESH, rt.state)
    }

    /** Their published split: at or above 60% is Fresh, below it is Rotten. */
    @Test
    fun `marks a score below the threshold as rotten`() {
        val body = """{"tomato_meter":42,"tomato_meter_state":"none"}"""
        assertEquals(
            TomatoState.ROTTEN,
            parse(body).single { it.source == RatingSource.ROTTEN_TOMATOES }.state,
        )
    }

    @Test
    fun `treats exactly the threshold as fresh`() {
        val body = """{"tomato_meter":60}"""
        assertEquals(
            TomatoState.FRESH,
            parse(body).single { it.source == RatingSource.ROTTEN_TOMATOES }.state,
        )
    }

    /**
     * Certified Fresh is an award with its own criteria - a minimum review count, a
     * share from top critics - so it is only ever taken from the service's field and
     * never inferred from the score.
     */
    @Test
    fun `honours a certified state the service reports`() {
        val body = """{"tomato_meter":89,"tomato_meter_state":"certified"}"""
        assertEquals(
            TomatoState.CERTIFIED_FRESH,
            parse(body).single { it.source == RatingSource.ROTTEN_TOMATOES }.state,
        )
    }

    @Test
    fun `splits the audience meter at the same threshold`() {
        assertEquals(
            TomatoState.AUDIENCE_FRESH,
            parse("""{"audience_score":96}""").single().state,
        )
        assertEquals(
            TomatoState.AUDIENCE_SPILLED,
            parse("""{"audience_score":41}""").single().state,
        )
    }

    // --------------------------------------------------------- absent values

    /**
     * Television answers with counts of zero even on success, which is absence
     * rather than a count of none - printing "0 reviews" would be worse than nothing.
     */
    @Test
    fun `drops a zero review count`() {
        val body = """{"tomato_meter":96,"tomato_meter_count":0,"audience_score":97,"audience_score_count":0}"""
        assertTrue(parse(body).all { it.voteCount == null })
    }

    @Test
    fun `skips a score of zero rather than showing it`() {
        val body = """{"imdb_rating":"0","tomato_meter":0,"audience_score":0}"""
        assertTrue(parse(body).isEmpty())
    }

    /** Either meter may be absent; whichever is present still shows. */
    @Test
    fun `keeps whichever meter is present`() {
        val critic = parse("""{"tomato_meter":74}""")
        assertEquals(1, critic.size)
        assertEquals(RatingSource.ROTTEN_TOMATOES, critic.single().source)

        val audience = parse("""{"audience_score":88}""")
        assertEquals(1, audience.size)
        assertEquals(RatingSource.POPCORNMETER, audience.single().source)
    }

    /** An IMDb figure alone leaves nothing to show, since it is not rendered. */
    @Test
    fun `returns nothing when only an imdb score is reported`() {
        assertTrue(parse("""{"imdb_rating":"8.1"}""").isEmpty())
    }

    // --------------------------------------------------------- malformed input

    @Test
    fun `survives an error body`() {
        assertTrue(parse("""{"error":"upstream failed"}""").isEmpty())
    }

    @Test
    fun `survives html from a proxy`() {
        assertTrue(parse("<!doctype html><html><body>502</body></html>").isEmpty())
    }

    @Test
    fun `survives an empty body`() {
        assertTrue(parse("").isEmpty())
    }

    /** The service may add fields; unknown ones must not break the parse. */
    @Test
    fun `ignores fields it does not know`() {
        val body = """{"tomato_meter":77,"mbp_id":4061,"quality_tag_new":"4k","cats":"drama"}"""
        assertEquals("77%", parse(body).single().display)
    }
}
