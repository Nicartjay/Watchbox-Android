package eu.kanade.tachiyomi.animesource.model

/**
 * The filter set an extension exposes, passed back into search requests.
 *
 * Implemented as a [List] delegate because extensions both construct it from a
 * vararg and iterate it.
 *
 * [list] is public because extensions also read it directly (`getList()`); four lib 14
 * extensions in the default repo failed with `NoSuchMethodError` while it was private.
 *
 * See the note in [SAnime] for why this package reproduces the Aniyomi ABI.
 */
class AnimeFilterList(val list: List<AnimeFilter<*>>) :
    List<AnimeFilter<*>> by list {

    constructor(vararg filters: AnimeFilter<*>) : this(filters.toList())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnimeFilterList) return false
        return list == other.list
    }

    override fun hashCode(): Int = list.hashCode()
}
