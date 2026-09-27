package space.nicart.watchbox.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import space.nicart.watchbox.data.remote.WeCollegeTrailerApi.Companion.parse

/**
 * Tests for reading a trailer out of a `trailers.wecollege.net` response.
 *
 * The payload is the live answer for tt6933238, captured on 27 September 2026.
 */
class WeCollegeTrailerTest {

    private val live = """
        {"id":"tt6933238","imdb_url":"https://www.imdb.com/title/tt6933238/","trailer":{"id":"vi967428633",
        "name":"Trailer","runtime":156,"thumbnail":"https://m.media-amazon.com/images/x.jpg","streams":[
        {"quality":"1080p","url":"https://imdb-video.media-imdb.com/mc/vi967428633/vi967428633_1080p.mp4","mimeType":"MP4"},
        {"quality":"AUTO","url":"https://imdb-video.media-imdb.com/mc/vi967428633/hls/vi967428633.m3u8","mimeType":"M3U8"},
        {"quality":"720p","url":"https://imdb-video.media-imdb.com/mc/vi967428633/vi967428633_720p.mp4","mimeType":"MP4"},
        {"quality":"480p","url":"https://imdb-video.media-imdb.com/mc/vi967428633/vi967428633_480p.mp4","mimeType":"MP4"}],
        "imdbUrl":"https://www.imdb.com/video/vi967428633/"}}
    """.trimIndent()

    @Test
    fun `prefers the 1080p mp4`() {
        val trailer = parse(live)
        assertEquals("https://imdb-video.media-imdb.com/mc/vi967428633/vi967428633_1080p.mp4", trailer?.url)
        assertEquals("video/mp4", trailer?.mimeType)
    }

    @Test
    fun `falls back down the quality order`() {
        val body = """{"trailer":{"streams":[
            {"quality":"AUTO","url":"https://x/a.m3u8","mimeType":"M3U8"},
            {"quality":"480p","url":"https://x/a_480p.mp4","mimeType":"MP4"}]}}"""
        assertEquals("https://x/a_480p.mp4", parse(body)?.url)
    }

    @Test
    fun `uses the hls master only when nothing else exists`() {
        val body = """{"trailer":{"streams":[{"quality":"AUTO","url":"https://x/a.m3u8","mimeType":"M3U8"}]}}"""
        assertEquals("application/x-mpegURL", parse(body)?.mimeType)
    }

    @Test
    fun `no trailer is null, not an error`() {
        assertNull(parse("""{"id":"tt0","trailer":null}"""))
        assertNull(parse("""{"trailer":{"streams":[]}}"""))
        assertNull(parse("not json"))
        assertNull(parse(""))
    }
}
