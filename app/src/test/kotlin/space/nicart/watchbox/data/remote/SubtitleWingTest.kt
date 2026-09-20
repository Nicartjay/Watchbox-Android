package space.nicart.watchbox.data.remote

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleWingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val realReply = """
        {
          "total": 1,
          "subtitles": [{
            "id": "https://subs.wing.st/sub/AAA",
            "language": "en",
            "url": "https://subs.wing.st/sub/AAA",
            "type": "srt",
            "display": "Movie.2026.1080p.WEB-DL.srt",
            "source": "shegu"
          }]
        }
    """.trimIndent()

    @Test
    fun `reads and maps a wing reply`() {
        val response = json.decodeFromString<WingSubtitleResponse>(realReply)
        val result = response.subtitles.single().toResult()

        assertEquals(1, response.total)
        assertEquals("Movie.2026.1080p.WEB-DL.srt", result?.name)
        assertEquals("en", result?.language)
        assertEquals("srt", result?.format)
        assertEquals("https://subs.wing.st/sub/AAA", result?.downloadUrl)
    }

    @Test
    fun `normalises a dotted format`() {
        val subtitle = WingSubtitle(
            language = "en",
            url = "https://subs.wing.st/sub/AAA",
            type = ".SRT",
        )

        assertEquals("srt", subtitle.toResult()?.format)
    }

    @Test
    fun `defaults a missing format to subrip`() {
        val subtitle = WingSubtitle(
            language = "en",
            url = "https://subs.wing.st/sub/AAA",
        )

        assertEquals("srt", subtitle.toResult()?.format)
    }

    @Test
    fun `drops a row without a language or url`() {
        assertNull(WingSubtitle(url = "https://subs.wing.st/sub/AAA").toResult())
        assertNull(WingSubtitle(language = "en").toResult())
    }
}
