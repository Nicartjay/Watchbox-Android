package space.nicart.watchbox.ui.player

import space.nicart.watchbox.domain.EpisodeEntry
import space.nicart.watchbox.domain.StreamOption
import space.nicart.watchbox.domain.SubtitleOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleSelectionTest {
    @Test
    fun `no subtitle tracks keeps the reels subtitle icon hidden`() {
        assertFalse(state().hasSubtitleTracks)
        assertFalse(PlayerUiState().hasSubtitleTracks)
    }

    @Test
    fun `source or embedded tracks make the subtitle icon available`() {
        assertTrue(state(tracks = listOf(subtitle("English", "en"))).hasSubtitleTracks)
        assertTrue(
            state(embedded = listOf(EmbeddedSubtitleTrack("English", "en"))).hasSubtitleTracks,
        )
    }

    @Test
    fun `turning subtitles Off does not hide the way to turn available tracks back on`() {
        assertTrue(
            state(
                preference = SubtitlePreference(),
                tracks = listOf(subtitle("English", "en")),
            ).hasSubtitleTracks,
        )
    }

    @Test
    fun `a remembered language alone is not an available subtitle track`() {
        assertFalse(
            state(
                preference = sourceSubtitlePreference(subtitle("English", "en", "previous.vtt")),
                episodeUrl = "next",
            ).hasSubtitleTracks,
        )
    }

    @Test
    fun `a downloaded subtitle for the current episode also exposes the subtitle icon`() {
        assertTrue(
            state().copy(
                externalSubtitles = listOf(
                    subtitle("English", "en", "file:///current.srt").copy(isDownloaded = true),
                ),
            ).hasSubtitleTracks,
        )
    }

    @Test
    fun `language names ISO codes and regional codes match the same subtitle preference`() {
        listOf("English", "en", "EN-us", "eng").forEach { language ->
            val state = state(
                tracks = listOf(subtitle("Spanish", "es"), subtitle("English", language)),
            ).withSubtitleSelection()
            assertEquals(1, state.selectedSubtitleIndex, language)
        }
        assertEquals("pt", subtitleLanguageCode("Portuguese (Brazil)"))
        assertEquals("en", subtitleLanguageCode("", "English"))
    }

    @Test
    fun `episode changes match a language rather than the previous index or subtitle URL`() {
        val first = subtitle("English", "en", "https://subs.test/ep1/en.vtt")
        val preference = sourceSubtitlePreference(first)
        val next = state(
            preference = preference,
            tracks = listOf(
                subtitle("Spanish", "es"),
                subtitle("English", "eng", "https://subs.test/ep2/en.vtt"),
            ),
            episodeUrl = "ep2",
        ).withSubtitleSelection()

        assertEquals(1, next.selectedSubtitleIndex)
        assertEquals("https://subs.test/ep2/en.vtt", next.subtitles[next.selectedSubtitleIndex].url)
    }

    @Test
    fun `Off remains Off for both source and embedded subtitles on the next episode`() {
        val next = state(
            preference = SubtitlePreference(),
            tracks = listOf(subtitle("English", "en")),
            embedded = listOf(EmbeddedSubtitleTrack("English", "en")),
            episodeUrl = "ep2",
        ).withSubtitleSelection()

        assertEquals(-1, next.selectedSubtitleIndex)
        assertEquals(-1, next.selectedEmbeddedSubtitleIndex)
    }

    @Test
    fun `missing languages do not replace or forget the preference`() {
        val missing = state(tracks = listOf(subtitle("Spanish", "es"))).withSubtitleSelection()
        assertEquals(-1, missing.selectedSubtitleIndex)
        assertEquals("en", missing.subtitlePreference.language)

        val availableAgain = missing.copy(
            selectedStream = stream(listOf(subtitle("English", "en"))),
        ).withSubtitleSelection()
        assertEquals(0, availableAgain.selectedSubtitleIndex)
    }

    @Test
    fun `embedded subtitles follow their language when the next episode reorders tracks`() {
        val preference = SubtitlePreference(
            enabled = true,
            language = "en",
            label = "English",
            preferEmbedded = true,
            embeddedIndex = 1,
            embeddedMedia = "ep1" to "video1",
        )
        val next = state(
            preference = preference,
            embedded = listOf(
                EmbeddedSubtitleTrack("English", "eng"),
                EmbeddedSubtitleTrack("Spanish", "spa"),
            ),
            episodeUrl = "ep2",
            streamUrl = "video2",
        ).withSubtitleSelection()

        assertEquals(-1, next.selectedSubtitleIndex)
        assertEquals(0, next.selectedEmbeddedSubtitleIndex)
        assertTrue(next.subtitleIsEmbedded)
    }

    @Test
    fun `duplicate embedded tracks retain the exact manual pick in the same file`() {
        val selected = state(
            preference = SubtitlePreference(
                enabled = true,
                language = "en",
                label = "English",
                preferEmbedded = true,
                embeddedIndex = 1,
                embeddedMedia = "ep1" to "video1",
            ),
            embedded = List(2) { EmbeddedSubtitleTrack("English", "en") },
        ).withSubtitleSelection()
        assertEquals(1, selected.selectedEmbeddedSubtitleIndex)
    }

    @Test
    fun `a source subtitle preference can continue with an embedded track`() {
        val next = state(
            preference = sourceSubtitlePreference(subtitle("English", "en")),
            embedded = listOf(EmbeddedSubtitleTrack("English", "en")),
        ).withSubtitleSelection()

        assertEquals(-1, next.selectedSubtitleIndex)
        assertEquals(0, next.selectedEmbeddedSubtitleIndex)
    }

    @Test
    fun `an embedded subtitle preference can continue with the next source track`() {
        val next = state(
            preference = SubtitlePreference(enabled = true, language = "en", preferEmbedded = true),
            tracks = listOf(subtitle("English", "en")),
        ).withSubtitleSelection()

        assertEquals(0, next.selectedSubtitleIndex)
        assertEquals(-1, next.selectedEmbeddedSubtitleIndex)
    }

    @Test
    fun `embedded preference wins when the same language is also supplied separately`() {
        val selected = state(
            preference = SubtitlePreference(enabled = true, language = "en", preferEmbedded = true),
            tracks = listOf(subtitle("English", "en")),
            embedded = listOf(EmbeddedSubtitleTrack("English", "en")),
        ).withSubtitleSelection()
        assertEquals(-1, selected.selectedSubtitleIndex)
        assertEquals(0, selected.selectedEmbeddedSubtitleIndex)
    }

    @Test
    fun `subtitle variants with the same language keep their matching label`() {
        val next = state(
            preference = sourceSubtitlePreference(subtitle("English CC", "en", "old.vtt")),
            tracks = listOf(
                subtitle("English", "en", "plain.vtt"),
                subtitle("English CC", "en", "next-cc.vtt"),
            ),
        ).withSubtitleSelection()
        assertEquals(1, next.selectedSubtitleIndex)
    }

    @Test
    fun `an unknown language can still retain its source label`() {
        val next = state(
            preference = sourceSubtitlePreference(subtitle("Custom captions", "und", "old.vtt")),
            tracks = listOf(subtitle("Custom captions", "und", "new.vtt")),
        ).withSubtitleSelection()
        assertEquals(0, next.selectedSubtitleIndex)
    }

    @Test
    fun `the previous episode's downloaded file is never carried into the next episode`() {
        val preference = sourceSubtitlePreference(subtitle("English", "en", "file:///ep1.srt"))
        val next = state(preference = preference, episodeUrl = "ep2").withSubtitleSelection()
        assertTrue(next.subtitles.isEmpty())
        assertEquals(-1, next.selectedSubtitleIndex)
        assertTrue(next.subtitlePreference.enabled)
    }

    @Test
    fun `a quality change resolves the current subtitle URL at its new index`() {
        val picked = subtitle("English", "en", "current.vtt")
        val selected = state(
            preference = sourceSubtitlePreference(picked),
            tracks = listOf(subtitle("Spanish", "es")),
        ).copy(externalSubtitles = listOf(picked)).withSubtitleSelection()
        assertEquals(1, selected.selectedSubtitleIndex)

        val changed = selected.copy(
            selectedStream = stream(emptyList(), "another-quality"),
        ).withSubtitleSelection()
        assertEquals(0, changed.selectedSubtitleIndex)
        assertEquals(picked.url, changed.subtitles[changed.selectedSubtitleIndex].url)
    }

    private fun state(
        preference: SubtitlePreference = SubtitlePreference(enabled = true, language = "en"),
        tracks: List<SubtitleOption> = emptyList(),
        embedded: List<EmbeddedSubtitleTrack> = emptyList(),
        episodeUrl: String = "ep1",
        streamUrl: String = "video1",
    ) = PlayerUiState(
        subtitlePreference = preference,
        episode = EpisodeEntry(episodeUrl, "Episode", 1f, dateUpload = 0L, scanlator = null),
        selectedStream = stream(tracks, streamUrl),
        embeddedSubtitleTracks = embedded,
    )

    private fun stream(tracks: List<SubtitleOption>, url: String = "video1") = StreamOption(
        label = "Server · 1080p",
        url = url,
        headers = emptyMap(),
        subtitles = tracks,
        audioTracks = emptyList(),
        resolution = 1080,
    )

    private fun subtitle(label: String, language: String, url: String = "$label.vtt") =
        SubtitleOption(label, url, language)
}
