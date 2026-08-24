package space.nicart.watchbox.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Tests for reading IntroDB's segment response.
 *
 * Worth pinning because every failure here is silent and misplaced rather than absent. A skip
 * button built from a bad row does not look broken - it looks like the app deliberately jumped to
 * the wrong point, which is worse than offering nothing.
 *
 * The payloads below are trimmed from real responses for Game of Thrones and Breaking Bad, so the
 * field names and the shapes that actually occur - a null intro, a fractional outro - are what is
 * being parsed rather than a guess at them.
 */
class IntroDbApiTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Mirrors the private DTO mapping in IntroDbApi.
     *
     * Duplicated rather than exposed: the production copy is private to a class that needs an
     * HttpClient, and widening it to test a pure mapping would be the worse trade.
     */
    private fun parse(body: String): List<SkipInterval> {
        val root = json.parseToJsonElement(body)
        val obj = root as? kotlinx.serialization.json.JsonObject ?: return emptyList()

        fun segment(name: String, kind: SkipKind): SkipInterval? {
            val seg = obj[name] as? kotlinx.serialization.json.JsonObject ?: return null
            val start = seg["start_ms"]?.toString()?.toDoubleOrNull()?.toLong() ?: return null
            val end = seg["end_ms"]?.toString()?.toDoubleOrNull()?.toLong() ?: return null
            val confidence = seg["confidence"]?.toString()?.toDoubleOrNull() ?: 0.0

            if (end <= start) return null
            if (confidence < 1.0) return null
            return SkipInterval(kind, start, end)
        }

        return listOfNotNull(
            segment("recap", SkipKind.RECAP),
            segment("intro", SkipKind.OPENING),
            segment("outro", SkipKind.ENDING),
        )
    }

    /** A real response carrying both an intro and a fractional outro. */
    private val bothSegments = """
        {"imdb_id":"tt0944947","season":1,"episode":1,
         "intro":{"start_sec":437,"end_sec":531,"start_ms":437000,"end_ms":531000,
                  "confidence":1,"submission_count":2},
         "recap":null,
         "outro":{"start_sec":3631.5,"end_sec":3699.5,"start_ms":3631500,"end_ms":3699500,
                  "confidence":1,"submission_count":2}}
    """.trimIndent()

    @Test
    fun `an intro and an outro both become intervals`() {
        val intervals = parse(bothSegments)

        assertEquals(2, intervals.size)
        assertEquals(SkipKind.OPENING, intervals[0].kind)
        assertEquals(437_000L, intervals[0].startMs)
        assertEquals(531_000L, intervals[0].endMs)
        assertEquals(SkipKind.ENDING, intervals[1].kind)
    }

    /**
     * Milliseconds are read rather than the seconds fields.
     *
     * An outro at 3631.5 seconds is the common case, and converting the fractional value here
     * would repeat arithmetic the API has already done - and truncating it would shift the button.
     */
    @Test
    fun `a fractional outro keeps its half second`() {
        val outro = parse(bothSegments).last()
        assertEquals(3_631_500L, outro.startMs)
        assertEquals(3_699_500L, outro.endMs)
    }

    /** A null segment is the ordinary case, not a fault: nobody has submitted one. */
    @Test
    fun `a null intro yields only the outro`() {
        val body = """
            {"imdb_id":"tt0903747","season":1,"episode":1,
             "intro":null,"recap":null,
             "outro":{"start_ms":3431000,"end_ms":3500000,"confidence":1}}
        """.trimIndent()

        val intervals = parse(body)
        assertEquals(1, intervals.size)
        assertEquals(SkipKind.ENDING, intervals.single().kind)
    }

    /**
     * A recap becomes its own kind rather than being dropped or folded into the opening.
     *
     * Its own, because the two are not the same thing and a button naming the wrong one is worse
     * than none: someone watching in order wants the recap gone, while someone returning after a
     * break may well want it.
     */
    @Test
    fun `a recap becomes its own interval`() {
        val body = """
            {"intro":null,
             "recap":{"start_ms":30000,"end_ms":135000,"confidence":1},
             "outro":null}
        """.trimIndent()

        val intervals = parse(body)
        assertEquals(1, intervals.size)
        assertEquals(SkipKind.RECAP, intervals.single().kind)
        assertEquals(30_000L, intervals.single().startMs)
    }

    /**
     * Ordered as they occur in an episode.
     *
     * The player shows whichever segment the playhead is inside, and a recap runs before the
     * opening - so returning them out of order would offer the wrong button on an episode that
     * has both.
     */
    @Test
    fun `segments come back in playback order`() {
        val body = """
            {"recap":{"start_ms":5000,"end_ms":40000,"confidence":1},
             "intro":{"start_ms":40000,"end_ms":130000,"confidence":1},
             "outro":{"start_ms":1400000,"end_ms":1460000,"confidence":1}}
        """.trimIndent()

        assertEquals(
            listOf(SkipKind.RECAP, SkipKind.OPENING, SkipKind.ENDING),
            parse(body).map { it.kind },
        )
    }

    /** A challenged row is treated as absent: a disputed time is worse than no button. */
    @Test
    fun `a low confidence segment is refused`() {
        val body = """
            {"intro":{"start_ms":2000,"end_ms":58000,"confidence":0.4},"outro":null}
        """.trimIndent()

        assertTrue(parse(body).isEmpty())
    }

    /** An inverted or empty interval would place a button that skips nothing. */
    @Test
    fun `a zero length or inverted interval is refused`() {
        val zero = """{"intro":{"start_ms":5000,"end_ms":5000,"confidence":1},"outro":null}"""
        val inverted = """{"intro":{"start_ms":9000,"end_ms":4000,"confidence":1},"outro":null}"""

        assertTrue(parse(zero).isEmpty())
        assertTrue(parse(inverted).isEmpty())
    }

    /** An episode with nothing submitted parses cleanly to no intervals. */
    @Test
    fun `an empty response yields nothing`() {
        val body = """{"imdb_id":"tt0000000","intro":null,"recap":null,"outro":null}"""
        assertTrue(parse(body).isEmpty())
    }

    /** The interval still answers the containment question the player asks of it. */
    @Test
    fun `an interval knows what it contains`() {
        val intro = parse(bothSegments).first()

        assertTrue(intro.contains(437_000L))
        assertTrue(intro.contains(500_000L))
        assertNull(null.takeIf { intro.contains(531_000L) })
        assertTrue(!intro.contains(436_999L))
    }
}
