package eu.kanade.tachiyomi.animesource.online

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Convenience layer for sources that scrape HTML.
 *
 * Turns the `*Parse(Response)` contract from [AnimeHttpSource] into a
 * selector-plus-element contract, so subclasses only describe how to read one
 * card or row. Sources returning JSON extend [AnimeHttpSource] directly.
 *
 * See the note in [eu.kanade.tachiyomi.animesource.model.SAnime] for why this
 * package reproduces the Aniyomi ABI.
 */
@Suppress("unused")
abstract class ParsedAnimeHttpSource : AnimeHttpSource() {

    // -------------------------------------------------------------- popular

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = document.select(popularAnimeSelector())
            .map { popularAnimeFromElement(it) }

        val hasNextPage = popularAnimeNextPageSelector()
            ?.let { document.selectFirst(it) != null }
            ?: false

        return AnimesPage(animes, hasNextPage)
    }

    protected abstract fun popularAnimeSelector(): String

    protected abstract fun popularAnimeFromElement(element: Element): SAnime

    protected abstract fun popularAnimeNextPageSelector(): String?

    // --------------------------------------------------------------- latest

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = document.select(latestUpdatesSelector())
            .map { latestUpdatesFromElement(it) }

        val hasNextPage = latestUpdatesNextPageSelector()
            ?.let { document.selectFirst(it) != null }
            ?: false

        return AnimesPage(animes, hasNextPage)
    }

    protected abstract fun latestUpdatesSelector(): String

    protected abstract fun latestUpdatesFromElement(element: Element): SAnime

    protected abstract fun latestUpdatesNextPageSelector(): String?

    // --------------------------------------------------------------- search

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = document.select(searchAnimeSelector())
            .map { searchAnimeFromElement(it) }

        val hasNextPage = searchAnimeNextPageSelector()
            ?.let { document.selectFirst(it) != null }
            ?: false

        return AnimesPage(animes, hasNextPage)
    }

    protected abstract fun searchAnimeSelector(): String

    protected abstract fun searchAnimeFromElement(element: Element): SAnime

    protected abstract fun searchAnimeNextPageSelector(): String?

    // -------------------------------------------------------------- details

    override fun animeDetailsParse(response: Response): SAnime =
        animeDetailsParse(response.asJsoup())

    protected abstract fun animeDetailsParse(document: Document): SAnime

    // ------------------------------------------------------------- episodes

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector()).map { episodeFromElement(it) }
    }

    protected abstract fun episodeListSelector(): String

    protected abstract fun episodeFromElement(element: Element): SEpisode

    // --------------------------------------------------------------- videos

    override fun videoListParse(response: Response): List<eu.kanade.tachiyomi.animesource.model.Video> {
        val document = response.asJsoup()
        return document.select(videoListSelector()).map { videoFromElement(it) }
    }

    protected open fun videoListSelector(): String =
        throw UnsupportedOperationException("Not implemented")

    protected open fun videoFromElement(
        element: Element,
    ): eu.kanade.tachiyomi.animesource.model.Video =
        throw UnsupportedOperationException("Not implemented")

    protected open fun videoUrlParse(document: Document): String =
        throw UnsupportedOperationException("Not implemented")
}
