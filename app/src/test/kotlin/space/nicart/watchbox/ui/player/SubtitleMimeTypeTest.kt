package space.nicart.watchbox.ui.player

import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleMimeTypeTest {

    @Test
    fun `an extensionless audio playlist can carry an hls fragment hint`() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            adaptiveMimeType("https://media.example.test/api?token=abc#.m3u8"),
        )
    }

    @Test
    fun `unknown audio urls keep media3 inference`() {
        assertEquals(null, adaptiveMimeType("https://media.example.test/file?id=1"))
    }

    @Test
    fun `dash paths and fragment hints are recognised`() {
        assertEquals(
            MimeTypes.APPLICATION_MPD,
            adaptiveMimeType("https://media.example.test/manifest.mpd?token=abc"),
        )
        assertEquals(
            MimeTypes.APPLICATION_MPD,
            adaptiveMimeType("https://media.example.test/api?token=abc#.mpd"),
        )
    }

    @Test
    fun `wing extensionless urls are treated as subrip`() {
        assertEquals(
            MimeTypes.APPLICATION_SUBRIP,
            subtitleMimeType("https://subs.wing.st/sub/AAA"),
        )
    }

    @Test
    fun `a fragment can carry a non-transmitted format hint`() {
        assertEquals(
            MimeTypes.APPLICATION_SUBRIP,
            subtitleMimeType("https://example.test/subtitle?id=1#.srt"),
        )
        assertEquals(
            MimeTypes.TEXT_VTT,
            subtitleMimeType("https://example.test/subtitle#.vtt"),
        )
    }

    @Test
    fun `the real path extension still wins`() {
        assertEquals(
            MimeTypes.TEXT_SSA,
            subtitleMimeType("https://example.test/subtitle.ass?token=abc"),
        )
    }
}
