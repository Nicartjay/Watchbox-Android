package eu.kanade.tachiyomi.animesource.model

/**
 * Filter primitives an extension exposes for its search screen.
 *
 * Each subtype carries a mutable [state] that the host mutates from the UI and
 * the extension reads back when building its search request.
 *
 * See the note in [SAnime] for why this package reproduces the Aniyomi ABI.
 */
sealed class AnimeFilter<T>(val name: String, var state: T) {

    /** Non-interactive label used to group following entries. */
    open class Header(name: String) : AnimeFilter<Any?>(name, null)

    /** Non-interactive visual divider. */
    open class Separator(name: String = "") : AnimeFilter<Any?>(name, null)

    /** Free-text input. */
    open class Text(name: String, state: String = "") : AnimeFilter<String>(name, state)

    /** Two-state toggle. */
    open class CheckBox(name: String, state: Boolean = false) :
        AnimeFilter<Boolean>(name, state)

    /**
     * Three-state toggle: ignore, include, exclude.
     * The integer states are part of the ABI.
     */
    open class TriState(name: String, state: Int = STATE_IGNORE) :
        AnimeFilter<Int>(name, state) {

        val isIgnored: Boolean get() = state == STATE_IGNORE
        val isIncluded: Boolean get() = state == STATE_INCLUDE
        val isExcluded: Boolean get() = state == STATE_EXCLUDE

        companion object {
            const val STATE_IGNORE = 0
            const val STATE_INCLUDE = 1
            const val STATE_EXCLUDE = 2
        }
    }

    /** Single choice out of [values]; [state] is the selected index. */
    open class Select<V>(
        name: String,
        val values: Array<V>,
        state: Int = 0,
    ) : AnimeFilter<Int>(name, state)

    /** A nested list of filters presented together. */
    open class Group<V>(name: String, state: List<V>) : AnimeFilter<List<V>>(name, state)

    /** Sort field plus direction. */
    open class Sort(
        name: String,
        val values: Array<String>,
        state: Selection? = null,
    ) : AnimeFilter<Sort.Selection?>(name, state) {
        data class Selection(val index: Int, val ascending: Boolean)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as AnimeFilter<*>
        return name == other.name && state == other.state
    }

    override fun hashCode(): Int = 31 * name.hashCode() + (state?.hashCode() ?: 0)
}
