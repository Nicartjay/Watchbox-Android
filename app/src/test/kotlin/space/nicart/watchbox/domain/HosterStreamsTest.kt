package space.nicart.watchbox.domain

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response

/**
 * Tests for folding a lib 16 source's hosters into the player's progressive stream list.
 *
 * The rule that matters most is when the first batch goes out. The player plays the first
 * emission and never switches on its own, so emitting whichever hoster answered first would
 * let a fast server override the order the source chose from the viewer's settings - a dub
 * playing for someone whose extension is set to sub.
 */
class HosterStreamsTest {

    @Test
    fun `labels each stream with the hoster it came from`() = runBlocking {
        val source = FakeSource(
            listOf(
                Hoster(hosterName = "Kwik", videoList = listOf(video("1080p"))),
                Hoster(hosterName = "Vidstream", videoList = listOf(video("720p"))),
            ),
        )

        assertEquals(
            listOf("Kwik · 1080p", "Vidstream · 720p"),
            source.streams().last().map { it.label },
        )
    }

    @Test
    fun `a wrapped plain list adds no server name`() = runBlocking {
        val source = FakeSource(listOf(video("1080p")).toHosterList())

        assertEquals(listOf("1080p"), source.streams().last().map { it.label })
    }

    @Test
    fun `a title that already names its server is not prefixed again`() = runBlocking {
        val source = FakeSource(
            listOf(Hoster(hosterName = "Kwik", videoList = listOf(video("Kwik · 1080p")))),
        )

        assertEquals(listOf("Kwik · 1080p"), source.streams().last().map { it.label })
    }

    @Test
    fun `the first batch waits for the hoster the source ranked first`() = runBlocking {
        val second = CompletableDeferred<Unit>()
        val source = FakeSource(
            listOf(Hoster(hosterName = "Sub"), Hoster(hosterName = "Dub")),
            videos = mapOf(
                // Ranked first but slower: answers only once the second has.
                "Sub" to { second.await(); listOf(video("1080p")) },
                "Dub" to { listOf(video("1080p")).also { second.complete(Unit) } },
            ),
        )

        val emissions = source.streams()

        assertTrue(emissions.all { it.first().label == "Sub · 1080p" })
        assertEquals(listOf("Sub · 1080p", "Dub · 1080p"), emissions.last().map { it.label })
    }

    @Test
    fun `a hoster that fails does not hold the first batch`() = runBlocking {
        val source = FakeSource(
            listOf(Hoster(hosterName = "Broken"), Hoster(hosterName = "Working")),
            videos = mapOf(
                "Broken" to { error("down") },
                "Working" to { listOf(video("720p")) },
            ),
        )

        assertEquals(listOf(listOf("Working · 720p")), source.streams().map { b -> b.map { it.label } })
    }

    @Test
    fun `a lazy hoster loads last and does not hold the first batch`() = runBlocking {
        val source = FakeSource(
            listOf(
                Hoster(hosterName = "Heavy", lazy = true),
                Hoster(hosterName = "Light", videoList = listOf(video("720p"))),
            ),
            videos = mapOf("Heavy" to { listOf(video("1080p")) }),
        )

        val emissions = source.streams().map { batch -> batch.map { it.label } }

        assertEquals(listOf("Light · 720p"), emissions.first())
        // Hoster order once both are in, which reorders but drops nothing.
        assertEquals(listOf("Heavy · 1080p", "Light · 720p"), emissions.last())
    }

    @Test
    fun `a preferred stream goes out at once and leads the list`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val source = FakeSource(
            listOf(
                Hoster(hosterName = "Slow"),
                Hoster(hosterName = "Pick", videoList = listOf(video("1080p", preferred = true))),
            ),
            videos = mapOf("Slow" to { release.await(); listOf(video("720p")) }),
        )

        val emissions = mutableListOf<List<String>>()
        source.hosterStreams(SEpisode.create()).collect { batch ->
            emissions += batch.map { it.label }
            release.complete(Unit)
        }

