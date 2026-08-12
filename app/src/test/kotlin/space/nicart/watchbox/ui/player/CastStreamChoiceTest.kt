package space.nicart.watchbox.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import space.nicart.watchbox.domain.StreamOption

/**
 * Tests for which stream a receiver is handed.
 *
 * Pinned because the failure is mute and misleading. A DLNA television given an HLS playlist
 * answers "file not supported" - it names neither the format nor which side is at fault - so
 * this reads as a broken app rather than an incompatible stream, and there is nothing in the
 * app's own logs to contradict that.
 */
class CastStreamChoiceTest {

    private fun stream(label: String, url: String) = StreamOption(
        label = label,
        url = url,
        headers = emptyMap(),
        subtitles = emptyList(),
        audioTracks = emptyList(),
        resolution = 1080,
    )

    private val hls = stream("1080p", "https://cdn.test/hls/master.m3u8")
    private val mp4 = stream("720p", "https://cdn.test/video.mp4")

    /** Chromecast decodes HLS itself, so nothing is swapped for it. */
    @Test
    fun `a chromecast keeps whatever is playing locally`() {
        assertEquals(hls, castStreamFor(listOf(hls, mp4), hls, preferProgressive = false))
    }

    @Test
    fun `dlna is given a progressive stream instead of hls`() {
        assertEquals(mp4, castStreamFor(listOf(hls, mp4), hls, preferProgressive = true))
    }

    /**
     * The chosen quality is not disturbed when it already works. Swapping anyway would quietly
     * downgrade the picture for no reason.
     */
    @Test
    fun `a progressive selection is left alone`() {
        val other = stream("480p", "https://cdn.test/other.mp4")

        assertEquals(mp4, castStreamFor(listOf(other, mp4), mp4, preferProgressive = true))
    }

    /**
     * Every stream being HLS still attempts the cast. A few renderers manage it, and the
     * receiver's own error is more informative than a button that appears to do nothing.
     */
    @Test
    fun `an hls-only source still casts rather than refusing`() {
        val onlyHls = listOf(hls, stream("720p", "https://cdn.test/b/index.m3u8"))

        assertEquals(hls, castStreamFor(onlyHls, hls, preferProgressive = true))
    }

    @Test
    fun `no streams and no selection yields nothing`() {
        assertNull(castStreamFor(emptyList(), null, preferProgressive = true))
        assertNull(castStreamFor(emptyList(), null, preferProgressive = false))
    }

    /** Query strings are common on CDN links and must not hide the extension. */
    @Test
    fun `an m3u8 with a query string is still recognised as hls`() {
        val signed = stream("1080p", "https://cdn.test/master.m3u8?token=abc123")

        assertEquals(mp4, castStreamFor(listOf(signed, mp4), signed, preferProgressive = true))
    }

    @Test
    fun `the first progressive stream wins`() {
        val first = stream("1080p", "https://cdn.test/first.mp4")
        val second = stream("720p", "https://cdn.test/second.mp4")

        assertEquals(first, castStreamFor(listOf(hls, first, second), hls, true))
    }
}
