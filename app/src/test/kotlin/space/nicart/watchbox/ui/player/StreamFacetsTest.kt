package space.nicart.watchbox.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import space.nicart.watchbox.domain.StreamOption

/**
 * Tests for splitting a stream label into server, quality and audio.
 *
 * Every label here was captured from the live API rather than invented, because
 * the format is a display string an extension is free to change - three of these
 * shapes appeared in a single day. The point of pinning them is that a format
 * change should break a test here, not silently collapse the player's grouping.
 */
class StreamFacetsTest {

    private fun stream(label: String, resolution: Int = 0) = StreamOption(
        label = label,
        url = "https://cdn.test/${label.hashCode()}.mkv",
        headers = emptyMap(),
        subtitles = emptyList(),
        audioTracks = emptyList(),
        resolution = resolution,
    )

    // ---------------------------------------------------------------- parsing

    @Test
    fun `reads server quality and dub from a MbPly label`() {
        val f = StreamFacets.parse("Art/MbPly · 1080p · Hindi dub · MP4")
        assertEquals("Art/MbPly", f.server)
        assertEquals("1080p", f.quality)
        assertEquals("Hindi dub", f.dub)
    }

    @Test
    fun `treats Original Audio as a track worth choosing`() {
        val f = StreamFacets.parse("Art/MbPly · 720p · Original Audio · MP4")
        assertEquals("Original Audio", f.dub)
    }

    /** Some servers separate entries only by subtitle language. */
    @Test
    fun `reads a subtitle-language descriptor as the audio axis`() {
        assertEquals("Arabic sub", StreamFacets.parse("Art/MbPly · 1080p · Arabic sub · MP4").dub)
    }

    @Test
    fun `leaves dub null when the server labels no audio`() {
        val f = StreamFacets.parse("Art/4k-Hub · 2160p · BluRay · HEVC · 66.39 GB · MKV")
        assertEquals("Art/4k-Hub", f.server)
        assertEquals("2160p", f.quality)
        assertNull(f.dub)
    }

    /**
     * "12 subs" is a count, not a track. It contains "sub", so without an explicit
     * exclusion it would be offered as an audio option.
     */
    @Test
    fun `does not mistake a subtitle count for an audio track`() {
        val f = StreamFacets.parse("Orion · 2160p · DASH · 8 subs")
        assertNull(f.dub)
    }

    @Test
    fun `prefers the real audio part over a subtitle count`() {
        val f = StreamFacets.parse("Yoru · 1080p · HLS · Original audio · 12 subs")
        assertEquals("Original audio", f.dub)
    }

    @Test
    fun `ignores container and release tags when looking for audio`() {
        val f = StreamFacets.parse("Art/4k-bk · 720p · BluRay · HEVC · 858.5 MB · MKV")
        assertNull(f.dub)
    }

    @Test
    fun `leaves quality null when the label has no resolution`() {
        val f = StreamFacets.parse("Breach · Auto · HLS")
        assertEquals("Breach", f.server)
        assertNull(f.quality)
    }

    @Test
    fun `reads a bare 4K as the resolution`() {
        assertEquals("4K", StreamFacets.parse("Yoru · 4K · HLS").quality)
    }

    /**
     * The guarantee that keeps a format change from hiding streams: anything
     * unrecognised is still a selectable entry under its own name.
     */
    @Test
    fun `falls back to the whole label when there are no separators`() {
        val f = StreamFacets.parse("1080p")
        assertEquals("1080p", f.server)
        assertNull(f.quality)
        assertNull(f.dub)
    }

    @Test
    fun `survives an empty label`() {
        val f = StreamFacets.parse("")
        assertEquals("", f.server)
        assertNull(f.quality)
    }

    // ------------------------------------------------------------- option lists

    private val streams = listOf(
        stream("Yoru · 1080p · HLS · Original audio", 1080),
        stream("Yoru · 720p · HLS · Original audio", 720),
        stream("Art/MbPly · 1080p · Hindi dub · MP4", 1080),
        stream("Art/MbPly · 480p · Hindi dub · MP4", 480),
        stream("Art/MbPly · 720p · Original Audio · MP4", 720),
        stream("Art/4k-Hub · 2160p · BluRay · MKV", 2160),
    )

    @Test
    fun `lists each server once in source order`() {
        assertEquals(listOf("Yoru", "Art/MbPly", "Art/4k-Hub"), streams.serverOptions())
    }

    @Test
    fun `lists a server's qualities highest first`() {
        assertEquals(
            listOf("1080p", "720p", "480p"),
            streams.qualityChoices("Art/MbPly", dub = null).map { it.label },
        )
    }

