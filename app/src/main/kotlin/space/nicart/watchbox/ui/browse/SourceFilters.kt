package space.nicart.watchbox.ui.browse

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

/**
 * A source filter flattened for display.
 *
 * The ABI's [AnimeFilter] tree is mutated in place - the host edits `state` on the
 * very objects the source handed over, then passes the same list back. That makes
 * the filter list stateful in a way Compose cannot observe, so the tree is
 * flattened into these immutable descriptors for rendering and edits are applied
 * back through [applyFilterChange].
 *
 * Groups are inlined rather than nested, because a source may nest arbitrarily
 * deep and a recursive UI would produce unusable indentation on a phone.
 */
data class FilterEntry(
    /** Index path into the (possibly nested) filter tree. */
    val path: List<Int>,
    val filter: AnimeFilter<*>,
    /** Nesting level, used only to indent group members. */
    val depth: Int,
    /**
     * The filter's state, copied at flatten time.
     *
     * This is what makes a change observable, and it is the whole reason the field
     * exists. [filter] is the source's own object and is mutated in place, so it is
     * the *same instance* before and after an edit - and [AnimeFilter.equals]
     * compares name and state, both read live. A descriptor holding only [filter]
     * therefore compares equal to its own post-edit self, `StateFlow` treats the new
     * value as a duplicate and drops it, and the UI never hears about the change.
     *
     * Copying the value here breaks that aliasing: the snapshot is taken at a point
     * in time and cannot be mutated afterwards, so two flattenings of the same list
     * differ exactly when something actually changed.
     *
     * Groups contribute no state of their own - their children are flattened into
     * sibling entries and carry their own snapshots - so a group records `null`
     * rather than its live child list, which would alias in the same way.
     */
    val state: Any?,
)

/** Flattens a filter list, inlining group children. */
fun AnimeFilterList.flattenForDisplay(): List<FilterEntry> {
    val out = mutableListOf<FilterEntry>()

    fun walk(filters: List<AnimeFilter<*>>, prefix: List<Int>, depth: Int) {
        filters.forEachIndexed { index, filter ->
            val path = prefix + index
            out += FilterEntry(
                path = path,
                filter = filter,
                depth = depth,
                state = filter.snapshotState(),
            )

            // Group state holds the child filters themselves.
            if (filter is AnimeFilter.Group<*>) {
                val children = filter.state.filterIsInstance<AnimeFilter<*>>()
                walk(children, path, depth + 1)
            }
        }
    }

    walk(this.toList(), emptyList(), 0)
    return out
}

/**
 * The filter's state as a value that cannot change underneath the caller.
 *
 * Every editable state in the ABI is already immutable (`Boolean`, `Int`, `String`,
 * or the `Sort.Selection` data class), so returning it directly is enough. Groups
 * and the stateless decorations return `null`; a group's state is a live list of
 * child filters, which would alias exactly like the objects it contains.
 */
private fun AnimeFilter<*>.snapshotState(): Any? = when (this) {
    is AnimeFilter.Group<*> -> null
    is AnimeFilter.Header, is AnimeFilter.Separator -> null
    else -> state
}

/** Resolves an index path back to the live filter object. */
fun AnimeFilterList.filterAt(path: List<Int>): AnimeFilter<*>? {
    if (path.isEmpty()) return null

    var current: AnimeFilter<*> = getOrNull(path.first()) ?: return null

    for (index in path.drop(1)) {
        val group = current as? AnimeFilter.Group<*> ?: return null
        current = group.state.filterIsInstance<AnimeFilter<*>>().getOrNull(index) ?: return null
    }
    return current
}

/**
 * Writes a new state onto the filter at [path].
 *
 * Mutating the source's own objects is deliberate and required by the ABI: the
 * source reads `state` off the instances it created, so replacing them with copies
 * would silently discard every selection.
 */
@Suppress("UNCHECKED_CAST")
fun AnimeFilterList.applyFilterChange(path: List<Int>, value: Any?) {
    when (val filter = filterAt(path)) {
        is AnimeFilter.CheckBox -> (filter as AnimeFilter<Boolean>).state = value as? Boolean
            ?: return

        is AnimeFilter.TriState -> (filter as AnimeFilter<Int>).state = value as? Int ?: return

        is AnimeFilter.Select<*> -> (filter as AnimeFilter<Int>).state = value as? Int ?: return

        is AnimeFilter.Text -> (filter as AnimeFilter<String>).state = value as? String ?: return

        is AnimeFilter.Sort -> (filter as AnimeFilter<AnimeFilter.Sort.Selection?>).state =
            value as? AnimeFilter.Sort.Selection

        // Header, Separator and Group carry no editable state of their own.
        else -> Unit
    }
}

/**
 * Whether any filter differs from a freshly-built list.
 *
 * Used to show an "active" marker on the filter button. Compared against a new
 * [AnimeFilterList] from the source rather than a remembered snapshot, because
 * the live list is mutated in place and any snapshot of it would alias.
 */
fun AnimeFilterList.hasActiveFilters(defaults: AnimeFilterList): Boolean {
    if (size != defaults.size) return true

    return flattenForDisplay().zip(defaults.flattenForDisplay()).any { (current, default) ->
        current.state != default.state
    }
}

/** Cycles a tri-state filter: ignore -> include -> exclude -> ignore. */
fun nextTriState(current: Int): Int = when (current) {
    AnimeFilter.TriState.STATE_IGNORE -> AnimeFilter.TriState.STATE_INCLUDE
    AnimeFilter.TriState.STATE_INCLUDE -> AnimeFilter.TriState.STATE_EXCLUDE
    else -> AnimeFilter.TriState.STATE_IGNORE
}

/**
 * Cycles a sort filter's selection for a given column.
 *
 * Tapping the active column flips direction; tapping a different column selects
 * it descending first, which is what "sort by" almost always means for a
 * catalogue (newest, most popular).
 */
fun nextSortSelection(
    current: AnimeFilter.Sort.Selection?,
    index: Int,
): AnimeFilter.Sort.Selection = when {
    current == null || current.index != index ->
        AnimeFilter.Sort.Selection(index, ascending = false)

    else -> AnimeFilter.Sort.Selection(index, ascending = !current.ascending)
}
