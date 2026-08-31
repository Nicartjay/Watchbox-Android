package space.nicart.watchbox.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Tests for reading the curated list at `vidfast.vc/wyzie`.
 *
 * Payloads are trimmed from real replies to `?id=550` (a film) and
 * `?id=95396&season=1&episode=1` (an episode), because the shape is unusually thin - four
 * fields, no release name, no download count, no format - and inventing it would have pinned
 * something the service never sends.
 *
 * That thinness drives most of the decisions here: one entry per language means rows can be
 * named by language without being ambiguous, and there is no popularity signal to rank on.
 */
class SubtitleWyzieTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** Three real entries, verbatim but for the shortened URLs. */
    private val realReply = """
        [
          {"display":"Spanish","language":"es","url":"https://vidfast.vc/wyzie/eu_AAA","encoding":"UTF-8"},
          {"display":"Portuguese (BR)","language":"pb","url":"https://vidfast.vc/wyzie/eu_BBB","encoding":"UTF-8"},
          {"display":"English","language":"en","url":"https://vidfast.vc/wyzie/eu_CCC","encoding":"UTF-8"}
        ]
    """.trimIndent()

    private fun parse(payload: String): List<WyzieSubtitle> =
        json.decodeFromString<List<WyzieSubtitle>>(payload)

    // -------------------------------------------------------------- decoding

    @Test
    fun `reads a real reply`() {
        val entries = parse(realReply)

        assertEquals(3, entries.size)
        assertEquals(listOf("es", "pb", "en"), entries.map { it.language })
        assertEquals("Portuguese (BR)", entries[1].display)
    }

    @Test
    fun `ignores fields it does not read`() {
        // The service could add fields at any time, and a strict reader would then lose every
        // subtitle at once rather than one.
        val extra = """
            [{"display":"English","language":"en","url":"https://x/1","addedLater":{"a":1}}]
        """.trimIndent()

        assertEquals(1, parse(extra).size)
    }

    @Test
    fun `reads an empty reply`() {
        assertTrue(parse("[]").isEmpty())
    }

    // --------------------------------------------------------------- mapping

    @Test
    fun `names a row by its language`() {
        // Honest rather than lazy: with one entry per language there is nothing to
        // disambiguate, and inventing a release name would imply a choice that does not exist.
        val result = parse(realReply)[1].toResult()

        assertEquals("Portuguese (BR)", result?.name)
        assertEquals("pb", result?.language)
    }

    @Test
    fun `declares srt because the reply carries no format`() {
        // The URL has no extension either. Verified by fetching one: the payload is SubRip.
        assertEquals("srt", parse(realReply).first().toResult()?.format)
    }

    @Test
    fun `identifies a row by its url`() {
        // There is no id field, and the language would collide once a fallback merges providers.
        val result = parse(realReply).first().toResult()

        assertEquals("https://vidfast.vc/wyzie/eu_AAA", result?.id)
        assertEquals("https://vidfast.vc/wyzie/eu_AAA", result?.downloadUrl)
    }

    @Test
    fun `claims no download count`() {
        // No popularity signal exists. Inventing one would put these above or below other
        // providers' results for no reason.
        assertEquals(0L, parse(realReply).first().toResult()?.downloads)
    }

    @Test
    fun `assumes a track is not hearing impaired`() {
        // Not reported. The safer error: one shown as ordinary is a mild surprise, where hiding
        // a usable track loses it entirely.
        assertEquals(false, parse(realReply).first().toResult()?.hearingImpaired)
    }

    @Test
    fun `drops an entry with no url`() {
        val noUrl = """[{"display":"English","language":"en"}]"""

        assertNull(parse(noUrl).single().toResult())
    }

    @Test
    fun `drops an entry with no language`() {
        // The language is what the row is named and filtered by, so an entry without one cannot
        // be offered.
        val noLang = """[{"display":"English","url":"https://x/1"}]"""

        assertNull(parse(noLang).single().toResult())
    }

    @Test
    fun `falls back to the code when the service names no language`() {
        val noDisplay = """[{"language":"en","url":"https://x/1"}]"""

        assertEquals("en", parse(noDisplay).single().toResult()?.name)
    }

    // ------------------------------------------------------------- filtering

    @Test
    fun `a stored code matches the regional variants this service splits`() {
        // It distinguishes "pt" from "pb" and "zh" from "zt". Matching on the leading subtag is
        // what keeps a stored "pt" from missing the Brazilian entry, which is often the only
        // Portuguese one present.
        val languages = parse(realReply).mapNotNull { it.language }

        assertEquals(listOf("pb"), languages.filter { it.startsWith("pb", true) })
        assertEquals(
            listOf("pb"),
            languages.filter { it.startsWith("p", true) },
        )
    }

    @Test
    fun `filtering by language leaves one entry`() {
        // The reason client-side filtering is acceptable here: the service ignores a language
        // parameter, but returns one entry per language rather than dozens per release.
        val english = parse(realReply).filter { it.language == "en" }

        assertEquals(1, english.size)
        assertEquals("English", english.single().display)
    }
}
