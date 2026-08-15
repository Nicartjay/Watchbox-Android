package space.nicart.watchbox.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the TMDB candidate check.
 *
 * Worth pinning because a wrong accept is permanent in practice: the resolved id is
 * cached and never reconsidered, so a title that matches the wrong show keeps the
 * wrong artwork, synopsis and score for the rest of the session.
 *
 * The rejection cases matter more than the acceptances. A substring test would pass
 * every "accepts" case below and still be wrong - the asymmetry between "Monster
 * Season 2" (a sequel) and "Monster Musume" (a different show) is the entire point.
 */
class TmdbTitleMatchTest {

    // ---------------------------------------------------------------- normalise

    @Test
    fun `normalising collapses punctuation and case`() {
        assertEquals("rezero", TmdbApi.normaliseTitle("Re:ZERO"))
        assertEquals("rezero", TmdbApi.normaliseTitle("Re Zero"))
        assertEquals("rezero", TmdbApi.normaliseTitle("RE-ZERO"))
        assertEquals("attackontitan", TmdbApi.normaliseTitle("Attack on Titan!"))
    }

    @Test
    fun `a non-latin title collapses to empty`() {
        // Must be treated as "cannot verify" by callers, not as a mismatch.
        assertEquals("", TmdbApi.normaliseTitle("進撃の巨人"))
    }

    // ------------------------------------------------------------------ accepts

    @Test
    fun `an exact match is accepted regardless of punctuation`() {
        assertTrue(TmdbApi.titleMatches("Re:Zero", "Re ZERO"))
        assertTrue(TmdbApi.titleMatches("Attack on Titan", "attack on titan"))
    }

    @Test
    fun `a season suffix is accepted`() {
        assertTrue(TmdbApi.titleMatches("Monster", "Monster Season 2"))
        assertTrue(TmdbApi.titleMatches("Overlord", "Overlord III"))
        assertTrue(TmdbApi.titleMatches("Re Zero", "Re:ZERO 2nd Season"))
        assertTrue(TmdbApi.titleMatches("Bleach", "Bleach S2"))
        assertTrue(TmdbApi.titleMatches("Vinland Saga", "Vinland Saga Season"))
        assertTrue(TmdbApi.titleMatches("Attack on Titan", "Attack on Titan Final Season"))
        assertTrue(TmdbApi.titleMatches("Kaguya-sama", "Kaguya-sama Part 2"))
        assertTrue(TmdbApi.titleMatches("Spy x Family", "Spy x Family Cour 2"))
    }

    @Test
    fun `roman numerals past six are accepted`() {
        // Zangetsu stops at VI; long-running franchises go further.
        assertTrue(TmdbApi.titleMatches("Fate", "Fate VII"))
        assertTrue(TmdbApi.titleMatches("Fate", "Fate X"))
    }

    // ------------------------------------------------------------------ rejects

    @Test
    fun `a different show sharing a prefix is rejected`() {
        // The case that makes a plain prefix test unusable.
        assertFalse(TmdbApi.titleMatches("Monster", "Monster Musume"))
        assertFalse(TmdbApi.titleMatches("Bleach", "Bleach Burn the Witch"))
    }

    @Test
    fun `a subtitle is not a season marker`() {
        assertFalse(TmdbApi.titleMatches("Frieren", "Frieren Beyond Journey's End"))
    }

    @Test
    fun `an unrelated title is rejected`() {
        assertFalse(TmdbApi.titleMatches("Naruto", "One Piece"))
    }

    @Test
    fun `a shorter candidate is rejected`() {
        // Reversed containment must not pass: "Naruto" is not "Naruto Shippuden".
        assertFalse(TmdbApi.titleMatches("Naruto Shippuden", "Naruto"))
    }

    @Test
    fun `empty or unverifiable input is rejected`() {
        assertFalse(TmdbApi.titleMatches("", "Monster"))
        assertFalse(TmdbApi.titleMatches("Monster", ""))
        // Non-Latin on either side cannot be verified.
        assertFalse(TmdbApi.titleMatches("進撃の巨人", "Attack on Titan"))
    }

    // ----------------------------------------------- observed TMDB behaviour

    @Test
    fun `the exact show is preferred over a sequel TMDB ranks first`() {
        // Both verified against live TMDB responses: searching "Dragon Ball" returns
        // "Dragon Ball Z" first, and "Yu-Gi-Oh!" returns "Yu-Gi-Oh! Zexal" first.
        // The base entry is further down the list, and it is the correct answer.
        assertTrue(TmdbApi.titleMatches("Dragon Ball", "Dragon Ball"))
        assertFalse(TmdbApi.titleMatches("Dragon Ball", "Dragon Ball Z"))

        assertTrue(TmdbApi.titleMatches("Yu-Gi-Oh!", "Yu-Gi-Oh!"))
        assertFalse(TmdbApi.titleMatches("Yu-Gi-Oh!", "Yu-Gi-Oh! Zexal"))
    }

    @Test
    fun `a TMDB subtitle does not verify, so the ranking is trusted instead`() {
        // TMDB routinely decorates a name - "Re:ZERO -Starting Life in Another
        // World-" for a source's "Re:Zero". These CANNOT be verified by title, which
        // is why an unverified first result is still accepted as a fallback. Pinned
        // so the fallback is never mistaken for an oversight and removed.
        assertFalse(
            TmdbApi.titleMatches("Re:Zero", "Re:ZERO -Starting Life in Another World-"),
        )
        assertFalse(TmdbApi.titleMatches("Shingeki no Kyojin", "Attack on Titan"))
    }

    // --------------------------------------------------- interaction with clean

    @Test
    fun `a cleaned source title verifies against the plain TMDB name`() {
        // The real pipeline: cleanTitle strips the decorations, then the result is
        // verified against what TMDB returned.
        val cleaned = TmdbApi.cleanTitle("Attack on Titan Season 4 (Dub)")

        assertTrue(TmdbApi.titleMatches(cleaned, "Attack on Titan"))
    }
}
