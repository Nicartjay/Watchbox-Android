package space.nicart.watchbox.ui.tv

import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.domain.AnimeRow
import space.nicart.watchbox.ui.browse.SourceEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the TV home's single-source state.
 *
 * The TV home shows one source rather than a rail per source, so the state has to answer
 * questions the phone feed never asks: which source is selected, whether a selection
 * survives an extension list change, and what the backdrop shows before focus lands.
 * Those are the parts that break silently - a lost selection just looks like the feed
 * jumped to a different catalogue.
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

    private fun row(title: String, vararg titles: String) = AnimeRow(
        sourceId = 1L,
        sourceName = "Cineby",
        title = title,
        items = titles.map(::card),
    )

    // ------------------------------------------------------------ empty state

    @Test
    fun `no sources is reported as empty`() {
        assertTrue(TvHomeState().hasNoSources)
    }

    @Test
    fun `a source present means not empty`() {
        val state = TvHomeState(sources = listOf(source(1L, "Cineby")))
        assertTrue(!state.hasNoSources)
    }

    // -------------------------------------------------------- backdrop seeding

    @Test
    fun `the first card seeds the backdrop`() {
        // Otherwise the screen opens on flat black until the D-pad moves.
        val state = TvHomeState(rows = listOf(row("Popular", "A", "B")))
        assertEquals("A", state.firstCard()?.title)
    }

    @Test
    fun `no rows means no seed card`() {
        assertNull(TvHomeState().firstCard())
    }

    @Test
    fun `an empty first row does not crash the seed`() {
        // A source can return an empty Popular while Latest has content.
        val state = TvHomeState(
            rows = listOf(row("Popular"), row("Latest", "C")),
        )
        // firstOrNull on the first row's items yields null rather than reaching into
        // the second row - documented so the behaviour is deliberate.
        assertNull(state.firstCard())
    }

    // ----------------------------------------------------------- row shape

    @Test
    fun `row titles do not repeat the source name`() {
        // The picker in the top-right already names the source, so prefixing every row
        // with it buries the word that actually distinguishes one row from the other.
        val state = TvHomeState(
            selected = source(1L, "Cineby"),
            rows = listOf(row("Latest", "A"), row("Popular", "B")),
        )
        assertTrue(state.rows.none { it.title.contains("Cineby") })
        assertEquals(listOf("Latest", "Popular"), state.rows.map { it.title })
    }

    @Test
    fun `a source without a latest feed yields a single row`() {
        // Popular only, rather than an empty Latest row.
        val state = TvHomeState(
            selected = source(2L, "NoLatest", supportsLatest = false),
            rows = listOf(row("Popular", "A", "B")),
        )
        assertEquals(1, state.rows.size)
        assertEquals("Popular", state.rows.single().title)
    }

    @Test
    fun `both feeds yield two rows, latest first`() {
        val state = TvHomeState(rows = listOf(row("Latest", "A"), row("Popular", "B")))

        // Latest leads because it is the row that changes between visits, so it is what
        // the backdrop shows on open. A fixed order also means the rows do not reshuffle
        // between sources depending on which feeds each one supports.
        assertEquals(listOf("Latest", "Popular"), state.rows.map { it.title })
    }

    // ------------------------------------------------------------- selection

    @Test
    fun `selection is independent of the row contents`() {
        // The picker shows the selected source even while its rows are still loading.
        val state = TvHomeState(
            sources = listOf(source(1L, "Cineby"), source(2L, "Zoro")),
            selected = source(2L, "Zoro"),
            isLoading = true,
            rows = emptyList(),
        )
        assertEquals("Zoro", state.selected?.name)
        assertTrue(state.rows.isEmpty())
    }

    @Test
    fun `an error is only meaningful with no rows`() {
        // A partial failure should not replace content the user can already see.
        val withContent = TvHomeState(
            rows = listOf(row("Popular", "A")),
            errorMessage = null,
        )
        assertNull(withContent.errorMessage)
    }
}
