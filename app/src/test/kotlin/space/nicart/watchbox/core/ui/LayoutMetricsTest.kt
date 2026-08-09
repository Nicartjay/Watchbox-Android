package space.nicart.watchbox.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for form-factor layout metrics.
 *
 * Unit-tested because the numbers were previously duplicated as a `when` block in four
 * screens, and had already drifted - Search computed a column count it never used.
 * Centralising them only helps if the values stay coherent, and "coherent" here means
 * relationships between numbers rather than any single value.
 *
 * The TV cases are the ones worth pinning. A television needs *fewer, larger* targets
 * than a tablet of the same reported width, which is the opposite of what a
 * width-driven ladder produces - so a regression would look like a sensible change.
 */
class LayoutMetricsTest {

    // ------------------------------------------------------------------- tv

    @Test
    fun `tv padding clears the overscan region`() {
        // Televisions crop several percent of each edge; content flush to the edge can
        // be physically cut off.
        val tv = layoutMetricsFor(FormFactor.TV, widthDp = 960)
        assertEquals(48, tv.screenPadding.value.toInt())
    }

    @Test
    fun `tv uses fewer columns than a tablet of similar width`() {
        // The point of tracking form factor separately from width: at 960dp a tablet
        // gets 4 columns, a TV needs larger tiles read from three metres.
        val tv = layoutMetricsFor(FormFactor.TV, widthDp = 960)
        val tablet = layoutMetricsFor(FormFactor.TABLET, widthDp = 1280)

        assertTrue(
            tv.posterWidth.value > tablet.posterWidth.value,
            "TV posters (${tv.posterWidth}) must exceed tablet (${tablet.posterWidth})",
        )
    }

    @Test
    fun `tv is focus driven and uses a rail`() {
        val tv = layoutMetricsFor(FormFactor.TV, widthDp = 960)
        assertTrue(tv.isFocusDriven, "a TV has no touchscreen; everything must be reachable by D-pad")
        assertTrue(tv.usesNavRail)
        assertTrue(tv.isTv)
    }

    @Test
    fun `tv metrics do not vary with reported width`() {
        // TV panels report a range of dp widths for the same physical viewing
        // distance, so sizing off width would make a 4K set use tiny tiles.
        val small = layoutMetricsFor(FormFactor.TV, widthDp = 720)
        val large = layoutMetricsFor(FormFactor.TV, widthDp = 1920)

        assertEquals(small.posterWidth, large.posterWidth)
        assertEquals(small.gridColumns, large.gridColumns)
        assertEquals(small.screenPadding, large.screenPadding)
    }

    // --------------------------------------------------------------- compact

    @Test
    fun `a phone uses the bottom bar and no two-pane detail`() {
        val phone = layoutMetricsFor(FormFactor.COMPACT, widthDp = 400)

        assertFalse(phone.usesNavRail)
        assertFalse(phone.usesTwoPaneDetail)
        assertFalse(phone.isFocusDriven)
        assertEquals(3, phone.gridColumns)
    }

    // ---------------------------------------------------------------- tablet

    @Test
    fun `tablet columns increase with width`() {
        val widths = listOf(900, 1000, 1200, 1400)
        val columns = widths.map { layoutMetricsFor(FormFactor.TABLET, it).gridColumns }

        assertEquals(columns.sorted(), columns, "columns must not decrease as width grows")
        assertEquals(columns.distinct().size, columns.size, "each breakpoint should differ")
    }

    @Test
    fun `a narrow tablet keeps the bottom bar`() {
        // Below 1000dp a permanent rail leaves too little room beside it, so the
        // floating pill is still the better trade.
        val narrow = layoutMetricsFor(FormFactor.TABLET, widthDp = 900)
        assertFalse(narrow.usesNavRail)
        assertFalse(narrow.usesTwoPaneDetail)
    }

    @Test
    fun `a wide tablet gets a rail and two-pane detail`() {
        val wide = layoutMetricsFor(FormFactor.TABLET, widthDp = 1280)
        assertTrue(wide.usesNavRail)
        assertTrue(wide.usesTwoPaneDetail)
        assertTrue(wide.isTablet)
    }

    @Test
    fun `tablet padding grows on the widest screens`() {
        val mid = layoutMetricsFor(FormFactor.TABLET, widthDp = 1000)
        val wide = layoutMetricsFor(FormFactor.TABLET, widthDp = 1280)
        assertTrue(wide.screenPadding.value >= mid.screenPadding.value)
    }

    // ------------------------------------------------------------ invariants

    @Test
    fun `every form factor has usable values`() {
        // Guards against a zero or negative slipping in, which renders as invisible
        // content rather than an error.
        listOf(
            layoutMetricsFor(FormFactor.COMPACT, 400),
            layoutMetricsFor(FormFactor.TABLET, 1280),
            layoutMetricsFor(FormFactor.TV, 960),
        ).forEach { m ->
            assertTrue(m.gridColumns >= 3, "${m.formFactor} columns=${m.gridColumns}")
            assertTrue(m.posterWidth.value > 0f)
            assertTrue(m.screenPadding.value > 0f)
        }
    }

    @Test
    fun `only tv is focus driven`() {
        // A phone or tablet showing focus outlines after a tap looks like a fault.
        assertFalse(layoutMetricsFor(FormFactor.COMPACT, 400).isFocusDriven)
        assertFalse(layoutMetricsFor(FormFactor.TABLET, 1280).isFocusDriven)
        assertTrue(layoutMetricsFor(FormFactor.TV, 960).isFocusDriven)
    }
}
