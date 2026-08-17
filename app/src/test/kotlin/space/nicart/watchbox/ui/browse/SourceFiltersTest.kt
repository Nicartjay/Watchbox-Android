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

    // ------------------------------------------- state-change observability

    @Test
    fun `a re-flattened entry is unequal to the previous one after a change`() {
        // The bug this guards: FilterEntry is a data class holding the live AnimeFilter,
        // and AnimeFilter.equals compares name and state. The filter is mutated in place,
        // so the *same* instance is on both sides of the comparison - meaning a snapshot
        // taken before the change reports equal to one taken after, and any consumer that
        // skips equal values drops the update.
        val filters = buildFilters()
        val before = filters.flattenForDisplay()

        filters.applyFilterChange(listOf(3), 2)
        val after = filters.flattenForDisplay()

        assertTrue(
            before != after,
            "a snapshot taken before the change must not compare equal to one taken after",
        )
    }

    @Test
    fun `consecutive different selections are each observable`() {
        // The reported bug, as the user hit it: a Cineby-style type filter defaulting to
        // "All", then TV, then Movie. The second change appeared to do nothing, and only
        // taking the value back to the default made the next pick register.
        val filters = AnimeFilterList(
            AnimeFilter.Select("Type", arrayOf("All", "TV Shows", "Movies")),
        )

        val seen = mutableListOf<List<FilterEntry>>()
        seen += filters.flattenForDisplay()

        // All -> TV Shows
        filters.applyFilterChange(listOf(0), 1)
        seen += filters.flattenForDisplay()

        // TV Shows -> Movies, with no return to the default in between.
        filters.applyFilterChange(listOf(0), 2)
        seen += filters.flattenForDisplay()

        // Every step has to be distinguishable, or a StateFlow drops it as a duplicate.
        assertEquals(3, seen.distinct().size, "each selection must produce a distinct value")
        assertEquals(0, seen[0].single().state)
        assertEquals(1, seen[1].single().state)
        assertEquals(2, seen[2].single().state)
    }

    @Test
    fun `a snapshot keeps the value it was taken with`() {
        // The core defect: the descriptor used to hold only the live filter, so a
        // snapshot's state changed retroactively when the filter was mutated. It has to
        // report what was true when it was taken.
        val filters = AnimeFilterList(
            AnimeFilter.Select("Type", arrayOf("All", "TV Shows", "Movies")),
        )
        val before = filters.flattenForDisplay().single()

        filters.applyFilterChange(listOf(0), 2)

        assertEquals(0, before.state, "an old snapshot must not report the new value")
        assertEquals(2, filters.flattenForDisplay().single().state)
    }

    @Test
    fun `every editable filter type is snapshotted`() {
        // Guards the same bug in the types the reported one did not happen to use.
        val filters = buildFilters()
        val before = filters.flattenForDisplay()

        filters.applyFilterChange(listOf(1), true)                                  // CheckBox
        filters.applyFilterChange(listOf(2), AnimeFilter.TriState.STATE_EXCLUDE)    // TriState
        filters.applyFilterChange(listOf(3), 2)                                     // Select
        filters.applyFilterChange(listOf(4), AnimeFilter.Sort.Selection(1, true))   // Sort
        filters.applyFilterChange(listOf(5), "Ito")                                 // Text
        filters.applyFilterChange(listOf(6, 0), true)                               // grouped CheckBox

        val after = filters.flattenForDisplay()
        listOf(1, 2, 3, 4, 5).forEach { index ->
            assertTrue(before[index] != after[index], "entry $index did not change")
        }
        // The group's own entry carries no state; its child is a sibling entry that does.
        assertTrue(before[7] != after[7], "grouped child did not change")
    }

    @Test
    fun `a group entry holds no aliasing state`() {
        // A group's live state is its list of child filters, which would alias exactly
        // like the objects inside it, so the entry records null instead.
        val group = buildFilters().flattenForDisplay().first { it.filter is AnimeFilter.Group<*> }
        assertEquals(null, group.state)
    }

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
