package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.flow.Flow

/**
 * A source that can report streams as it finds them, rather than only when it has them all.
 *
 * ## Why this exists
 *
 * [AnimeSource.getVideoList] is a single suspending call returning one finished list. A source
 * with several independent backends resolves them in parallel internally, then joins before
 * returning - so the host learns nothing until the slowest one is done and the viewer waits on
 * a spinner while a playable stream has been sitting ready for seconds.
 *
 * Implementing this lets a source emit each batch as it lands. The host plays the first stream
 * that arrives and keeps collecting, adding servers to the picker as they appear.
 *
 * ## Deliberately a separate interface
 *
 * Not a method on [AnimeSource], and not a defaulted one. Kotlin compiles interface members to
 * `abstract` plus a static `DefaultImpls` helper, so adding a member there - even with a body -
 * changes the compiled contract, and an extension built against the older shape raises
 * `AbstractMethodError` the moment it is used. The same reasoning keeps `getSeasonList` and the
 * lib-16 `Hoster` overloads out of [AnimeSource].
 *
 * The host tests for this with `is`, exactly as it does for [ConfigurableAnimeSource], so a
 * source that does not implement it is unaffected and keeps working through
 * [AnimeSource.getVideoList].
 *
 * ## Contract
 *
 * - Emit at least once. A source with nothing to offer emits an empty list rather than
 *   completing silently, so the host can tell "none found" from "still working".
 * - Each emission is **cumulative**: it carries every stream found so far, in the order the
 *   host should offer them. Emitting only the new ones would make the host responsible for
 *   merging, and two sources would disagree about how.
 * - Later emissions may reorder earlier entries - a source that learns a better ranking should
 *   say so - but a stream already emitted should not disappear, since the host may already be
 *   playing it.
 * - Failure of one backend is not failure of the flow. Emit what the others found; throwing
 *   discards work that was already usable.
 *
 * See the note in [eu.kanade.tachiyomi.animesource.model.SAnime] for why this package
 * reproduces the Aniyomi ABI rather than inventing its own.
 */
interface ProgressiveVideoSource : AnimeSource {

    /**
     * Streams for [episode], emitted as they are found.
     *
     * Each emission supersedes the last. The flow completes when the source has nothing further
     * to add.
     */
    fun getVideoListFlow(episode: SEpisode): Flow<List<Video>>
}
