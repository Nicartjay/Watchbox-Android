package space.nicart.watchbox.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the description parser against the shapes sources actually emit.
 *
 * The Miruro sample below is transcribed from a real detail page where the raw markers
 * were visible on screen - `**Studio:**`, `**Tags:**`, a bracketed trailer link and a
 * `---` rule - because the field was rendered as plain text.
 */
class ParsedDescriptionTest {

    private val miruro = """
        **Airing** • TV Short • 2 min • Spring 2025

        **Studio:** Lesprit

        **Genres:** Slice of Life

        **Tags:** Iyashikei, Animals, Primarily Animal Cast, Chibi, Episodic

        **CHARACTER:** [Natsu no Uta](https://anilist.co/anime/145291) — Music, 1 episodes
        **CHARACTER:** [Futon no Naka kara Detakunai](https://anilist.co/anime/108056) — Music, 1 episodes

        [▶ Watch Trailer](https://www.youtube.com/watch?v=L4wurjkd0JM)

        ---

        The story of the series follows the everyday adventures of Koupen-chan
        and his friends as they enjoy the little things in life.

        (Source: Crunchyroll News, edited)
    """.trimIndent()

    @Test
    fun `the synopsis is separated from the metadata`() {
        val parsed = parseDescription(miruro)

        assertTrue(parsed.summary.startsWith("The story of the series"), parsed.summary)
        // None of the markdown may survive into the prose.
        assertFalse("**" in parsed.summary, parsed.summary)
        assertFalse("https://" in parsed.summary, parsed.summary)
        assertFalse("---" in parsed.summary, parsed.summary)
    }

    @Test
    fun `the studio is captured as a field`() {
        assertEquals("Lesprit", parseDescription(miruro).field("Studio"))
    }

    @Test
    fun `tags are captured intact`() {
        val tags = parseDescription(miruro).field("Tags")

        assertTrue(tags!!.startsWith("Iyashikei, Animals"), tags)
    }

    @Test
    fun `repeated labels are merged into one field`() {
        // Miruro emits one `**CHARACTER:**` line per entry; a stack of identically
        // labelled rows would look like a rendering fault.
        val characters = parseDescription(miruro).field("CHARACTER")

        assertTrue(characters!!.contains("Natsu no Uta"), characters)
        assertTrue(characters.contains("Futon no Naka kara Detakunai"), characters)
        assertEquals(1, parseDescription(miruro).fields.count { it.first == "CHARACTER" })
    }

    @Test
    fun `link urls are dropped but their text is kept`() {
        val parsed = parseDescription(miruro)
        val all = parsed.summary + parsed.fields.joinToString { it.second }

        assertFalse("anilist.co" in all, all)
        assertFalse("youtube.com" in all, all)
        assertTrue("Natsu no Uta" in all)
    }

    @Test
    fun `labels already shown elsewhere are dropped`() {
        // Genres have their own chips on the detail page.
        assertNull(parseDescription(miruro).field("Genres"))
    }

    @Test
    fun `a plain synopsis passes through unchanged`() {
        val plain = "Gold Roger was known as the Pirate King, the strongest of them all."

        val parsed = parseDescription(plain)

        assertEquals(plain, parsed.summary)
        assertTrue(parsed.fields.isEmpty())
    }

    @Test
    fun `a description with no rule still separates metadata`() {
        // AniDB's shape: labelled lines, then prose, with no horizontal rule.
        val anidb = """
            **Type:** TV Series
            **Rating:** 7.4

            A boy sets out on a journey.
        """.trimIndent()

        val parsed = parseDescription(anidb)

        assertEquals("A boy sets out on a journey.", parsed.summary)
        assertEquals("7.4", parsed.field("Rating"))
        // Type duplicates the meta line's own type badge.
        assertNull(parsed.field("Type"))
    }

    @Test
    fun `metadata-only input yields no summary`() {
        // The case `hasSummary` guards: the TMDB overview should be used instead, so
        // the summary must come back blank rather than as leftover markers.
        val parsed = parseDescription("**Type:** TV\n**Links:** [AniDB](https://anidb.net/a1)")

        assertEquals("", parsed.summary)
    }

    @Test
    fun `escaped newlines are honoured`() {
        // Some sources emit a literal backslash-n rather than a real newline.
        val parsed = parseDescription("**Studio:** Bones\\n\\nA real synopsis here.")

        assertEquals("Bones", parsed.field("Studio"))
        assertEquals("A real synopsis here.", parsed.summary)
    }

    // ------------------------------------------------------------- links

    @Test
    fun `database links are captured with their urls`() {
        val parsed = parseDescription(
            "**Links:** [MAL](https://myanimelist.net/anime/1) | " +
                "[AniList](https://anilist.co/anime/1)\n\nA synopsis.",
        )

        assertEquals(
            listOf("MAL" to "https://myanimelist.net/anime/1", "AniList" to "https://anilist.co/anime/1"),
            parsed.links,
        )
    }

    @Test
    fun `artwork links are not offered`() {
        // Sources put backdrop and cover URLs in the same markdown as their database
        // links. A chip opening a bare image is not a link the user meant to follow, and
        // the app is already showing that image.
        val parsed = parseDescription(
            "**Backdrop:** [backdrop](https://cdn.example/x/bd.jpg)\n" +
                "**Poster:** [cover art](https://cdn.example/x/p.png?size=w500)\n" +
                "**Links:** [AniDB](https://anidb.net/a1)\n\nA synopsis.",
        )

        assertEquals(listOf("AniDB" to "https://anidb.net/a1"), parsed.links)
    }

    @Test
    fun `an extensionless artwork url is caught by its label`() {
        // CDN paths often carry no suffix, so the label is the only signal left.
        val parsed = parseDescription("[banner](https://cdn.example/img/abc123)\n\nSynopsis.")

        assertTrue(parsed.links.isEmpty())
    }

    @Test
    fun `a repeated url is offered once`() {
        val parsed = parseDescription(
            "[MAL](https://myanimelist.net/anime/1) and [MyAnimeList](https://myanimelist.net/anime/1)",
        )

        assertEquals(1, parsed.links.size)
    }

    @Test
    fun `blank and null input is safe`() {
        assertEquals("", parseDescription(null).summary)
        assertEquals("", parseDescription("").summary)
        assertEquals("", parseDescription("   ").summary)
        assertTrue(parseDescription(null).fields.isEmpty())
    }
}
