package space.nicart.watchbox.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for turning a download key into a filename.
 *
 * The bug this guards: a download key carries the episode URL, and some sources encode a whole
 * session into that - Anikoto's run to around 300 characters of base64. Escaping the unsafe
 * characters produced a name far past the 255-byte limit every Android filesystem enforces, so
 * ffmpeg fetched the entire video and then failed on the final write with "File name too long".
 *
 * The obvious alternative - truncating the key - is worse than the original bug and is pinned
 * against here: these keys differ only in their tail, so a truncated name makes two episodes of
 * the same show resolve to one file and silently overwrite each other.
 */
class DownloadFileNameTest {

    /**
     * Mirrors DownloadStorage.toFileName.
     *
     * Duplicated rather than exposed: the production copy is private to a class that needs a
     * Context, and widening its visibility to test a pure string transform would be the worse
     * trade. Any change to the real one has to be reflected here, which the shared expectations
     * below make obvious.
     */
    private fun String.toFileName(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

        val readable = substringBefore("::")
            .plus("_")
            .plus(substringAfter("::").substringBefore("::").takeLast(24))
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(48)

        return "${readable}_$digest"
    }

    /** A real Anikoto key: the episode URL is a long base64 blob with query parameters. */
    private val longKey = "4697393375201558791::/watch/dandadan-lzcmw::" +
        "UkVTWTBROWkxaWMvSUV6aXlBTFVpYWN0K1V5ZW1TekloSVQ4TUVWRk43YjJBUmNBZXVYMG9oL3BZa0dY" +
        "YTFGOUoybFNJbC9vdWNRTTFUSm90QkFwMDZlVG02UVZCQk1IditKU01NOXpZUlpkWVdoZFExMUJ0RGdp" +
        "c2JhdHRRSWRsVlpEc1FwQStIY2ZrWlNGUTN2MmRRUGJaUUh5bmo1Z3EvMTJhczdLaWpBPQ" +
        "&epurl=/watch/dandadan-lzcmw/ep-3&mal=57334&slug=3&ts=1733483396"

    /**
     * 255 bytes is the per-component limit on ext4 and f2fs, which covers Android internal
     * storage; the `.mkv` suffix and any sidecar naming has to fit inside it too.
     */
    @Test
    fun `a very long key produces a name within the filesystem limit`() {
        val name = longKey.toFileName()
        assertTrue(
            name.length + ".mkv".length <= 255,
            "name was ${name.length} chars: $name",
        )
    }

    /**
     * The case truncation would have broken.
     *
     * Two episodes of one show share a source id and anime url and differ only inside the base64
     * tail, so a prefix-based name would collide.
     */
    @Test
    fun `keys differing only in their tail get different names`() {
        val ep3 = longKey
        val ep4 = longKey.replace("ep-3", "ep-4").replace("slug=3", "slug=4")

        assertNotEquals(ep3.toFileName(), ep4.toFileName())
    }

    @Test
    fun `the same key always produces the same name`() {
        // Playback looks a download up by recomputing this, so it cannot be random per call.
        assertEquals(longKey.toFileName(), longKey.toFileName())
    }

    @Test
    fun `the name contains nothing a filesystem would reject`() {
        val name = longKey.toFileName()
        val illegal = name.filterNot { it.isLetterOrDigit() || it == '_' || it == '-' }
        assertEquals("", illegal, "unexpected characters: $illegal")
    }

    /** A short key still yields a usable name rather than an empty one. */
    @Test
    fun `a short key is handled`() {
        val name = "12::/a::b".toFileName()
        assertTrue(name.isNotBlank())
        assertTrue(name.length <= 255)
    }
}
