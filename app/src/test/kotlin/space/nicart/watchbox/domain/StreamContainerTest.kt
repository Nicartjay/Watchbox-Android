package space.nicart.watchbox.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for deciding whether a stream is HLS, DASH or a plain file.
 *
 * This decides the MIME type declared to the player, and getting it wrong fails before a
 * single segment is requested: a manifest handed to the progressive extractor cannot be
 * parsed, so the stream dies instantly on a server whose media is perfectly fine.
 *
 * The URL alone is not enough. Adaptive CDNs serve manifests from paths with no extension,
 * or behind one that lies - a `.jpg` that is really a playlist. Extensions know the type
 * from their own API and print it into the label, so that is consulted when the URL is
 * silent. Every label here is the shape a real extension produces.
 */
class StreamContainerTest {

    private fun stream(label: String, url: String) = StreamOption(
        label = label,
        url = url,
        headers = emptyMap(),
        subtitles = emptyList(),
        audioTracks = emptyList(),
        resolution = 1080,
    )

    // ------------------------------------------------------- from the URL

    @Test
    fun `a m3u8 url is hls`() {
        val s = stream("Yoru · 1080p", "https://cdn.test/master.m3u8")

        assertTrue(s.isHls)
        assertFalse(s.isDash)
    }

    @Test
    fun `a mpd url is dash`() {
        val s = stream("Art/MhPly · 1080p", "https://cdn.test/manifest.mpd")

        assertTrue(s.isDash)
        // Mutually exclusive, or a DASH manifest would be declared as HLS.
        assertFalse(s.isHls)
    }

    @Test
    fun `a query string cannot make a file into dash`() {
        // The pre-existing contract: matched on the path, so a signed URL whose query
        // mentions another container is unaffected.
        val s = stream("Srv · 1080p", "https://cdn.test/video.mkv?from=a.mpd")

        assertFalse(s.isDash)
    }

    @Test
    fun `a plain file is neither`() {
        val s = stream("Art/4k-Hub · 2160p · MKV", "https://cdn.test/release.mkv")

        assertFalse(s.isHls)
        assertFalse(s.isDash)
    }

    // ----------------------------------------------------- from the label

    @Test
    fun `an extensionless url is hls when the label says so`() {
        // Citadel and Solara: the path carries no extension at all, so nothing in the URL
        // says this is a manifest. The extension knew from its own API and said so.
        val s = stream("Art/Citadel · 1080p · HLS", "https://cdn.test/m3u8?token=abc123")

        assertTrue(s.isHls)
    }

    @Test
    fun `a jpg url is hls when the label says so`() {
        // Nebula's variants end .jpg and are served as image/jpeg, but the segments are
        // genuine fMP4 behind those names.
        val s = stream("Jay/Nebula · 1080p · HLS", "https://cdn.test/stream/1080/index.jpg")

        assertTrue(s.isHls)
    }

    @Test
    fun `a label declaring dash wins over no extension`() {
        val s = stream("Art/MhPly · 1080p · DASH", "https://cdn.test/adaptive?id=9")

        assertTrue(s.isDash)
        assertFalse(s.isHls)
    }

    @Test
    fun `m3u8 in the label is read as hls`() {
        val s = stream("Srv · 1080p · M3U8", "https://cdn.test/play?id=1")

        assertTrue(s.isHls)
    }

    @Test
    fun `reads a declaration from a pipe separated label`() {
        // The other shape extensions use for their labels.
        val s = stream("Srv | 1080p | HLS", "https://cdn.test/play?id=1")

        assertTrue(s.isHls)
    }

    @Test
    fun `dash in the label beats a m3u8 in the url`() {
        // isDash is evaluated first everywhere, and the label is no exception: a manifest
        // proxied through a URL mentioning .m3u8 is still DASH.
        val s = stream("Srv · 1080p · DASH", "https://proxy.test/get?src=a.m3u8")

        assertTrue(s.isDash)
        assertFalse(s.isHls)
    }

    // -------------------------------------------------------- not a match

    @Test
    fun `a title containing hls is not a declaration`() {
        // Read as a whole part between separators, so a word inside a title cannot make a
        // progressive file into a manifest.
        val s = stream("Srv · Hlsonic Adventures · 1080p", "https://cdn.test/movie.mp4")

        assertFalse(s.isHls)
        assertFalse(s.isDash)
    }

    @Test
    fun `a label naming another container is not hls`() {
        val s = stream("Art/StremFx · 1080p · MKV", "https://cdn.test/file")

        assertFalse(s.isHls)
        assertFalse(s.isDash)
    }

    @Test
    fun `a label with no container leaves an opaque url alone`() {
        // Nothing to go on, so the extractor sniffs it - which is the right default for a
        // progressive file and the pre-existing behaviour.
        val s = stream("Art/Citadel · 1080p", "https://cdn.test/play?id=7")

        assertFalse(s.isHls)
        assertFalse(s.isDash)
    }

    // ------------------------------------------------- the url wins outright

    @Test
    fun `a remuxed download is a file even when its label says hls`() {
        // The regression this guards: a download keeps the label it was fetched under, but
        // ffmpeg remuxed it into a plain file on disk. Declaring that a manifest would fail
        // to open an episode that had downloaded perfectly.
        val s = stream(
            "Art/Citadel · 1080p · HLS",
            "file:///data/user/0/space.nicart.watchbox/files/downloads/abc123.mkv",
        )

        assertFalse(s.isHls)
        assertFalse(s.isDash)
    }

    @Test
    fun `a downloaded mp4 is a file even when its label says dash`() {
        val s = stream("Art/MhPly · 1080p · DASH", "file:///data/downloads/ep1.mp4")

        assertFalse(s.isDash)
        assertFalse(s.isHls)
    }

    @Test
    fun `an adaptive download keeps its manifest url and stays hls`() {
        // The other download shape: an adaptive stream is stored under its manifest URI, so
        // the URL settles it without the label being needed.
        val s = stream("Art/Citadel · 1080p · HLS", "https://cdn.test/master.m3u8?sig=expired")

        assertTrue(s.isHls)
    }

    @Test
    fun `a query string does not hide the real extension`() {
        // The extension is read from the path, so a signed URL still answers for itself and
        // the label is not consulted.
        val s = stream("Srv · 1080p · HLS", "https://cdn.test/movie.mkv?token=xyz")

        assertFalse(s.isHls)
    }
}
