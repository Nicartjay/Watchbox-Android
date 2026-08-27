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

    // ------------------------------------------------- merged (sideloaded) audio

    @Test
    fun `pairs the tail of the group list with the source's audio`() {
        // One track in the file, two merged in: the merge appends after the video, so the
        // last two groups are the source's.
        assertEquals(1, mergedAudioOffset(groupCount = 3, suppliedCount = 2))
    }

    @Test
    fun `offset passes the end when every track came from the file`() {
        // Nothing was merged, so the offset lands past the last group and every lookup for
        // a source label misses - which is what leaves a file's own tracks named by their
        // own metadata.
        val offset = mergedAudioOffset(groupCount = 2, suppliedCount = 0)

        assertEquals(2, offset)
        assertEquals(-1, 1 - offset)
    }

    @Test
    fun `offset clamps at zero before the groups arrive`() {
        // The stream declares audio but no track group has been parsed yet. A negative
        // offset here would pair a file's own track with someone else's label.
        assertEquals(0, mergedAudioOffset(groupCount = 0, suppliedCount = 2))
    }

    @Test
    fun `names a merged track from what the source said`() {
        // The usual case for a separate audio playlist: the media carries no metadata at
        // all, because the naming lived in the master playlist.
        val result = mergedAudioTrack(
            containerLabel = null,
            containerLanguage = null,
            suppliedLabel = "English",
            suppliedLanguage = "English",
            fallback = "Audio track 2",
        )

        assertEquals("English", result.label)
        assertEquals("English", result.language)
    }

    @Test
    fun `prefers the container over the source`() {
        val result = mergedAudioTrack(
            containerLabel = "Japanese 5.1",
            containerLanguage = "ja",
            suppliedLabel = "Japanese",
            suppliedLanguage = "ja",
            fallback = "Audio track 1",
        )

        assertEquals("Japanese 5.1", result.label)
        assertEquals("ja", result.language)
    }

    @Test
    fun `an undetermined container language does not beat the source`() {
        // "und" is what a container writes when it does not know. Letting it win would
        // store an unmatchable preference, and the choice would not carry to the next
        // episode.
        val result = mergedAudioTrack(
            containerLabel = null,
            containerLanguage = "und",
            suppliedLabel = "English",
            suppliedLanguage = "en",
            fallback = "Audio track 1",
        )

        assertEquals("en", result.language)
    }

    @Test
    fun `falls back when neither names the track`() {
        val result = mergedAudioTrack(
            containerLabel = null,
            containerLanguage = null,
            suppliedLabel = null,
            suppliedLanguage = null,
            fallback = "Audio track 3",
        )

        assertEquals("Audio track 3", result.label)
        assertEquals("", result.language)
    }

    @Test
    fun `a merged track stays selectable by the name the source gave it`() {
        // End to end: the source names a track "English" with no code, so it is stored by
        // label - and this is what finds it again on the next episode.
        val merged = mergedAudioTrack(
            containerLabel = null,
            containerLanguage = null,
            suppliedLabel = "English",
            suppliedLanguage = "English",
            fallback = "Audio track 2",
        )
        val tracks = listOf(track("Japanese", "ja"), merged)

        assertEquals(1, tracks.indexOfLanguage("English"))
    }
}
