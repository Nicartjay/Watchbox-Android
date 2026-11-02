package space.nicart.watchbox.download

import space.nicart.watchbox.domain.SubtitleOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadInputsTest {
    @Test
    fun `opaque reel playlists discard the client HLS hint before FFmpeg networking`() {
        assertEquals(
            "https://cdn.test/stream?token=abc",
            ffmpegInputUrl("https://cdn.test/stream?token=abc#.m3u8"),
        )
    }

    @Test
    fun `encoded hash characters and signed query parameters are not modified`() {
        val url = "https://cdn.test/video%23one.m3u8?signature=a%23b%2Bc"
        assertEquals(url, ffmpegInputUrl(url))
    }

    @Test
    fun `all supplied subtitle languages are retained for automatic downloads`() {
        val tracks = listOf(
            SubtitleOption("English", "https://subs.test/en.vtt", "en"),
            SubtitleOption("French", "https://subs.test/fr.vtt", "fr"),
        )
        assertEquals(tracks, downloadableSubtitleTracks(tracks))
    }

    @Test
    fun `subtitle downloads ignore empty native references and duplicate sidecar URLs`() {
        val track = SubtitleOption("English", "https://subs.test/en.vtt", "en")
        assertEquals(
            listOf(track),
            downloadableSubtitleTracks(listOf(track, track, SubtitleOption("In file", "", "en"))),
        )
        assertTrue(downloadableSubtitleTracks(emptyList()).isEmpty())
    }
}
