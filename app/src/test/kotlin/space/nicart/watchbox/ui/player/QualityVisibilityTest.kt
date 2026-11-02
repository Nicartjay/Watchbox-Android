package space.nicart.watchbox.ui.player

import space.nicart.watchbox.domain.StreamOption
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QualityVisibilityTest {
    @Test
    fun `no streams or one quality keeps the quality icon hidden`() {
        assertFalse(emptyList<StreamOption>().hasMultipleQualityChoices(null))
        val only = stream("Server · 1080p", "one.mp4")
        assertFalse(listOf(only).hasMultipleQualityChoices(only))
    }

    @Test
    fun `several qualities on the selected server show the icon`() {
        val streams = listOf(stream("Server · 1080p", "1080.mp4"), stream("Server · 720p", "720.mp4"))
        assertTrue(streams.hasMultipleQualityChoices(streams.first()))
    }

    @Test
    fun `another server alone does not add choices to the selected quality panel`() {
        val streams = listOf(stream("First · 1080p", "one.mp4"), stream("Second · 720p", "two.mp4"))
        assertFalse(streams.hasMultipleQualityChoices(streams.first()))
    }

    @Test
    fun `duplicate URLs are not real quality choices`() {
        val streams = listOf(stream("Server · 1080p", "same.mp4"), stream("Server · 720p", "same.mp4"))
        assertFalse(streams.hasMultipleQualityChoices(streams.first()))
    }

    @Test
    fun `distinct releases at one height remain selectable`() {
        val streams = listOf(
            stream("Server · 1080p · HEVC", "hevc.mp4"),
            stream("Server · 1080p · AVC", "avc.mp4"),
        )
        assertTrue(streams.hasMultipleQualityChoices(streams.first()))
    }

    @Test
    fun `unparsed labels follow the quality panel's flat list fallback`() {
        val streams = listOf(stream("Auto", "auto.m3u8"), stream("Video", "video.mp4"))
        assertTrue(streams.hasMultipleQualityChoices(streams.first()))
        assertFalse(listOf(streams.first()).hasMultipleQualityChoices(streams.first()))
    }

    private fun stream(label: String, url: String) = StreamOption(
        label = label,
        url = url,
        headers = emptyMap(),
        subtitles = emptyList(),
        audioTracks = emptyList(),
        resolution = 0,
    )
}
