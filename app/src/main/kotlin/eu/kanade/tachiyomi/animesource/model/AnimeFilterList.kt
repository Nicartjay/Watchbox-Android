package eu.kanade.tachiyomi.animesource.model

/**
 * The filter set an extension exposes, passed back into search requests.
 *
 * Implemented as a [List] delegate because extensions both construct it from a
 * vararg and iterate it.
 *
 * See the note in [SAnime] for why this package reproduces the Aniyomi ABI.
 */
class AnimeFilterList(private val filters: List<AnimeFilter<*>>) :
    List<AnimeFilter<*>> by filters {

    constructor(vararg filters: AnimeFilter<*>) : this(filters.toList())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnimeFilterList) return false
        return filters == other.filters
    }

    override fun hashCode(): Int = filters.hashCode()
}
