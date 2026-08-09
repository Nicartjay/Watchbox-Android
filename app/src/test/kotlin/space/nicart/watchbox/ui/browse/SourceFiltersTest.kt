package space.nicart.watchbox.ui.browse

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for source filter flattening and write-back.
 *
 * Unit-tested because these failures are silent on a device: an index path that
 * resolves to the wrong filter still renders a plausible panel and still returns
 * results, just filtered by something the user did not pick. Group nesting is the
 * risky part - only some extensions use it, so a broken path would appear to work
 * everywhere until one specific source is opened.
 */
class SourceFiltersTest {

    // Mirrors how a real source builds its filters: a header, some flat entries,
    // and a group whose children live in its state.
    private fun buildFilters() = AnimeFilterList(
        AnimeFilter.Header("Filters"),
        AnimeFilter.CheckBox("Dubbed"),
        AnimeFilter.TriState("Completed"),
        AnimeFilter.Select("Season", arrayOf("Any", "Winter", "Spring")),
        AnimeFilter.Sort("Order", arrayOf("Title", "Date")),
        AnimeFilter.Text("Author"),
        AnimeFilter.Group(
            "Genres",
            listOf(
                AnimeFilter.CheckBox("Action"),
                AnimeFilter.CheckBox("Comedy"),
            ),
        ),
    )

    // ------------------------------------------------------------- flattening

    @Test
    fun `flatten inlines group children after the group itself`() {
        val entries = buildFilters().flattenForDisplay()

        // 7 top-level + 2 group children.
        assertEquals(9, entries.size, "expected group children to be inlined")
        assertEquals("Genres", entries[6].filter.name)
        assertEquals("Action", entries[7].filter.name)
        assertEquals("Comedy", entries[8].filter.name)
    }

    @Test
    fun `group children carry a nested path and greater depth`() {
        val entries = buildFilters().flattenForDisplay()

        assertEquals(listOf(6), entries[6].path)
        assertEquals(0, entries[6].depth)

        // The child's path must include its parent, or write-back targets the
        // wrong filter.
        assertEquals(listOf(6, 0), entries[7].path)
        assertEquals(1, entries[7].depth)
    }

    @Test
    fun `an empty filter list flattens to nothing`() {
        assertTrue(AnimeFilterList().flattenForDisplay().isEmpty())
    }

    // ------------------------------------------------------------- resolution

    @Test
    fun `filterAt resolves a top-level path`() {
        val filters = buildFilters()
        assertEquals("Dubbed", filters.filterAt(listOf(1))?.name)
    }

    @Test
    fun `filterAt resolves a nested path`() {
        val filters = buildFilters()
        assertEquals("Comedy", filters.filterAt(listOf(6, 1))?.name)
    }

    @Test
    fun `filterAt returns null for paths that do not exist`() {
        val filters = buildFilters()
        assertNull(filters.filterAt(emptyList()))
        assertNull(filters.filterAt(listOf(99)))
        assertNull(filters.filterAt(listOf(6, 99)))
        // Index 1 is a CheckBox, not a group, so it has no children.
        assertNull(filters.filterAt(listOf(1, 0)))
    }

    // ------------------------------------------------------------ write-back

    @Test
    fun `checkbox state is written through to the source's own object`() {
        val filters = buildFilters()
        filters.applyFilterChange(listOf(1), true)

        // The ABI requires mutating the instance the source handed over.
        assertEquals(true, (filters.filterAt(listOf(1)) as AnimeFilter.CheckBox).state)
    }

    @Test
    fun `nested checkbox state is written through`() {
        val filters = buildFilters()
        filters.applyFilterChange(listOf(6, 1), true)

        assertEquals(true, (filters.filterAt(listOf(6, 1)) as AnimeFilter.CheckBox).state)
        // A sibling must be untouched.
        assertEquals(false, (filters.filterAt(listOf(6, 0)) as AnimeFilter.CheckBox).state)
    }

