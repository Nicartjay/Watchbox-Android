package space.nicart.watchbox.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for SubRip to WebVTT conversion.
 *
 * Pinned because the failure is silent in the worst way: a receiver handed SubRip lists the track
 * in its menu and displays nothing when it is selected. There is no error on either side, so a
 * regression here looks like "subtitles just don't work on the TV".
 *
 * The comma-versus-dot separator is the whole substance of the format difference, and it is one
 * character - easy to lose in a refactor and impossible to notice without a test.
 */
class SubtitleConverterTest {

    private val subrip = """
        1
        00:00:06,000 --> 00:00:12,074
        First line of dialogue

        2
        00:01:02,500 --> 00:01:04,000
        Second line
        spanning two rows
    """.trimIndent()

    // ------------------------------------------------------------------- header

    @Test
    fun `the webvtt header is added`() {
        assertTrue(SubtitleConverter.toWebVtt(subrip).startsWith("WEBVTT"))
    }

    /** Applying this to content that is already WebVTT must not double the header. */
    @Test
    fun `existing webvtt is left untouched`() {
        val vtt = "WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.000\nHello\n"

        assertEquals(vtt, SubtitleConverter.toWebVtt(vtt))
    }

    /** A BOM is legal in SubRip and rejected by some WebVTT parsers. */
    @Test
    fun `a byte order mark is stripped`() {
        val converted = SubtitleConverter.toWebVtt("\uFEFF$subrip")

        assertTrue(converted.startsWith("WEBVTT"), "unexpected prefix: ${converted.take(12)}")
        assertFalse(converted.contains('\uFEFF'))
    }

    /** A BOM before an existing WEBVTT header must not defeat the passthrough check. */
    @Test
    fun `a byte order mark before an existing header is stripped`() {
        val converted = SubtitleConverter.toWebVtt("\uFEFFWEBVTT\n\n1\n00:00:01.000 --> 00:00:02.000\nHi")

        assertTrue(converted.startsWith("WEBVTT"))
        assertEquals(1, Regex("WEBVTT").findAll(converted).count())
    }

    // --------------------------------------------------------------- timestamps

    /** The one substantive difference between the formats. */
    @Test
    fun `comma separators become dots`() {
        val converted = SubtitleConverter.toWebVtt(subrip)

        assertTrue(converted.contains("00:00:06.000 --> 00:00:12.074"), converted)
        assertTrue(converted.contains("00:01:02.500 --> 00:01:04.000"), converted)
        assertFalse(converted.contains(","), "a comma survived: $converted")
    }

    /** WebVTT requires exactly three fractional digits; SubRip in the wild sometimes has two. */
    @Test
    fun `short fractions are padded to milliseconds`() {
        val converted = SubtitleConverter.toWebVtt("1\n00:00:01,5 --> 00:00:02,25\nText")

        assertTrue(converted.contains("00:00:01.500 --> 00:00:02.250"), converted)
    }

    @Test
    fun `dialogue text is preserved`() {
        val converted = SubtitleConverter.toWebVtt(subrip)

        assertTrue(converted.contains("First line of dialogue"))
        assertTrue(converted.contains("spanning two rows"))
    }

    /** Cue numbers are legal WebVTT identifiers, so they are kept rather than stripped. */
    @Test
    fun `cue numbers are kept`() {
        val converted = SubtitleConverter.toWebVtt(subrip)

        assertTrue(converted.lines().any { it.trim() == "1" })
        assertTrue(converted.lines().any { it.trim() == "2" })
    }

    /** Windows line endings are the norm in downloaded subtitle files. */
    @Test
    fun `carriage returns are normalised`() {
        val converted = SubtitleConverter.toWebVtt(
            "1\r\n00:00:01,000 --> 00:00:02,000\r\nText\r\n",
        )

        assertFalse(converted.contains('\r'))
        assertTrue(converted.contains("00:00:01.000 --> 00:00:02.000"))
    }

    /**
     * A timing line may carry cue settings after the end time, which must survive.
     */
    @Test
    fun `trailing cue settings are preserved`() {
        val converted = SubtitleConverter.toWebVtt(
            "1\n00:00:01,000 --> 00:00:02,000 line:90%\nText",
        )

        assertTrue(converted.contains("00:00:01.000 --> 00:00:02.000 line:90%"), converted)
    }

    /** Text containing a comma must not be mistaken for a timestamp. */
    @Test
    fun `commas in dialogue are untouched`() {
        val converted = SubtitleConverter.toWebVtt(
            "1\n00:00:01,000 --> 00:00:02,000\nWell, hello there",
        )

        assertTrue(converted.contains("Well, hello there"), converted)
    }

    /**
     * The proxy now converts by content rather than by filename.
     *
     * A source's subtitle URL very often has no extension at all - the path is an opaque token -
     * so a name-based check passed SubRip through untouched and the receiver silently showed
     * nothing. Converting unconditionally is safe because WebVTT input is returned as-is.
     */
    @Test
    fun `subrip with no filename hint is still converted`() {
        val converted = SubtitleConverter.toWebVtt(subrip)

        assertTrue(converted.startsWith("WEBVTT"))
        assertTrue(converted.contains("00:00:06.000 --> 00:00:12.074"), converted)
    }

    @Test
    fun `empty input still yields a valid header`() {
        assertTrue(SubtitleConverter.toWebVtt("").startsWith("WEBVTT"))
    }

    // ------------------------------------------------------------- needs convert

    @Test
    fun `subrip files are detected by extension`() {
        assertTrue(SubtitleConverter.needsConversion("sub-123.srt"))
        assertTrue(SubtitleConverter.needsConversion("SUB-123.SRT"))
        assertTrue(SubtitleConverter.needsConversion("movie.sub"))
    }

    @Test
    fun `webvtt files need no conversion`() {
        assertFalse(SubtitleConverter.needsConversion("sub-123.vtt"))
        assertFalse(SubtitleConverter.needsConversion("track.webvtt"))
    }

    /** Signed URLs carry a query string, which must not hide the extension. */
    @Test
    fun `a query string does not defeat detection`() {
        assertTrue(SubtitleConverter.needsConversion("https://x.test/a.srt?token=1"))
        assertFalse(SubtitleConverter.needsConversion("https://x.test/a.vtt?token=1"))
    }
}
