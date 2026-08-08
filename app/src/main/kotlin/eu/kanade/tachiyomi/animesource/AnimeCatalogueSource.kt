package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import rx.Observable

/**
 * A source that can be browsed and searched.
 *
 * Almost every extension implements this via [eu.kanade.tachiyomi.animesource.online.AnimeHttpSource].
 * Same dual suspend/RxJava arrangement as [AnimeSource]: call the `suspend`
 * methods and let the defaults bridge legacy implementations.
 *
 * See the note in [eu.kanade.tachiyomi.animesource.model.SAnime] for why this
 * package reproduces the Aniyomi ABI.
 */
@Suppress("DEPRECATION")
interface AnimeCatalogueSource : AnimeSource {

    override val lang: String

    /** False when the source has no "recently updated" feed. */
    val supportsLatest: Boolean

    suspend fun getPopularAnime(page: Int): AnimesPage =
        fetchPopularAnime(page).awaitSingleValue()

    suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = fetchSearchAnime(page, query, filters).awaitSingleValue()

    suspend fun getLatestUpdates(page: Int): AnimesPage =
        fetchLatestUpdates(page).awaitSingleValue()

    fun getFilterList(): AnimeFilterList

    @Deprecated("Implement getPopularAnime instead.")
    fun fetchPopularAnime(page: Int): Observable<AnimesPage> =
        Observable.error(UnsupportedOperationException("Not implemented"))

    @Deprecated("Implement getSearchAnime instead.")
    fun fetchSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Observable<AnimesPage> =
        Observable.error(UnsupportedOperationException("Not implemented"))

    @Deprecated("Implement getLatestUpdates instead.")
    fun fetchLatestUpdates(page: Int): Observable<AnimesPage> =
        Observable.error(UnsupportedOperationException("Not implemented"))
}
