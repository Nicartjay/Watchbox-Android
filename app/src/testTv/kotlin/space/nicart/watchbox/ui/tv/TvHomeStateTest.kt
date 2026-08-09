package space.nicart.watchbox.ui.tv

import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.ui.browse.SourceEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the TV home's single-source state.
 *
 * The TV home shows one source, with Popular as a row and Latest as a paging grid, so the
 * state answers questions the phone feed never asks: which source is selected, whether a
 * selection survives an extension list change, what seeds the backdrop, and when paging
 * should stop. Those fail silently - a lost selection just looks like the feed jumped to
 * a different catalogue, and a paging bug looks like a source with no content.
 */
class TvHomeStateTest {

    private fun card(title: String) = AnimeCard(
        sourceId = 1L,
        url = "/$title",
        title = title,
        posterUrl = null,
    )

    private fun source(id: Long, name: String, supportsLatest: Boolean = true) = SourceEntry(
        id = id,
        name = name,
        lang = "en",
        supportsLatest = supportsLatest,
    )

    // ------------------------------------------------------------ empty state

    @Test
    fun `no sources is reported as empty`() {
        assertTrue(TvHomeState().hasNoSources)
    }

    @Test
    fun `a source present means not empty`() {
        assertTrue(!TvHomeState(sources = listOf(source(1L, "Cineby"))).hasNoSources)
    }

    @Test
    fun `isEmpty covers both feeds`() {
        // Used to decide whether to reload on an extension change, so it must not report
        // empty when only one of the two returned content.
        assertTrue(TvHomeState().isEmpty)
        assertTrue(!TvHomeState(popular = listOf(card("A"))).isEmpty)
        assertTrue(!TvHomeState(latest = listOf(card("B"))).isEmpty)
    }

    // -------------------------------------------------------- backdrop seeding

    @Test
    fun `popular seeds the backdrop`() {
        // Popular is the row on screen at rest, so the backdrop should match it rather
        // than something further down the grid.
        val state = TvHomeState(popular = listOf(card("A")), latest = listOf(card("B")))
        assertEquals("A", state.firstCard()?.title)
    }

    @Test
    fun `latest seeds the backdrop when popular is empty`() {
        // A source can return an empty Popular while Latest has content.
        val state = TvHomeState(latest = listOf(card("B")))
        assertEquals("B", state.firstCard()?.title)
    }

    @Test
    fun `no content means no seed card`() {
        assertNull(TvHomeState().firstCard())
    }

    // ---------------------------------------------------------------- paging

    @Test
    fun `paging starts enabled so the first append can run`() {
        assertTrue(TvHomeState().hasMoreLatest)
    }

    @Test
    fun `a page counter of zero means nothing has loaded`() {
        // The next append asks for page 1; a stale counter would skip pages.
        assertEquals(0, TvHomeState().latestPage)
    }

    @Test
    fun `appending is not the same as loading`() {
        // The grid keeps its content while appending, but shows nothing while loading -
        // conflating them would blank the screen on every page.
        val appending = TvHomeState(
            popular = listOf(card("A")),
            isLoading = false,
            isAppending = true,
        )
        assertTrue(!appending.isEmpty)
        assertTrue(appending.isAppending)
    }

    // ------------------------------------------------------------- selection

    @Test
    fun `selection is independent of the content`() {
        // The picker shows the selected source even while its feeds are still loading.
        val state = TvHomeState(
            sources = listOf(source(1L, "Cineby"), source(2L, "Zoro")),
            selected = source(2L, "Zoro"),
            isLoading = true,
        )
        assertEquals("Zoro", state.selected?.name)
        assertTrue(state.isEmpty)
    }

    @Test
    fun `a source without a latest feed still has popular`() {
        val state = TvHomeState(
            selected = source(2L, "NoLatest", supportsLatest = false),
            popular = listOf(card("A"), card("B")),
        )
        assertEquals(2, state.popular.size)
        assertTrue(state.latest.isEmpty())
    }
}
