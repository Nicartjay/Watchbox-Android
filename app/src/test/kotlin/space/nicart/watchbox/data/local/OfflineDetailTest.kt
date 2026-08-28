package space.nicart.watchbox.data.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import space.nicart.watchbox.domain.AnimeDetail
import space.nicart.watchbox.domain.AnimeStatus
import space.nicart.watchbox.domain.EpisodeEntry

/**
 * Tests for the page cached when a download starts.
 *
 * This is what makes a downloaded show openable with the network off. The failure it prevents
 * is quiet and total: the episode list lives on the detail, so a page that will not load leaves
 * a downloaded episode with no row to play it from - the library looks broken in exactly the
 * situation downloads exist for.
 *
 * The round trip is pinned because it is stored as JSON and read back a session later, so a
 * field that fails to survive is not noticed until someone is offline.
 */
class OfflineDetailTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun episode(
        url: String,
        name: String,
        number: Float,
        season: Int? = null,
    ) = EpisodeEntry(
        url = url,
        name = name,
        number = number,
        season = season,
        dateUpload = 0L,
        scanlator = null,
        overview = "What happens.",
        runtimeMinutes = 24,
    )

    private fun detail(
        episodes: List<EpisodeEntry> = listOf(episode("/e1", "Episode 1", 1f)),
    ) = AnimeDetail(
        sourceId = 7L,
        sourceName = "Rentaro",
        url = "/anime/test",
        title = "A Show",
        posterUrl = "https://img.test/poster.jpg",
        backdropUrl = "https://img.test/backdrop.jpg",
        year = "2024",
        rating = 8.4,
        description = "A synopsis worth keeping.",
        author = "Someone",
        artist = "Someone Else",
        genres = listOf("Action", "Drama"),
        status = AnimeStatus.ONGOING,
        episodes = episodes,
    )

    // ------------------------------------------------------------ capturing

    @Test
    fun `keeps what the page needs to render`() {
        val cached = OfflineDetail.from(detail(), "/files/poster", "/files/backdrop", 100L)

        assertEquals("A Show", cached.title)
        assertEquals("A synopsis worth keeping.", cached.description)
        assertEquals(listOf("Action", "Drama"), cached.genres)
        assertEquals("2024", cached.year)
        assertEquals(8.4, cached.rating)
        assertEquals(100L, cached.savedAt)
    }

    @Test
    fun `stores local artwork paths rather than remote urls`() {
        // The point of caching: the image loader has no network offline, so a remote URL would
        // render as a blank card.
        val cached = OfflineDetail.from(detail(), "/files/poster", "/files/backdrop", 0L)

        assertEquals("/files/poster", cached.posterPath)
        assertEquals("/files/backdrop", cached.backdropPath)
    }

    @Test
    fun `survives artwork that could not be fetched`() {
        // A stored path pointing at nothing is worse than none: the loader would show a broken
        // image instead of falling back to its placeholder.
        val cached = OfflineDetail.from(detail(), null, null, 0L)

        assertNull(cached.posterPath)
        assertNull(cached.backdropPath)
    }

    @Test
    fun `keys by source and url so two sources do not collide`() {
        val cached = OfflineDetail.from(detail(), null, null, 0L)

        assertEquals("7::/anime/test", cached.key)
    }

    // ------------------------------------------------------------ restoring

    @Test
    fun `restores a detail the page can render`() {
        val restored = OfflineDetail.from(detail(), "/files/poster", null, 0L).toDetail()

        assertEquals("A Show", restored.title)
        assertEquals("Rentaro", restored.sourceName)
        assertEquals(7L, restored.sourceId)
        assertEquals("/anime/test", restored.url)
        assertEquals("/files/poster", restored.posterUrl)
        assertEquals(AnimeStatus.ONGOING, restored.status)
    }

    @Test
    fun `restores every episode, not only the downloaded ones`() {
        // The improvement over rebuilding from the download registry, which only knew about
        // episodes already on disk and so hid the rest of the series.
        val many = listOf(
            episode("/e1", "Episode 1", 1f),
            episode("/e2", "Episode 2", 2f),
            episode("/e3", "Episode 3", 3f),
        )
        val restored = OfflineDetail.from(detail(many), null, null, 0L).toDetail()

        assertEquals(3, restored.episodes.size)
        assertEquals(listOf("/e1", "/e2", "/e3"), restored.episodes.map { it.url })
    }

    @Test
    fun `keeps season numbers so a multi-season show stays grouped`() {
        val seasoned = listOf(
            episode("/s1e1", "S1 E1", 1f, season = 1),
            episode("/s2e1", "S2 E1", 1f, season = 2),
        )
        val restored = OfflineDetail.from(detail(seasoned), null, null, 0L).toDetail()

        assertEquals(listOf(1, 2), restored.episodes.map { it.season })
    }

    @Test
    fun `keeps episode overview and runtime`() {
        val restored = OfflineDetail.from(detail(), null, null, 0L).toDetail()
        val first = restored.episodes.first()

        assertEquals("What happens.", first.overview)
        assertEquals(24, first.runtimeMinutes)
    }

    @Test
    fun `a film restores as a film`() {
        // isMovie is inferred from the episode count, so a single-entry title has to stay
        // single-entry or the page grows an episode list it should not have.
        val restored = OfflineDetail
            .from(detail(listOf(episode("/film", "The Film", 1f))), null, null, 0L)
            .toDetail()

        assertTrue(restored.isMovie)
    }

    @Test
    fun `leaves online-only sections empty`() {
        // Suggestions, TMDB extras and studios are not cached: they cannot be reached offline
        // and their own defaults already mean "do not draw this section".
        val restored = OfflineDetail.from(detail(), null, null, 0L).toDetail()

        assertTrue(restored.suggestions.isEmpty())
        assertTrue(restored.studios.isEmpty())
        assertTrue(restored.extras.videos.isEmpty())
    }

    // ---------------------------------------------------------- persistence

    @Test
    fun `survives a json round trip`() {
        val cached = OfflineDetail.from(detail(), "/files/poster", "/files/backdrop", 55L)

        val restored = json.decodeFromString<OfflineDetail>(json.encodeToString(cached))

        assertEquals(cached, restored)
    }

    @Test
    fun `a full round trip through json still renders`() {
        val cached = OfflineDetail.from(detail(), "/files/poster", null, 0L)

        val detail = json.decodeFromString<OfflineDetail>(json.encodeToString(cached)).toDetail()

        assertEquals("A Show", detail.title)
        assertEquals(AnimeStatus.ONGOING, detail.status)
        assertEquals(1, detail.episodes.size)
    }

    @Test
    fun `an unrecognised status decodes to unknown`() {
        // Stored by name, so a renamed constant must degrade rather than fail the whole read
        // and lose every cached page.
        val stored = OfflineDetail(
            sourceId = 1L,
            sourceName = "S",
            url = "/u",
            title = "T",
            statusName = "SOMETHING_REMOVED",
        )

        assertEquals(AnimeStatus.UNKNOWN, stored.toDetail().status)
    }
}
