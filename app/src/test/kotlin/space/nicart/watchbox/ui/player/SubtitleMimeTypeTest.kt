package space.nicart.watchbox.ui.player

import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleMimeTypeTest {

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
