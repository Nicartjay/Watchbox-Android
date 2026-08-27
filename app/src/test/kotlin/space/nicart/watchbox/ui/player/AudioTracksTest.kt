package space.nicart.watchbox.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for naming and matching the audio tracks inside a stream.
 *
 * These back the audio picker's two jobs: reading a track's name out of whatever the
 * container provided, and finding the viewer's remembered language in the next file. The
 * matching is the part worth pinning - it is what makes a choice survive an episode change,
 * and the failure is silent, since the wrong track simply plays.
 */
class AudioTracksTest {

    private fun track(label: String, language: String) =
        EmbeddedAudioTrack(label = label, language = language)

    // ------------------------------------------------------------ labelling

    @Test
    fun `prefers the container label`() {
        assertEquals(
            "Commentary",
            audioTrackLabel(rawLabel = "Commentary", language = "eng", fallback = "Audio 1"),
        )
    }

    @Test
    fun `renders a language code as a name`() {
        assertEquals(
            "Japanese",
            audioTrackLabel(rawLabel = null, language = "ja", fallback = "Audio 1"),
        )
    }

    @Test
    fun `renders a three letter code as a name`() {
        assertEquals(
            "Japanese",
            audioTrackLabel(rawLabel = null, language = "jpn", fallback = "Audio 1"),
        )
    }

    @Test
    fun `falls back when the container names nothing`() {
        assertEquals(
            "Audio 2",
            audioTrackLabel(rawLabel = null, language = null, fallback = "Audio 2"),
        )
    }

    @Test
    fun `treats undetermined as unnamed`() {
        assertEquals(
            "Audio 1",
            audioTrackLabel(rawLabel = null, language = "und", fallback = "Audio 1"),
        )
    }

    @Test
    fun `treats a blank label as unnamed`() {
        assertEquals(
            "Audio 1",
            audioTrackLabel(rawLabel = "   ", language = null, fallback = "Audio 1"),
        )
    }

    @Test
    fun `keeps an unrecognisable code rather than showing the fallback`() {
        // Says more than "Audio 1" does: the code came from the file, so it at least
        // distinguishes this track from the next one.
        assertEquals(
            "zzz",
            audioTrackLabel(rawLabel = null, language = "zzz", fallback = "Audio 1"),
        )
    }

    // ------------------------------------------------------------- matching

    @Test
    fun `finds the stored language`() {
        val tracks = listOf(track("Japanese", "ja"), track("English", "en"))

        assertEquals(1, tracks.indexOfLanguage("en"))
    }

    @Test
    fun `matches a region tagged track on its primary subtag`() {
        // The preference may have been set from a release tagging plain "en", and this is
        // what carries it to one tagging "en-US".
        val tracks = listOf(track("Japanese", "ja"), track("English", "en-US"))

        assertEquals(1, tracks.indexOfLanguage("en"))
    }

    @Test
    fun `matches an underscore separated tag`() {
        val tracks = listOf(track("English", "en_GB"))

        assertEquals(0, tracks.indexOfLanguage("en"))
    }

    @Test
    fun `ignores case`() {
        val tracks = listOf(track("English", "EN"))

        assertEquals(0, tracks.indexOfLanguage("en"))
    }

    @Test
    fun `reports no match when the language is absent`() {
        // The player's own default has to stand here. Forcing the first track instead would
        // override the original audio of every file lacking the stored language.
        val tracks = listOf(track("Japanese", "ja"), track("Spanish", "es"))

        assertEquals(-1, tracks.indexOfLanguage("en"))
    }

    @Test
    fun `reports no match for a blank preference`() {
        val tracks = listOf(track("Japanese", "ja"))

        assertEquals(-1, tracks.indexOfLanguage(""))
    }

    @Test
    fun `reports no match on an empty track list`() {
        assertEquals(-1, emptyList<EmbeddedAudioTrack>().indexOfLanguage("en"))
    }

    @Test
    fun `falls back to the label for an untagged track`() {
        // A container need not tag its audio, and such a track is stored by label. Matching
        // it back is what makes choosing one stick within the same release.
        val tracks = listOf(track("Audio 1", ""), track("Commentary", ""))

        assertEquals(1, tracks.indexOfLanguage("Commentary"))
    }

    @Test
    fun `prefers a language match over a label match`() {
        // A label that happens to read like a stored code must not outrank the real tag.
        val tracks = listOf(track("en", ""), track("English", "en"))

        assertEquals(1, tracks.indexOfLanguage("en"))
    }

    @Test
    fun `takes the first of two tracks sharing a language`() {
        // A dub and its commentary are routinely both tagged "eng". Nothing here can tell
        // them apart, so the first is chosen and the panel remains the way to reach the
        // other - which is why selection overrides the group rather than setting a
        // preferred language on the player.
        val tracks = listOf(track("English", "en"), track("Commentary", "en"))

        assertEquals(0, tracks.indexOfLanguage("en"))
    }
}
