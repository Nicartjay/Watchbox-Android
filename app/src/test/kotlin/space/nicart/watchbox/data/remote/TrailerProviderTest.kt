package space.nicart.watchbox.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import space.nicart.watchbox.data.remote.SheguTrailerApi.Companion.parse

/**
 * Tests for reading a hero trailer out of a response body.
 *
 * The service is a third party's private endpoint - no version, no documented
 * contract, no commitment that the shape stays as it is. So the property under test
 * throughout is tolerance: an answer this does not understand must yield null, never
 * an exception, because null means the hero shows its backdrop and the page is
 * otherwise untouched.
 *
 * The payload here was captured from the live service. The field names and the
 * signed-URL shape are not things worth inventing.
 */
class TrailerProviderTest {

    /** Oppenheimer, as the service answers it. */
    private val oppenheimer = """
        {"url":"https://imdb-video.media-imdb.com/vi2053751833/1434659379400-8cjq25.mp4?Expires=1787111795&Signature=abc&Key-Pair-Id=APKAIFLZBVQZ24NQH3KA",
         "mime":"video/mp4","source":"imdb","imdb_id":"tt15398776"}
    """.trimIndent()

    @Test
    fun `reads the url and container from a live payload`() {
        val trailer = parse(oppenheimer)
        assertEquals("video/mp4", trailer?.mimeType)
        assertEquals(true, trailer?.url?.startsWith("https://imdb-video.media-imdb.com/"))
    }

    /** The query carries the signature; dropping it would make the URL a 403. */
    @Test
    fun `keeps the signing query intact`() {
        val url = parse(oppenheimer)?.url.orEmpty()
        assertEquals(true, url.contains("Expires="))
        assertEquals(true, url.contains("Signature="))
        assertEquals(true, url.contains("Key-Pair-Id="))
    }

    /**
     * Defaulted rather than required. The container is knowable from the file, so
     * refusing a playable URL over a missing label would be the wrong trade.
     */
    @Test
    fun `assumes mp4 when no container is reported`() {
        assertEquals("video/mp4", parse("""{"url":"https://x.test/a.mp4"}""")?.mimeType)
    }

    @Test
    fun `assumes mp4 when the container is blank`() {
        assertEquals("video/mp4", parse("""{"url":"https://x.test/a.mp4","mime":""}""")?.mimeType)
    }

    @Test
    fun `honours a container the service does report`() {
        val body = """{"url":"https://x.test/a.webm","mime":"video/webm"}"""
        assertEquals("video/webm", parse(body)?.mimeType)
    }

    // -------------------------------------------------------- nothing to play

    /** No url is "no trailer", which is an ordinary answer rather than a fault. */
    @Test
    fun `returns nothing when there is no url`() {
        assertNull(parse("""{"mime":"video/mp4","source":"imdb"}"""))
    }

    @Test
    fun `returns nothing for a blank url`() {
        assertNull(parse("""{"url":"   "}"""))
    }

    @Test
    fun `returns nothing for a null url`() {
        assertNull(parse("""{"url":null}"""))
    }

    // -------------------------------------------------------- malformed input

    /**
     * The cases that would take the detail page down if they threw. A private
     * endpoint can start answering an error object, HTML from a proxy, or an array
     * instead of an object, with no warning.
     */
    @Test
    fun `survives an error object`() {
        assertNull(parse("""{"error":"not found","status":404}"""))
    }

    @Test
    fun `survives html from an interstitial`() {
        assertNull(parse("<!doctype html><html><body>blocked</body></html>"))
    }

    @Test
    fun `survives a json array`() {
        assertNull(parse("""[{"url":"https://x.test/a.mp4"}]"""))
    }

    @Test
    fun `survives an empty body`() {
        assertNull(parse(""))
    }

    /** Unknown fields are ignored, so the service may add some without breaking this. */
    @Test
    fun `ignores fields it does not know`() {
        val body = """{"url":"https://x.test/a.mp4","mime":"video/mp4","quality":"1080p","added":9}"""
        assertEquals("https://x.test/a.mp4", parse(body)?.url)
    }
}
