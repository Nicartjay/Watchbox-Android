package space.nicart.watchbox.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the subtitle parser and offset lookup.
 *
 * The app parses cues itself only so timing can be shifted in both directions.
 * ExoPlayer's `onCues` reports lines as they become current, which can delay a subtitle
 * but can never surface one early, so the offset feature depends entirely on this.
 */
class SubtitleParserTest {

    private val webvtt = """
        WEBVTT

        1
        00:00:01.000 --> 00:00:03.500
        First line

        2
        00:00:05.000 --> 00:00:07.000
        Second line
        continued
    """.trimIndent()

    private val subrip = """
        1
        00:00:01,000 --> 00:00:03,500
        First line

        2
        00:00:05,000 --> 00:00:07,000
        Second line
    """.trimIndent()

    @Test
    fun `webvtt cues are parsed with their timings`() {
        val cues = SubtitleParser.parse(webvtt)

        assertEquals(2, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(3_500L, cues[0].endMs)
        assertEquals("First line", cues[0].text)
    }

    @Test
    fun `subrip comma separators are accepted`() {
        val cues = SubtitleParser.parse(subrip)

        assertEquals(2, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(3_500L, cues[0].endMs)
    }

    @Test
    fun `multi-line cue bodies are joined`() {
        val cues = SubtitleParser.parse(webvtt)

        assertEquals("Second line\ncontinued", cues[1].text)
    }

    @Test
    fun `inline markup is stripped`() {
        val cues = SubtitleParser.parse(
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<i>Italic</i> and {\\an8}positioned",
        )

        assertEquals("Italic and positioned", cues[0].text)
    }

    @Test
    fun `hours are optional`() {
        val cues = SubtitleParser.parse("WEBVTT\n\n01:30.000 --> 01:32.000\nNo hour field")

        assertEquals(90_000L, cues[0].startMs)
        assertEquals(92_000L, cues[0].endMs)
    }

    @Test
    fun `short fractions are padded not truncated`() {
        // SubRip in the wild sometimes carries two digits; ".5" is 500ms, not 5ms.
        val cues = SubtitleParser.parse("1\n00:00:01,5 --> 00:00:02,25\nShort fraction")

        assertEquals(1_500L, cues[0].startMs)
        assertEquals(2_250L, cues[0].endMs)
    }

    @Test
    fun `webvtt cue settings after the timing are ignored`() {
        val cues = SubtitleParser.parse(
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000 align:start position:10%\nPositioned",
        )

        assertEquals(1, cues.size)
        assertEquals("Positioned", cues[0].text)
    }

    @Test
    fun `ass and ssa yield nothing so the player keeps rendering`() {
        // Returning an empty list is the signal to fall back; a partial parse would show
        // some lines and silently drop others.
        val ass = "[Script Info]\nTitle: x\n\n[Events]\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello"

        assertTrue(SubtitleParser.parse(ass).isEmpty())
    }

    @Test
    fun `malformed cues are skipped rather than failing the file`() {
        val mixed = """
            WEBVTT

            00:00:01.000 --> 00:00:02.000
            Good

            not a timing line at all

            00:00:03.000 --> 00:00:04.000
            Also good
        """.trimIndent()

        val cues = SubtitleParser.parse(mixed)

        assertEquals(2, cues.size)
        assertEquals(listOf("Good", "Also good"), cues.map { it.text })
    }

    @Test
    fun `a reversed or empty window is dropped`() {
        val bad = "WEBVTT\n\n00:00:05.000 --> 00:00:02.000\nBackwards\n\n" +
            "00:00:06.000 --> 00:00:06.000\nZero length"

        assertTrue(SubtitleParser.parse(bad).isEmpty())
    }

    @Test
    fun `cues are sorted by start time`() {
        val unsorted = "WEBVTT\n\n00:00:05.000 --> 00:00:06.000\nLater\n\n" +
            "00:00:01.000 --> 00:00:02.000\nEarlier"

        assertEquals(listOf("Earlier", "Later"), SubtitleParser.parse(unsorted).map { it.text })
    }

    @Test
    fun `blank input is safe`() {
        assertTrue(SubtitleParser.parse("").isEmpty())
        assertTrue(SubtitleParser.parse("   \n\n  ").isEmpty())
        assertTrue(SubtitleParser.parse("WEBVTT").isEmpty())
    }

    // ------------------------------------------------------------- lookup

    @Test
    fun `a cue is active inside its own window`() {
        val cues = SubtitleParser.parse(webvtt)

        assertEquals(listOf("First line"), cues.activeAt(2_000L, offsetMs = 0L))
        assertTrue(cues.activeAt(4_000L, offsetMs = 0L).isEmpty())
    }

    @Test
    fun `the window is inclusive of start and exclusive of end`() {
        val cues = SubtitleParser.parse(webvtt)

        assertEquals(listOf("First line"), cues.activeAt(1_000L, 0L))
        assertTrue(cues.activeAt(3_500L, 0L).isEmpty())
    }

    @Test
    fun `a positive offset delays the subtitle`() {
        val cues = SubtitleParser.parse(webvtt)

        // With +2s the first line no longer shows at 2s...
        assertTrue(cues.activeAt(2_000L, offsetMs = 2_000L).isEmpty())
        // ...but does at 4s.
        assertEquals(listOf("First line"), cues.activeAt(4_000L, offsetMs = 2_000L))
    }

    @Test
    fun `a negative offset shows the subtitle earlier`() {
        // The case ExoPlayer's own callback cannot express, and the reason this parser
        // exists at all.
        val cues = SubtitleParser.parse(webvtt)

        assertEquals(listOf("Second line\ncontinued"), cues.activeAt(3_000L, offsetMs = -2_000L))
    }

    @Test
    fun `an empty cue list is safe to look up`() {
        assertTrue(emptyList<SubtitleCue>().activeAt(1_000L, 0L).isEmpty())
    }
}
