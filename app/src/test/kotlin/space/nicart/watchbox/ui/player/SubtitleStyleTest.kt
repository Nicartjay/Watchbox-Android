package space.nicart.watchbox.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for subtitle style values and alpha maths.
 *
 * The `CaptionStyleCompat` construction itself needs Media3's Android classes, so
 * what is tested here is the part that is both pure and easy to get subtly wrong:
 * the alpha packing, and the invariants the enums have to satisfy for the settings
 * UI to make sense. A wrong alpha shift produces a fully transparent or fully opaque
 * background - which looks like the opacity setting simply does nothing.
 */
class SubtitleStyleTest {

    // ------------------------------------------------------------ alpha maths

    @Test
    fun `full opacity keeps the colour opaque`() {
        val result = 0x000000.withAlpha(1f)
        assertEquals(0xFF000000.toInt(), result)
    }

    @Test
    fun `zero opacity clears the alpha channel`() {
        assertEquals(0x00000000, 0x000000.withAlpha(0f))
    }

    @Test
    fun `alpha is packed into the top byte, leaving rgb intact`() {
        // A shift error here silently destroys the colour as well as the alpha.
        val result = 0x123456.withAlpha(1f)
        assertEquals(0x123456, result and 0x00FFFFFF, "rgb must be preserved")
        assertEquals(0xFF, (result shr 24) and 0xFF, "alpha must occupy the top byte")
    }

    @Test
    fun `an existing alpha is replaced rather than combined`() {
        // Input colours are already opaque, so a bitwise-or would pin every
        // background to fully opaque and make the setting inert.
        val opaque = 0xFF000000.toInt()
        val half = opaque.withAlpha(0.5f)
        assertEquals(127, (half shr 24) and 0xFF)
    }

    @Test
    fun `opacity is clamped rather than wrapping`() {
        // Out-of-range values must not overflow into a different alpha.
        assertEquals(0xFF, (0x000000.withAlpha(5f) shr 24) and 0xFF)
        assertEquals(0x00, (0x000000.withAlpha(-2f) shr 24) and 0xFF)
    }

    @Test
    fun `mid opacity is a mid alpha`() {
        val alpha = (0x000000.withAlpha(0.6f) shr 24) and 0xFF
        assertTrue(alpha in 150..156, "expected ~153 for 60%, was $alpha")
    }

    // ------------------------------------------------------------------ sizes

    @Test
    fun `sizes increase strictly`() {
        // The UI lists them in declaration order, so it must be ascending or the
        // options read as unordered.
        val fractions = SubtitleSize.entries.map { it.fraction }
        assertEquals(fractions.sorted(), fractions)
        assertEquals(fractions.distinct().size, fractions.size)
    }

    @Test
    fun `every size is a usable fraction of the view`() {
        // A fraction is not a point size; values outside this band render either
        // illegibly small or large enough to cover the picture.
        SubtitleSize.entries.forEach { size ->
            assertTrue(
                size.fraction in 0.02f..0.15f,
                "${size.name} fraction ${size.fraction} is outside the usable range",
            )
        }
    }

    @Test
    fun `medium matches the media3 default`() {
        // So "Medium" means the same thing as leaving subtitles alone.
        assertEquals(0.0533f, SubtitleSize.MEDIUM.fraction, absoluteTolerance = 0.0001f)
    }

    // ------------------------------------------------------------ backgrounds

    @Test
    fun `every background style has a distinct label`() {
        val labels = SubtitleBackground.entries.map { it.label }
        assertEquals(labels.distinct().size, labels.size)
        assertTrue(labels.none { it.isBlank() })
    }

    @Test
    fun `box and full-width band are separate styles`() {
        // They differ only in whether Media3's window or background is used, which
        // is easy to collapse into one by accident.
        assertNotEquals(SubtitleBackground.BACKGROUND, SubtitleBackground.FULL_BACKGROUND)
    }

    @Test
    fun `all five background styles are offered`() {
        assertEquals(5, SubtitleBackground.entries.size)
    }

    // ---------------------------------------------------------------- colours

    @Test
    fun `preset colours are opaque and distinct`() {
        val colors = SUBTITLE_TEXT_COLORS.map { it.second }

        assertEquals(colors.distinct().size, colors.size)
        colors.forEach { color ->
            // A preset with no alpha would render invisibly.
            assertEquals(0xFF, (color shr 24) and 0xFF, "preset colours must be opaque")
        }
    }

    @Test
    fun `white is the first preset`() {
        // It is what nearly everyone wants, so it should not be hunted for.
        assertEquals(0xFFFFFFFF.toInt(), SUBTITLE_TEXT_COLORS.first().second)
    }
}