    // ------------------------------------------------- every stream is reachable

    /**
     * Art/4k-Hub offers four different 2160p releases. Collapsing them to one row
     * per resolution hid three, and because that left a single distinct quality
     * the panel's own button was hidden as well - so none of them could be reached.
     */
    private val sameHeight = listOf(
        stream("Art/4k-Hub · 2160p · BluRay · HEVC · 66.39 GB · MKV", 2160),
        stream("Art/4k-Hub · 2160p · BluRay · HEVC · 41.84 GB · MKV", 2160),
        stream("Art/4k-Hub · 2160p · BluRay · HEVC · 19.11 GB · MKV", 2160),
    )

    @Test
    fun `keeps a row for every stream at the same resolution`() {
        val choices = sameHeight.qualityChoices("Art/4k-Hub", dub = null)
        assertEquals(3, choices.size)
        assertEquals(listOf("2160p-1", "2160p-2", "2160p-3"), choices.map { it.label })
    }

    /** Each row must resolve to its own file, not all to the first. */
    @Test
    fun `each numbered row carries its own stream`() {
        val urls = sameHeight.qualityChoices("Art/4k-Hub", dub = null).map { it.stream.url }
        assertEquals(urls.size, urls.distinct().size)
    }

    /** The number alone says nothing; the size and source are what distinguish them. */
    @Test
    fun `numbered rows carry the detail that tells them apart`() {
        val choices = sameHeight.qualityChoices("Art/4k-Hub", dub = null)
        assertTrue(choices[0].detail!!.contains("66.39 GB"))
        assertTrue(choices[1].detail!!.contains("41.84 GB"))
    }

    /** A resolution appearing once is not numbered - "1080p-1" alone reads oddly. */
    @Test
    fun `leaves a unique resolution unnumbered`() {
        val choices = streams.qualityChoices("Art/4k-Hub", dub = null)
        assertEquals(listOf("2160p"), choices.map { it.label })
    }

    // ------------------------------------------------- quality vs audio track

    /**
     * The reported no-op: MbPly carries 1080p on the Hindi dub only, while the
     * original audio tops out at 720p. Listing the heights alone made 1080p look
     * available on either, and choosing it while on the original resolved back to
     * the 720p stream already playing - so the row did nothing at all.
     */
    private val mixed = listOf(
        stream("Art/MbPly · 1080p · Hindi dub · MP4", 1080),
        stream("Art/MbPly · 480p · Hindi dub · MP4", 480),
        stream("Art/MbPly · 720p · Original Audio · MP4", 720),
        stream("Art/MbPly · 480p · Original Audio · MP4", 480),
        stream("Art/MbPly · 360p · Original Audio · MP4", 360),
    )

    @Test
    fun `marks a quality that only exists on another audio track`() {
        val choices = mixed.qualityChoices("Art/MbPly", dub = "Original Audio")
        assertEquals("Hindi dub", choices.first { it.label == "1080p" }.requiresDub)
    }

    @Test
    fun `leaves qualities on the current track unmarked`() {
        val choices = mixed.qualityChoices("Art/MbPly", dub = "Original Audio")
        assertNull(choices.first { it.label == "720p" }.requiresDub)
        // 480p exists on both tracks, so neither copy is attributed to the other.
        assertTrue(choices.filter { it.label.startsWith("480p") }.all { it.requiresDub == null })
    }

    @Test
    fun `marks nothing when every quality is on the current track`() {
        val choices = mixed.qualityChoices("Art/MbPly", dub = "Hindi dub")
        assertNull(choices.first { it.label == "1080p" }.requiresDub)
        assertEquals("Original Audio", choices.first { it.label == "720p" }.requiresDub)
    }

    /** The fix for the dead tap: choosing 1080p must move the audio track with it. */
    @Test
    fun `switching to a quality on another track lands on that track`() {
        val picked = pickStream(mixed, server = "Art/MbPly", quality = "1080p", dub = "Hindi dub")
        assertEquals("Art/MbPly · 1080p · Hindi dub · MP4", picked?.label)
        assertEquals(1080, picked?.resolution)
    }

    /** Pinned as the regression: carrying the old track over is what did nothing. */
    @Test
    fun `keeping the old track on an unavailable quality resolves to the same stream`() {
        val picked = pickStream(mixed, server = "Art/MbPly", quality = "1080p", dub = "Original Audio")
        assertEquals(720, picked?.resolution)
    }