    @Test
    fun `select, tri-state, text and sort all write through`() {
        val filters = buildFilters()

        filters.applyFilterChange(listOf(2), AnimeFilter.TriState.STATE_EXCLUDE)
        filters.applyFilterChange(listOf(3), 2)
        filters.applyFilterChange(listOf(4), AnimeFilter.Sort.Selection(1, ascending = true))
        filters.applyFilterChange(listOf(5), "Urasawa")

        assertEquals(
            AnimeFilter.TriState.STATE_EXCLUDE,
            (filters.filterAt(listOf(2)) as AnimeFilter.TriState).state,
        )
        assertEquals(2, (filters.filterAt(listOf(3)) as AnimeFilter.Select<*>).state)
        assertEquals(
            AnimeFilter.Sort.Selection(1, ascending = true),
            (filters.filterAt(listOf(4)) as AnimeFilter.Sort).state,
        )
        assertEquals("Urasawa", (filters.filterAt(listOf(5)) as AnimeFilter.Text).state)
    }

    @Test
    fun `a value of the wrong type is ignored rather than crashing`() {
        val filters = buildFilters()
        // Guards against a UI bug corrupting a source's filter state.
        filters.applyFilterChange(listOf(1), "not a boolean")
        assertEquals(false, (filters.filterAt(listOf(1)) as AnimeFilter.CheckBox).state)
    }

    @Test
    fun `writing to a header or unknown path is a no-op`() {
        val filters = buildFilters()
        filters.applyFilterChange(listOf(0), "anything")
        filters.applyFilterChange(listOf(42), true)
        // Nothing above should have changed the editable filters.
        assertEquals(false, (filters.filterAt(listOf(1)) as AnimeFilter.CheckBox).state)
    }

    // ------------------------------------------------------------ active flag

    @Test
    fun `a freshly built list is not active`() {
        assertFalse(buildFilters().hasActiveFilters(buildFilters()))
    }

    @Test
    fun `changing any filter marks the set active`() {
        val filters = buildFilters()
        filters.applyFilterChange(listOf(1), true)
        assertTrue(filters.hasActiveFilters(buildFilters()))
    }

    @Test
    fun `a change nested inside a group marks the set active`() {
        // The flag drives the filter button's highlight; missing nested changes
        // would leave it looking inactive while filters were applied.
        val filters = buildFilters()
        filters.applyFilterChange(listOf(6, 0), true)
        assertTrue(filters.hasActiveFilters(buildFilters()))
    }

    // --------------------------------------------------------------- cycling

    @Test
    fun `tri-state cycles ignore to include to exclude and back`() {
        var state = AnimeFilter.TriState.STATE_IGNORE
        state = nextTriState(state)
        assertEquals(AnimeFilter.TriState.STATE_INCLUDE, state)
        state = nextTriState(state)
        assertEquals(AnimeFilter.TriState.STATE_EXCLUDE, state)
        state = nextTriState(state)
        assertEquals(AnimeFilter.TriState.STATE_IGNORE, state)
    }

    @Test
    fun `selecting a new sort column starts descending`() {
        // Descending first because catalogue sorts mean newest or most popular.
        val selection = nextSortSelection(current = null, index = 1)
        assertEquals(1, selection.index)
        assertFalse(selection.ascending)
    }

    @Test
    fun `tapping the active sort column flips direction`() {
        val first = nextSortSelection(current = null, index = 0)
        val second = nextSortSelection(current = first, index = 0)

        assertEquals(0, second.index)
        assertTrue(second.ascending, "expected direction to flip on the active column")
    }

    @Test
    fun `switching sort column resets to descending`() {
        val ascendingOnZero = AnimeFilter.Sort.Selection(0, ascending = true)
        val moved = nextSortSelection(current = ascendingOnZero, index = 1)

        assertEquals(1, moved.index)
        assertFalse(moved.ascending)
    }
}
