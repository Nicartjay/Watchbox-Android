package space.nicart.watchbox.core.ui

import space.nicart.watchbox.data.local.POSTER_SCALE_MAX
import space.nicart.watchbox.data.local.POSTER_SCALE_MIN
import space.nicart.watchbox.data.local.UI_SCALE_MAX
import space.nicart.watchbox.data.local.UI_SCALE_MIN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the UI and poster scale bounds and their effect on grids.
 *
 * The bounds matter more than they look. UI scale is applied through density, so a
 * value near zero shrinks the entire app - including the setting itself - leaving the
 * user no way to undo it. That is unrecoverable without clearing app data, so the floor
 * is a correctness constraint rather than a preference.
 */
class ScaleTest {

    // ------------------------------------------------------------- bounds

    @Test
    fun `ui scale cannot shrink the app into illegibility`() {
        // The setting has to stay readable enough to reverse.
        assertTrue(UI_SCALE_MIN >= 0.75f, "floor $UI_SCALE_MIN is too small to recover from")
        assertTrue(UI_SCALE_MIN < 1f)
    }

    @Test
    fun `ui scale ceiling stays within what the layout can reflow`() {
        // Poster metrics are fixed dp, so beyond roughly 1.5 a phone clips instead of
        // reflowing.
        assertTrue(UI_SCALE_MAX <= 1.5f, "ceiling $UI_SCALE_MAX will clip fixed-width content")
        assertTrue(UI_SCALE_MAX > 1f)
    }

    @Test
    fun `poster scale allows a wider range than ui scale`() {
        // Posters carry no text of their own, so they tolerate more without breaking
        // legibility.
        assertTrue(POSTER_SCALE_MIN < UI_SCALE_MIN)
        assertTrue(POSTER_SCALE_MAX > UI_SCALE_MAX)
    }

    @Test
    fun `both ranges include the unscaled default`() {
        // Otherwise the app could not represent its own shipped appearance.
        assertTrue(1f in UI_SCALE_MIN..UI_SCALE_MAX)
        assertTrue(1f in POSTER_SCALE_MIN..POSTER_SCALE_MAX)
    }

    // ------------------------------------------------------ grid columns

    @Test
    fun `larger posters mean fewer columns`() {
        // A grid that ignored the scale would squash the posters rather than showing
        // fewer of them.
        val metrics = layoutMetricsFor(FormFactor.TABLET, widthDp = 1280)

        val normal = metrics.gridColumnsScaled(1f)
        val larger = metrics.gridColumnsScaled(1.5f)

        assertTrue(larger < normal, "expected fewer than $normal columns, got $larger")
    }

    @Test
    fun `smaller posters mean more columns`() {
        val metrics = layoutMetricsFor(FormFactor.TABLET, widthDp = 1280)
        assertTrue(metrics.gridColumnsScaled(0.7f) > metrics.gridColumnsScaled(1f))
    }

    @Test
    fun `an unscaled grid is unchanged`() {
        val metrics = layoutMetricsFor(FormFactor.TABLET, widthDp = 1280)
        assertEquals(metrics.gridColumns, metrics.gridColumnsScaled(1f))
    }

    @Test
    fun `a grid never collapses to a single column`() {
        // One column per row stops being a grid, and at the maximum poster scale on a
        // phone the arithmetic would otherwise reach 1.
        val phone = layoutMetricsFor(FormFactor.COMPACT, widthDp = 400)
        assertTrue(
            phone.gridColumnsScaled(POSTER_SCALE_MAX) >= 2,
            "columns collapsed to ${phone.gridColumnsScaled(POSTER_SCALE_MAX)}",
        )
    }

    @Test
    fun `column counts stay sane across the whole poster range`() {
        val metrics = layoutMetricsFor(FormFactor.TV, widthDp = 960)
        var previous = Int.MAX_VALUE

        // Walked in increasing scale; the count must never rise as posters grow.
        var scale = POSTER_SCALE_MIN
        while (scale <= POSTER_SCALE_MAX) {
            val columns = metrics.gridColumnsScaled(scale)
            assertTrue(columns in 2..12, "columns=$columns at scale=$scale")
            assertTrue(columns <= previous, "columns rose from $previous to $columns")
            previous = columns
            scale += 0.1f
        }
    }
}