    @Test
    fun `reports no choices when labels carry no resolution`() {
        val unlabelled = listOf(stream("Breach · Auto · HLS"))
        assertTrue(unlabelled.qualityChoices("Breach", dub = null).isEmpty())
    }

    @Test
    fun `lists only the audio tracks a server offers`() {
        assertEquals(listOf("Hindi dub", "Original Audio"), streams.dubOptions("Art/MbPly"))
        assertTrue(streams.dubOptions("Art/4k-Hub").isEmpty())
    }

    @Test
    fun `reports the best resolution each server carries`() {
        val best = streams.serverBestQuality()
        assertEquals("1080p", best["Yoru"])
        assertEquals("1080p", best["Art/MbPly"])
        assertEquals("2160p", best["Art/4k-Hub"])
    }

    /** Absent rather than zero, so the caller can omit the line entirely. */
    @Test
    fun `omits a server whose labels carry no resolution`() {
        val unlabelled = listOf(stream("Breach · Auto · HLS"))
        assertTrue(unlabelled.serverBestQuality().isEmpty())
    }

    // ------------------------------------------------------------- picking

    @Test
    fun `keeps quality and audio when they exist on the new server`() {
        val picked = pickStream(streams, server = "Art/MbPly", quality = "1080p", dub = "Hindi dub")
        assertEquals("Art/MbPly · 1080p · Hindi dub · MP4", picked?.label)
    }

    /**
     * The behaviour chosen over resetting to the server's best: 2160p is not on
     * offer here, so it drops to the nearest below rather than jumping anywhere.
     */
    @Test
    fun `falls to the nearest lower quality when the exact one is missing`() {
        val picked = pickStream(streams, server = "Yoru", quality = "2160p", dub = null)
        assertEquals(1080, picked?.resolution)
    }

    @Test
    fun `takes the best available when everything is above the request`() {
        val picked = pickStream(streams, server = "Art/4k-Hub", quality = "480p", dub = null)
        assertEquals(2160, picked?.resolution)
    }

    @Test
    fun `ignores an audio track the new server does not have`() {
        val picked = pickStream(streams, server = "Art/4k-Hub", quality = "2160p", dub = "Hindi dub")
        assertEquals("Art/4k-Hub · 2160p · BluRay · MKV", picked?.label)
    }

    @Test
    fun `returns null only for an empty list`() {
        assertNull(pickStream(emptyList(), null, null, null))
    }

    // --------------------------------------------------- opening stream choice

    /**
     * Ordered as a source would return it, preference first, rather than by
     * height - the repository no longer re-sorts, so the head of the list is the
     * extension's own recommendation.
     */
    private val asSourceOrdered = listOf(
        stream("Art/4k-Hub · 2160p · BluRay · MKV", 2160),
        stream("Art/4k-bk · 1080p · BluRay · MKV", 1080),
        stream("Yoru · 720p · HLS", 720),
        stream("Art/MbPly · 480p · Hindi dub · MP4", 480),
    )

    /** The reported bug: 1080p set, 2160p played. */
    @Test
    fun `opens at the preferred height when it exists`() {
        assertEquals(1080, defaultStream(asSourceOrdered, preferredHeight = 1080)?.resolution)
    }

    @Test
    fun `steps down when the preferred height is missing`() {
        assertEquals(720, defaultStream(asSourceOrdered, preferredHeight = 900)?.resolution)
    }

    /** Overshooting a deliberate low setting is worse than undershooting. */
    @Test
    fun `takes the smallest when every stream is above the request`() {
        assertEquals(480, defaultStream(asSourceOrdered, preferredHeight = 360)?.resolution)
    }

    /** With no setting the extension's ordering is the signal. */
    @Test
    fun `keeps the source order when no height is set`() {
        assertEquals(
            "Art/4k-Hub · 2160p · BluRay · MKV",
            defaultStream(asSourceOrdered, preferredHeight = null)?.label,
        )
    }

    @Test
    fun `never prefers a stream with no parsed height`() {
        val withAuto = listOf(
            stream("Breach · Auto · HLS", 0),
            stream("Yoru · 1080p · HLS", 1080),
        )
        assertEquals(1080, defaultStream(withAuto, preferredHeight = 1080)?.resolution)
    }

    /** All heights unknown: nothing to compare, so the source's first stands. */
    @Test
    fun `falls back to the first stream when no height is known`() {
        val unlabelled = listOf(stream("Breach · Auto · HLS", 0))
        assertEquals("Breach · Auto · HLS", defaultStream(unlabelled, 1080)?.label)
    }

    @Test
    fun `returns null for an empty stream list`() {
        assertNull(defaultStream(emptyList(), 1080))
    }
}
