package space.nicart.watchbox.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for HLS manifest rewriting.
 *
 * Worth unit-testing rather than checking on a device: a mistake here produces a
 * manifest the receiver accepts and then fails to play, with no error that points
 * back at the rewriter. The encryption-key case in particular breaks every
 * AES-encrypted stream while looking perfectly healthy.
 */
class HlsRewriterTest {

    private val proxyBase = "http://192.168.1.5:8909"
    private val manifestUrl = "https://cdn.example.com/hls/720/index.m3u8"
    private val session = "7"

    private fun rewrite(manifest: String): String =
        HlsRewriter.rewrite(manifest, manifestUrl, session, proxyBase)

    @Test
    fun `relative segment resolves against the manifest directory`() {
        val out = rewrite("#EXTINF:6.0,\nseg1.ts")
        assertTrue(
            out.contains("/seg/$session/https%3A%2F%2Fcdn.example.com%2Fhls%2F720%2Fseg1.ts"),
            "expected manifest-relative resolution, got:\n$out",
        )
    }

    @Test
    fun `root-relative segment resolves against the origin, not the manifest directory`() {
        val out = rewrite("#EXTINF:6.0,\n/abs/seg2.ts")
        assertTrue(
            out.contains("/seg/$session/https%3A%2F%2Fcdn.example.com%2Fabs%2Fseg2.ts"),
            "root-relative URI must drop the manifest path, got:\n$out",
        )
    }

    @Test
    fun `absolute segment url is preserved`() {
        val out = rewrite("#EXTINF:6.0,\nhttps://other.cdn.net/seg3.ts")
        assertTrue(out.contains("/seg/$session/https%3A%2F%2Fother.cdn.net%2Fseg3.ts"))
    }

    @Test
    fun `protocol-relative segment is promoted to https`() {
        val out = rewrite("#EXTINF:6.0,\n//proto.cdn.net/seg4.ts")
        assertTrue(out.contains("/seg/$session/https%3A%2F%2Fproto.cdn.net%2Fseg4.ts"))
    }

    @Test
    fun `encryption key uri is rewritten`() {
        // Missing this leaves the key fetch unauthenticated, so playback fails
        // with a decryption error rather than anything pointing here.
        val out = rewrite("""#EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x00""")
        assertTrue(
            out.contains("""URI="$proxyBase/seg/$session/"""),
            "EXT-X-KEY URI must be proxied, got:\n$out",
        )
    }

    @Test
    fun `init segment map uri is rewritten`() {
        val out = rewrite("""#EXT-X-MAP:URI="init.mp4"""")
        assertTrue(out.contains("%2Fhls%2F720%2Finit.mp4"), out)
    }

    @Test
    fun `iframe stream variant uri is rewritten`() {
        val out = rewrite("""#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=100,URI="iframe.m3u8"""")
        assertTrue(out.contains("%2Fiframe.m3u8"), out)
    }

    @Test
    fun `non-uri tags are left untouched`() {
        val manifest = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6.0,\n#EXT-X-ENDLIST"
        val out = rewrite(manifest)
        assertEquals(manifest, out)
    }

    @Test
    fun `already proxied urls are not wrapped twice`() {
        val once = rewrite("#EXTINF:6.0,\nseg1.ts")
        val twice = HlsRewriter.rewrite(once, manifestUrl, session, proxyBase)
        assertFalse(
            twice.contains("%2Fseg%2F$session"),
            "double-wrapping would make the proxy fetch itself:\n$twice",
        )
        assertEquals(once, twice)
    }

    @Test
    fun `blank lines survive so the manifest stays valid`() {
        val out = rewrite("#EXTM3U\n\n#EXTINF:6.0,\nseg1.ts")
        assertTrue(out.startsWith("#EXTM3U\n\n"), out)
    }
}
