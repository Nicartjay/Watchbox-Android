package space.nicart.watchbox.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for HLS segment-format detection.
 *
 * Worth pinning because the failure it prevents is completely silent. A Cast receiver assumes
 * MPEG2-TS; handed fragmented MP4 without being told, it loads the manifest, reports the correct
 * duration, downloads segments - and then never decodes a frame. `load()` reports success,
 * `mediaError` is null, and the only symptom is a receiver stuck at 0:00.
 *
 * The fixtures mirror the two real sources this was diagnosed against: one serving fMP4/CMAF
 * with an `#EXT-X-MAP` initialisation segment, the other classic `.ts`.
 */
class HlsFormatTest {

    /** Shaped like the Cineby playlist that stalled: fMP4 with an init segment. */
    private val fmp4Playlist = """
        #EXTM3U
        #EXT-X-TARGETDURATION:6
        #EXT-X-PLAYLIST-TYPE:VOD
        #EXT-X-VERSION:6
        #EXT-X-MEDIA-SEQUENCE:1
        #EXT-X-MAP:URI="https://cdn.test/vd/abc/init-seg"
        #EXTINF:6.400,
        https://cdn.test/vd/abc/seg-1
        #EXTINF:6.400,
        https://cdn.test/vd/abc/seg-2
        #EXT-X-ENDLIST
    """.trimIndent()

    /** Classic TS playlist, the shape that always worked. */
    private val tsPlaylist = """
        #EXTM3U
        #EXT-X-TARGETDURATION:10
        #EXT-X-VERSION:3
        #EXTINF:10.0,
        segment0.ts
        #EXTINF:10.0,
        segment1.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
        1080/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
        720/index.m3u8
    """.trimIndent()

    // ------------------------------------------------------------ segment format

    /** `#EXT-X-MAP` only exists for fMP4, so it is the definitive marker. */
    @Test
    fun `an init segment identifies fragmented mp4`() {
        assertEquals(HlsFormat.FMP4, HlsFormat.segmentFormat(fmp4Playlist))
    }

    @Test
    fun `ts segments are identified by extension`() {
        assertEquals(HlsFormat.TS, HlsFormat.segmentFormat(tsPlaylist))
    }

    /** Some packagers omit EXT-X-MAP, so the extension is the fallback. */
    @Test
    fun `m4s segments identify fragmented mp4 without an init tag`() {
        val playlist = """
            #EXTM3U
            #EXTINF:4.0,
            chunk-0.m4s
            #EXTINF:4.0,
            chunk-1.m4s
        """.trimIndent()

        assertEquals(HlsFormat.FMP4, HlsFormat.segmentFormat(playlist))
    }

    /** Signed CDN URLs carry a query string, which must not hide the extension. */
    @Test
    fun `a query string does not defeat extension detection`() {
        val playlist = """
            #EXTM3U
            #EXTINF:10.0,
            https://cdn.test/a/segment0.ts?token=abc&exp=123
        """.trimIndent()

        assertEquals(HlsFormat.TS, HlsFormat.segmentFormat(playlist))
    }

    /**
     * A master playlist describes no segments of its own, so nothing can be concluded from it -
     * and guessing would assert a format for the wrong level of the stream.
     */
    @Test
    fun `a master playlist yields no format of its own`() {
        assertNull(HlsFormat.segmentFormat(master))
    }

    /**
     * Null rather than a default. Telling the receiver "TS" for a stream that is really fMP4
     * fails exactly as badly as saying nothing, so an unknown format is left to the receiver.
     */
    @Test
    fun `an extensionless playlist yields no format`() {
        val playlist = """
            #EXTM3U
            #EXTINF:6.0,
            https://cdn.test/vd/abc/chunk-1
        """.trimIndent()

        assertNull(HlsFormat.segmentFormat(playlist))
    }

    @Test
    fun `an empty playlist yields no format`() {
        assertNull(HlsFormat.segmentFormat(""))
        assertNull(HlsFormat.segmentFormat("#EXTM3U"))
    }

    // ------------------------------------------------------- video segment format

    /** Cast spells the video variant differently from the segment variant. */
    @Test
    fun `the video format matches the segment format`() {
        assertEquals(HlsFormat.VIDEO_FMP4, HlsFormat.videoSegmentFormat(HlsFormat.FMP4))
        assertEquals(HlsFormat.VIDEO_MPEG2_TS, HlsFormat.videoSegmentFormat(HlsFormat.TS))
    }

    @Test
    fun `an unknown segment format has no video format`() {
        assertNull(HlsFormat.videoSegmentFormat(null))
        assertNull(HlsFormat.videoSegmentFormat("something-else"))
    }

    /** The constants must match the strings the Cast SDK defines, or the receiver ignores them. */
    @Test
    fun `the format identifiers match the cast sdk values`() {
        assertEquals("fmp4", HlsFormat.FMP4)
        assertEquals("ts", HlsFormat.TS)
        assertEquals("fmp4", HlsFormat.VIDEO_FMP4)
        assertEquals("mpeg2_ts", HlsFormat.VIDEO_MPEG2_TS)
    }

    // -------------------------------------------------------------------- master

    @Test
    fun `a master playlist is recognised`() {
        assertTrue(HlsFormat.isMaster(master))
        assertFalse(HlsFormat.isMaster(fmp4Playlist))
        assertFalse(HlsFormat.isMaster(tsPlaylist))
    }

    @Test
    fun `the first variant is taken from a master`() {
        assertEquals("1080/index.m3u8", HlsFormat.firstVariantUri(master))
    }

    /** Blank lines and comments between the tag and its URI are legal. */
    @Test
    fun `a variant uri is found past blank lines`() {
        val awkward = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n\n\nvariant.m3u8\n"

        assertEquals("variant.m3u8", HlsFormat.firstVariantUri(awkward))
    }

    @Test
    fun `a playlist with no variants has no variant uri`() {
        assertNull(HlsFormat.firstVariantUri(tsPlaylist))
    }

    // ------------------------------------------------------------------- resolve

    @Test
    fun `an absolute variant url is kept`() {
        val resolved = HlsFormat.resolve(
            "https://other.test/v/index.m3u8",
            "https://cdn.test/hls/master.m3u8",
        )

        assertEquals("https://other.test/v/index.m3u8", resolved)
    }

    @Test
    fun `a relative variant resolves against the playlist directory`() {
        val resolved = HlsFormat.resolve("1080/index.m3u8", "https://cdn.test/hls/master.m3u8")

        assertEquals("https://cdn.test/hls/1080/index.m3u8", resolved)
    }

    /** Root-relative URIs resolve against the origin, not the playlist directory. */
    @Test
    fun `a root-relative variant resolves against the origin`() {
        val resolved = HlsFormat.resolve("/v/1080.m3u8", "https://cdn.test/hls/master.m3u8")

        assertEquals("https://cdn.test/v/1080.m3u8", resolved)
    }

    @Test
    fun `a protocol-relative variant is promoted to https`() {
        val resolved = HlsFormat.resolve("//cdn.test/v/1080.m3u8", "https://x.test/master.m3u8")

        assertEquals("https://cdn.test/v/1080.m3u8", resolved)
    }
}
