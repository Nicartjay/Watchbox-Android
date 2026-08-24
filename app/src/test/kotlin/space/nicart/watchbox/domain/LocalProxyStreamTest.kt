package space.nicart.watchbox.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for detecting a stream served by a proxy inside the extension.
 *
 * Worth pinning because the failure it prevents is expensive and confusing: some extensions do
 * not return the real media URL at all, but start an HTTP server in their own process and hand
 * back a `localhost` address. That plays perfectly - the proxy is alive while the video is on
 * screen - and can never be downloaded, because the download outlives the page and the port is
 * chosen fresh each session. The observed symptom was an immediate 403 with nothing
 * transferred, on a source whose playback worked fine, which points nowhere useful.
 *
 * The port is deliberately not part of the match: it is exactly the part that varies.
 */
class LocalProxyStreamTest {

    private fun stream(url: String) = StreamOption(
        label = "Test",
        url = url,
        headers = emptyMap(),
        subtitles = emptyList(),
        audioTracks = emptyList(),
        resolution = 1080,
    )

    @Test
    fun `a loopback url on any port is a local proxy`() {
        assertTrue(stream("http://localhost:44293/m3u8?url=https%3A%2F%2Fcdn.example%2Fa.m3u8").isLocalProxy)
        assertTrue(stream("http://localhost:34051/m3u8?url=x").isLocalProxy)
        assertTrue(stream("http://127.0.0.1:8080/proxy/index.m3u8").isLocalProxy)
    }

    @Test
    fun `the check is case insensitive`() {
        assertTrue(stream("HTTP://LocalHost:44293/m3u8").isLocalProxy)
    }

    /** A real CDN is downloadable, which is the case that must keep working. */
    @Test
    fun `a remote url is not a local proxy`() {
        assertFalse(
            stream("https://cdn.watching.onl/anime/abc/def/index-f1-v1-a1.m3u8").isLocalProxy,
        )
        assertFalse(stream("https://s1.akirax.buzz/aga6e/1080p/index.m3u8").isLocalProxy)
        assertFalse(stream("https://example.com/video.mp4").isLocalProxy)
    }

    /**
     * A host that merely begins with the word is remote.
     *
     * `localhost.example.com` resolves publicly, so matching on a prefix without the port
     * separator would wrongly refuse a downloadable stream.
     */
    @Test
    fun `a host that only starts with localhost is remote`() {
        assertFalse(stream("https://localhost.example.com/video.m3u8").isLocalProxy)
        assertFalse(stream("https://localhostcdn.net/a.m3u8").isLocalProxy)
    }

    /** A proxied stream is still a valid HLS stream; only its reachability is the problem. */
    @Test
    fun `a proxied hls url is still recognised as hls`() {
        val proxied = stream("http://localhost:44293/m3u8?url=https%3A%2F%2Fx%2Fa.m3u8")
        assertTrue(proxied.isHls)
        assertTrue(proxied.isLocalProxy)
    }
}
