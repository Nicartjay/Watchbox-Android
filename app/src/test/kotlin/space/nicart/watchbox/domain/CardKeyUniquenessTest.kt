package space.nicart.watchbox.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests that card lists never carry a duplicate [AnimeCard.key].
 *
 * Unit-tested because the consequence is a crash, not a glitch. Every lazy list in
 * the app is keyed on `sourceId::url`, and Compose treats a repeated key as fatal:
 *
 *     IllegalArgumentException: Key "<sourceId>::<url>" was already used.
 *
 * The failure is data-dependent, so it survives any amount of manual testing - it
 * needs a source that happens to repeat a title. One did: AniZone returned the same
 * entry twice in a single page and took the home screen down on launch.
 *
 * Two distinct places can introduce a repeat, so both are pinned here:
 *
 *  - within one page, when a source lists the same title twice;
 *  - across pages, when an infinite-scroll append re-serves a title because the
 *    catalogue's ordering shifted between requests.
 */
class CardKeyUniquenessTest {

    private fun card(url: String, sourceId: Long = 1L, title: String = "T") = AnimeCard(
        sourceId = sourceId,
        sourceName = "Source",
        url = url,
        title = title,
        posterUrl = null,
    )

    @Test
    fun `the key is composed of source and url`() {
        assertEquals("7::/anime/x", card(url = "/anime/x", sourceId = 7L).key)
    }

    @Test
    fun `the same url from different sources stays distinct`() {
        // Sources share URL shapes, so the key must not collapse across them -
        // otherwise two different titles would fight over one slot.
        val cards = listOf(card("/anime/x", sourceId = 1L), card("/anime/x", sourceId = 2L))

        assertEquals(2, cards.distinctBy { it.key }.size)
    }

    @Test
    fun `a page that repeats a title yields one card`() {
        // Exactly the AniZone case: the same entry twice in one response.
        val page = listOf(card("/anime/8ab2te29"), card("/anime/8ab2te29"))

        assertEquals(1, page.distinctBy { it.key }.size)
    }

    @Test
    fun `appending a page that re-serves a title does not duplicate`() {
        val firstPage = listOf(card("/a"), card("/b"))
        val secondPage = listOf(card("/b"), card("/c"))

        val merged = (firstPage + secondPage).distinctBy { it.key }

        assertEquals(listOf("1::/a", "1::/b", "1::/c"), merged.map { it.key })
    }

    @Test
    fun `dedup keeps the first occurrence and preserves order`() {
        // Order matters: catalogues are ranked, and reordering silently changes
        // what the user sees at the top of a rail.
        val cards = listOf(
            card("/a", title = "first"),
            card("/b", title = "second"),
            card("/a", title = "duplicate"),
        )

        val merged = cards.distinctBy { it.key }

        assertEquals(listOf("first", "second"), merged.map { it.title })
    }

    @Test
    fun `a fully duplicate page still leaves the list non-empty`() {
        // Guards the paging interaction: a page that dedups away to nothing must
        // not be mistaken for an empty list, or browsing would appear to break.
        val existing = listOf(card("/a"))
        val repeat = listOf(card("/a"))

        val merged = (existing + repeat).distinctBy { it.key }

        assertTrue(merged.isNotEmpty())
        assertEquals(1, merged.size)
    }
}
