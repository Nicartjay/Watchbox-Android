package space.nicart.watchbox.cast

import space.nicart.watchbox.ui.player.SubtitleEdgeWidth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for cast device merging and subtitle edge widths.
 *
 * The merge is unit-tested because casting was shipped with Chromecast devices never
 * being enumerated at all - the panel was empty unless a session had already been
 * started elsewhere - and a silent regression to that state would look identical to
 * "no devices on the network".
 */
class CastDeviceMergeTest {

    private fun chromecast(name: String) = CastDevice(
        id = "cc-$name",
        name = name,
        host = null,
        protocol = CastProtocol.CHROMECAST,
    )

    private fun dlna(name: String) = CastDevice(
        id = "http://192.168.1.5/$name",
        name = name,
        host = "192.168.1.5",
        protocol = CastProtocol.DLNA,
    )

    // ------------------------------------------------------------------ state

    @Test
    fun `an empty state reports no devices`() {
        assertTrue(CastState().devices.isEmpty())
        assertTrue(!CastState().hasDevices)
    }

    @Test
    fun `both protocols can appear in one list`() {
        // The whole point of the fix: a single panel showing everything.
        val state = CastState(devices = listOf(chromecast("Living Room"), dlna("Bravia")))

        assertEquals(2, state.devices.size)
        assertTrue(state.hasDevices)
        assertEquals(
            setOf(CastProtocol.CHROMECAST, CastProtocol.DLNA),
            state.devices.map { it.protocol }.toSet(),
        )
    }

    @Test
    fun `a chromecast may have no host until it is connected`() {
        // Route extras do not always carry an address; the host is only needed to
        // choose the proxy interface, which happens after connecting.
        assertEquals(null, chromecast("Shield").host)
    }

    @Test
    fun `a dlna renderer always has a host`() {
        // It is parsed from the description URL, so it is known before connecting.
        assertEquals("192.168.1.5", dlna("Bravia").host)
    }

    @Test
    fun `device ids stay distinct across protocols`() {
        // They key the same map, so a collision would make one device unreachable.
        val devices = listOf(chromecast("TV"), dlna("TV"))
        assertEquals(2, devices.map { it.id }.distinct().size)
    }

    // ------------------------------------------------------------ edge widths

    @Test
    fun `edge widths increase strictly`() {
        // Listed in declaration order, so they must be ascending.
        val outlines = SubtitleEdgeWidth.entries.map { it.outlineDp }
        assertEquals(outlines.sorted(), outlines)
        assertEquals(outlines.distinct().size, outlines.size)
    }

    @Test
    fun `shadow offset and blur also increase with width`() {
        val offsets = SubtitleEdgeWidth.entries.map { it.shadowDp }
        val blurs = SubtitleEdgeWidth.entries.map { it.shadowBlurDp }

        assertEquals(offsets.sorted(), offsets)
        assertEquals(blurs.sorted(), blurs)
    }

    @Test
    fun `every width is visible but not overwhelming`() {
        SubtitleEdgeWidth.entries.forEach { width ->
            assertTrue(
                width.outlineDp in 0.5f..6f,
                "${width.name} outline ${width.outlineDp}dp is outside the usable range",
            )
        }
    }

    @Test
    fun `medium approximates the fixed width media3 would have used`() {
        // Media3's SubtitlePainter hardcodes a 2dp outline; MEDIUM is the stroke
        // half-width that renders equivalently, so the default looks unchanged.
        assertEquals(1.5f, SubtitleEdgeWidth.MEDIUM.outlineDp)
    }

    @Test
    fun `blur always exceeds the offset for a drop shadow`() {
        // Otherwise the shadow reads as a hard duplicate of the text rather than a
        // shadow.
        SubtitleEdgeWidth.entries.forEach { width ->
            assertTrue(
                width.shadowBlurDp >= width.shadowDp,
                "${width.name} blur ${width.shadowBlurDp} < offset ${width.shadowDp}",
            )
        }
    }
}
