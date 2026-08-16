package space.nicart.watchbox.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests the artwork language choice.
 *
 * The fallback chain is what needs pinning, not the preference. TMDB's coverage outside
 * English is patchy, so a strict match would leave most titles with no logo - and a missing
 * logo is worse than one in the wrong language, because the hero then drops to plain text.
 */
class ArtworkLanguageTest {

    private fun c(path: String, lang: String?, vote: Double = 0.0) =
        ArtworkCandidate(path, lang, vote)

    @Test
    fun `the requested language wins when present`() {
        val images = listOf(c("/en.png", "en", 9.0), c("/ja.png", "ja", 1.0))

        assertEquals("/ja.png", pickArtworkByLanguage(images, "ja"))
    }

    @Test
    fun `a higher vote wins within the requested language`() {
        val images = listOf(c("/ja-low.png", "ja", 2.0), c("/ja-high.png", "ja", 8.0))

        assertEquals("/ja-high.png", pickArtworkByLanguage(images, "ja"))
    }

    @Test
    fun `english is the first fallback`() {
        // The requested language is absent, so English is used rather than nothing.
        val images = listOf(c("/en.png", "en"), c("/fr.png", "fr"))

        assertEquals("/en.png", pickArtworkByLanguage(images, "ja"))
    }

    @Test
    fun `language-neutral comes after english`() {
        // Neutral entries are usually the textless cut - right for a poster behind a logo,
        // wrong for the logo itself, so English is preferred first.
        val images = listOf(c("/neutral.png", null), c("/en.png", "en"))

        assertEquals("/en.png", pickArtworkByLanguage(images, "ja"))
        assertEquals("/neutral.png", pickArtworkByLanguage(listOf(c("/neutral.png", null)), "ja"))
    }

    @Test
    fun `anything is better than nothing`() {
        // Neither the request nor English nor neutral exists, so the best of what remains
        // is used - a logo in the wrong language still beats falling back to plain text.
        val images = listOf(c("/fr.png", "fr", 3.0), c("/de.png", "de", 7.0))

        assertEquals("/de.png", pickArtworkByLanguage(images, "ja"))
    }

    @Test
    fun `svg entries are never chosen`() {
        // Coil has no SVG decoder registered, so these fail to decode and the caller
        // silently gets nothing - the same bug that hid The Mentalist's logo.
        val images = listOf(c("/logo.svg", "ja", 9.0), c("/logo.png", "en", 1.0))

        assertEquals("/logo.png", pickArtworkByLanguage(images, "ja"))
    }

    @Test
    fun `an all-svg set yields nothing rather than an undecodable url`() {
        assertNull(pickArtworkByLanguage(listOf(c("/a.svg", "en"), c("/b.svg", null)), "en"))
    }

    @Test
    fun `a blank language falls back to the default`() {
        val images = listOf(c("/en.png", "en"), c("/ja.png", "ja"))

        assertEquals("/en.png", pickArtworkByLanguage(images, ""))
        assertEquals("/en.png", pickArtworkByLanguage(images, "   "))
    }

    @Test
    fun `the language code is matched case-insensitively`() {
        // A value written by an older build may not be normalised.
        val images = listOf(c("/ja.png", "ja"))

        assertEquals("/ja.png", pickArtworkByLanguage(images, "JA"))
    }

    @Test
    fun `an empty set yields nothing`() {
        assertNull(pickArtworkByLanguage(emptyList(), "en"))
    }
}
