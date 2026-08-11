package space.nicart.watchbox.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for which artwork a hero draws.
 *
 * The TV home's spotlight fills the screen, so it asks for TMDB's `original` transform
 * rather than the `w1280` one the phone hero and the detail pages use - w1280 is narrower
 * than a 1080p panel, let alone a 4K one, and upscaling is obvious at that size.
 *
 * The fallback order is the point of these tests. Artwork arrives asynchronously and
 * partially: a card may have a source poster and no TMDB match at all, or a TMDB match
 * from a build that predates the full-resolution field. Every one of those has to yield
 * *something*, because the alternative is a black screen behind the spotlight.
 */
class HeroImageTest {

    private fun card(
        posterUrl: String? = null,
        backdropUrl: String? = null,
        heroBackdropUrl: String? = null,
        tmdbPosterUrl: String? = null,
    ) = AnimeCard(
        sourceId = 1L,
        url = "/movie/603",
        title = "The Matrix",
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        heroBackdropUrl = heroBackdropUrl,
        tmdbPosterUrl = tmdbPosterUrl,
    )

    @Test
    fun `a full-bleed hero prefers the full-resolution backdrop`() {
        val card = card(
            posterUrl = "https://src/poster.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/w1280/b.jpg",
            heroBackdropUrl = "https://image.tmdb.org/t/p/original/b.jpg",
            tmdbPosterUrl = "https://image.tmdb.org/t/p/w500/p.jpg",
        )

        assertEquals("https://image.tmdb.org/t/p/original/b.jpg", card.fullBleedImage)
    }

    /**
     * The smaller hero keeps using w1280. A full-screen asset is several times the bytes
     * and the phone hero and detail pages draw it at a fraction of the size, so raising
     * them both to `original` would cost bandwidth for no visible gain.
     */
    @Test
    fun `the smaller hero still uses the w1280 backdrop`() {
        val card = card(
            posterUrl = "https://src/poster.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/w1280/b.jpg",
            heroBackdropUrl = "https://image.tmdb.org/t/p/original/b.jpg",
        )

        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", card.heroImage)
    }

    /**
     * A card enriched before the full-resolution field existed, or by a TMDB response
     * with no backdrop path. Falling through to w1280 shows a slightly soft image;
     * returning null would show nothing.
     */
    @Test
    fun `it falls back to the w1280 backdrop when there is no full-resolution one`() {
        val card = card(
            posterUrl = "https://src/poster.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/w1280/b.jpg",
        )

        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", card.fullBleedImage)
    }

    /** No backdrop at all: a portrait poster cropped to the screen beats a black screen. */
    @Test
    fun `it falls back through the posters when there is no backdrop`() {
        assertEquals(
            "https://image.tmdb.org/t/p/w500/p.jpg",
            card(
                posterUrl = "https://src/poster.jpg",
                tmdbPosterUrl = "https://image.tmdb.org/t/p/w500/p.jpg",
            ).fullBleedImage,
        )

        // TMDB never matched, so the source's own poster is all there is.
        assertEquals(
            "https://src/poster.jpg",
            card(posterUrl = "https://src/poster.jpg").fullBleedImage,
        )
    }

    /** Nothing to show. Must be null rather than a blank string, which Coil would fetch. */
    @Test
    fun `it is null when the card carries no artwork`() {
        assertNull(card().fullBleedImage)
    }
}
