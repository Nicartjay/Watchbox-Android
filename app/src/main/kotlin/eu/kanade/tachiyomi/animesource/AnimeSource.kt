package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import rx.Observable

/**
 * Root contract every extension source implements.
 *
 * ## Dual API
 *
 * Extensions built at different times implement different halves of this
 * interface. Newer ones override the `suspend` methods; older ones only
 * override the deprecated RxJava `fetch*` methods. Each side defaults to the
 * other, so a source is usable no matter which half it filled in — which is why
 * `rx.Observable` has to stay on the classpath even though nothing here calls
 * it directly.
 *
 * The host should always call the `suspend` methods and let the bridge handle
 * legacy sources.
 *
 * Deliberately *not* included: `getSeasonList` and the `Hoster` overloads. Those
 * arrived in lib 16, and declaring an abstract member the surveyed lib-14
 * extensions do not implement would raise `AbstractMethodError` at call time.
 *
 * See the note in [eu.kanade.tachiyomi.animesource.model.SAnime] for why this
 * package reproduces the Aniyomi ABI rather than inventing its own.
 */
@Suppress("DEPRECATION")
interface AnimeSource {

    /** Stable identity, usually derived from name+lang+versionId. */
    val id: Long

    val name: String

    val lang: String
        get() = ""

    suspend fun getAnimeDetails(anime: SAnime): SAnime =
        fetchAnimeDetails(anime).awaitSingleValue()

    suspend fun getEpisodeList(anime: SAnime): List<SEpisode> =
        fetchEpisodeList(anime).awaitSingleValue()

    suspend fun getVideoList(episode: SEpisode): List<Video> =
        fetchVideoList(episode).awaitSingleValue()

    @Deprecated("Implement getAnimeDetails instead.")
    fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> =
        Observable.error(UnsupportedOperationException("Not implemented"))

    @Deprecated("Implement getEpisodeList instead.")
    fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> =
        Observable.error(UnsupportedOperationException("Not implemented"))

    @Deprecated("Implement getVideoList instead.")
    fun fetchVideoList(episode: SEpisode): Observable<List<Video>> =
        Observable.error(UnsupportedOperationException("Not implemented"))
}