        assertEquals(listOf("Pick · 1080p"), emissions.first())
        assertEquals(listOf("Pick · 1080p", "Slow · 720p"), emissions.last())
    }

    @Test
    fun `only uninitialized videos are resolved, and one that fails is dropped`() = runBlocking {
        val resolved = mutableListOf<String>()
        val source = FakeSource(
            listOf(
                Hoster(
                    hosterName = "H",
                    videoList = listOf(
                        video("ready", initialized = true),
                        video("later"),
                        video("gone"),
                    ),
                ),
            ),
            resolve = { video ->
                resolved += video.videoTitle
                if (video.videoTitle == "gone") null else video.copy(videoUrl = "https://cdn/final")
            },
        )

        val streams = source.streams().last()

        assertEquals(setOf("later", "gone"), resolved.toSet())
        assertEquals(2, resolved.size)
        assertEquals(listOf("H · ready", "H · later"), streams.map { it.label })
        assertEquals("https://cdn/final", streams[1].url)
    }

    @Test
    fun `a lib 14 video with no url is fetched from its page`() = runBlocking {
        val source = FakeSource(
            listOf(Hoster(hosterName = "Old", videoList = listOf(lib14Video("https://page")))),
            videoUrl = { video -> "https://media/for/" + video.url.substringAfterLast('/') },
        )

        assertEquals("https://media/for/page", source.streams().last().single().url)
    }

    @Test
    fun `with nothing playable the first failure is reported`() = runBlocking {
        val source = FakeSource(
            listOf(Hoster(hosterName = "A"), Hoster(hosterName = "B")),
            videos = mapOf("A" to { error("first") }, "B" to { error("second") }),
        )

        val failure = runCatching { source.streams() }.exceptionOrNull()

        assertTrue(failure?.message in setOf("first", "second"))
    }

    @Test
    fun `no hosters means no emission rather than an empty one`() = runBlocking {
        assertEquals(emptyList<List<StreamOption>>(), FakeSource(emptyList()).streams())
    }

    private suspend fun FakeSource.streams() = hosterStreams(SEpisode.create()).toList()

    /** Typed nulls pick the lib 14 constructor; a bare null matches three of them. */
    private fun lib14Video(page: String) = Video(page, "480p", null as String?, null as Headers?)

    private fun video(
        title: String,
        preferred: Boolean = false,
        initialized: Boolean = false,
    ) = Video(
        videoUrl = "https://cdn/$title",
        videoTitle = title,
        preferred = preferred,
        initialized = initialized,
    )

    /** Overrides only what a lib 16 source overrides; the request plumbing is never reached. */
    private class FakeSource(
        private val hosters: List<Hoster>,
        private val videos: Map<String, suspend () -> List<Video>> = emptyMap(),
        private val resolve: suspend (Video) -> Video? = { it },
        private val videoUrl: suspend (Video) -> String = { error("not expected") },
    ) : AnimeHttpSource() {
        override val name = "Fake"
        override val lang = "en"
        override val baseUrl = "https://example.invalid"
        override val supportsLatest = false

        override suspend fun getHosterList(episode: SEpisode): List<Hoster> = hosters

        override suspend fun getVideoList(hoster: Hoster): List<Video> =
            videos.getValue(hoster.hosterName)()

        override suspend fun resolveVideo(video: Video): Video? = resolve(video)

        override suspend fun getVideoUrl(video: Video): String = videoUrl(video)

        override fun popularAnimeRequest(page: Int): Request = unused()
        override fun popularAnimeParse(response: Response): AnimesPage = unused()
        override fun latestUpdatesRequest(page: Int): Request = unused()
        override fun latestUpdatesParse(response: Response): AnimesPage = unused()
        override fun searchAnimeRequest(
            page: Int,
            query: String,
            filters: AnimeFilterList,
        ): Request = unused()
        override fun searchAnimeParse(response: Response): AnimesPage = unused()
        override fun animeDetailsParse(response: Response): SAnime = unused()
        override fun episodeListParse(response: Response): List<SEpisode> = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException()
    }
}
