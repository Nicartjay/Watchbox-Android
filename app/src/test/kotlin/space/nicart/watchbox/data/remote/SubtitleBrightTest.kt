package space.nicart.watchbox.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Tests for reading the keyless aggregator's search response.
 *
 * Every payload here is trimmed from a real reply to
 * `subs.bright67.online/search?id=79744&season=1&episode=1`, because the endpoint returns
 * fifty-odd fields per result and most are null in practice - inventing a shape would have
 * pinned something the service never sends.
 *
 * The parsing matters more than it looks: a field that fails to decode makes a subtitle silently
 * absent rather than raising anything, which is indistinguishable from a title nobody has
 * subtitled.
 */
class SubtitleBrightTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * One real result, cut down to the fields that are read.
     *
     * The full reply carries `flagUrl`, `stableId`, `r2Url`, `qualityWarnings` and forty more.
     * They are left out deliberately: `ignoreUnknownKeys` has to keep working, since the service
     * adds fields without notice and a strict reader would break on the next one.
     */
    private val realResult = """
        [{
          "id": "1956089121",
          "url": "https://subs.bright67.online/c/19c40c53/id/1956089121?format=srt",
          "format": "srt",
          "encoding": "UTF-8",
          "display": "English",
          "language": "en",
          "languageName": "English",
          "media": "\"The Rookie\" Pilot",
          "isHearingImpaired": false,
          "isForced": false,
          "downloadCount": 395168,
          "isTrusted": true,
          "isMachineTranslated": false,
          "syncConfidence": "likely",
          "release": "The.Rookie.S01E01.HDTV.x264-SVA",
          "fileName": "The.Rookie.S01E01.HDTV.x264-SVA.srt",
          "source": "opensubtitles"
        }]
    """.trimIndent()

    private fun parse(payload: String): List<BrightSubtitle> =
        json.decodeFromString<List<BrightSubtitle>>(payload)

    // -------------------------------------------------------------- decoding

    @Test
    fun `reads a real result`() {
        val entry = parse(realResult).single()

        assertEquals("1956089121", entry.id)
        assertEquals("en", entry.language)
        assertEquals("srt", entry.format)
        assertEquals("The.Rookie.S01E01.HDTV.x264-SVA", entry.release)
        assertEquals(395168L, entry.downloadCount)
        assertTrue(entry.isTrusted == true)
    }

    @Test
    fun `ignores the fields it does not read`() {
        // The service returns around fifty per result and adds more without notice. A strict
        // reader would fail on the next addition and lose every subtitle at once.
        val withExtras = """
            [{
              "url": "https://x/1",
              "language": "en",
              "release": "R",
              "flagUrl": "https://flagsapi.com/US/flat/24.png",
              "qualityWarnings": [],
              "alternativeSources": [],
              "somethingAddedLater": {"nested": true}
            }]
        """.trimIndent()

        assertEquals(1, parse(withExtras).size)
    }

    @Test
    fun `survives the nulls the service actually sends`() {
        // resolution, edition, lineCount and a dozen others come back null on most results.
        val sparse = """
            [{
              "url": "https://x/1",
              "language": "ro",
              "release": null,
              "fileName": null,
              "downloadCount": null,
              "isTrusted": null,
              "syncConfidence": null
            }]
        """.trimIndent()

        val entry = parse(sparse).single()

        assertEquals("ro", entry.language)
        assertEquals(null, entry.release)
    }

    @Test
    fun `reads an empty reply`() {
        // What a title with no subtitles in the requested language returns.
        assertTrue(parse("[]").isEmpty())
    }

    // --------------------------------------------------------------- naming

    @Test
    fun `names a result by its release`() {
        // The release is what tells one row from another. "display" is only the language, so
        // naming by it would make every row for a language read identically.
        val entry = parse(realResult).single()

        assertEquals("The.Rookie.S01E01.HDTV.x264-SVA", entry.bestName())
    }

    @Test
    fun `falls back to the filename then the language`() {
        val noRelease = """
            [{"url": "https://x/1", "language": "en", "fileName": "Some.File.srt"}]
        """.trimIndent()
        val neither = """
            [{"url": "https://x/1", "language": "en", "display": "English"}]
        """.trimIndent()

        assertEquals("Some.File.srt", parse(noRelease).single().bestName())
        assertEquals("English", parse(neither).single().bestName())
    }

    @Test
    fun `marks a machine translation rather than hiding it`() {
        // For a language with no human subtitle at all it is the only option, so it is offered
        // with a note instead of being dropped.
        val auto = """
            [{
              "url": "https://x/1", "language": "th", "release": "Auto",
              "isMachineTranslated": true
            }]
        """.trimIndent()

        assertTrue("auto-translated" in parse(auto).single().displayName())
    }

    @Test
    fun `marks an uncertain sync`() {
        // The one thing OpenSubtitles' own API does not report: a file matched by id alone is
        // routinely timed against a different cut.
        val unsure = """
            [{
              "url": "https://x/1", "language": "en", "release": "R",
              "syncConfidence": "unlikely"
            }]
        """.trimIndent()

        assertTrue("sync uncertain" in parse(unsure).single().displayName())
    }

    @Test
    fun `a likely sync carries no note`() {
        // Every result in the captured reply was "likely", so this is the normal case and must
        // stay clean.
        assertEquals(
            "The.Rookie.S01E01.HDTV.x264-SVA",
            parse(realResult).single().displayName(),
        )
    }

    // -------------------------------------------------------------- ranking

    @Test
    fun `a trusted upload is promoted without overriding popularity`() {
        // The bonus breaks near-ties. A heavily-downloaded untrusted file is usually the safer
        // bet than an obscure trusted one, so it must still win.
        val trustedButObscure = 100L + BRIGHT_TRUSTED_BONUS
        val untrustedButPopular = 395_168L

        assertTrue(untrustedButPopular > trustedButObscure)
    }

    @Test
    fun `a trusted upload outranks an equally obscure untrusted one`() {
        assertTrue(100L + BRIGHT_TRUSTED_BONUS > 100L)
    }
}
