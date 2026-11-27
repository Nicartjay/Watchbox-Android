package space.nicart.watchbox.ui.player

import space.nicart.watchbox.core.ui.FormFactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerTouchInteractionsTest {
    @Test
    fun `brightness stays brightness while its indicator fades out`() {
        val shown = retainedLevelGesture(VerticalGesture.NONE, VerticalGesture.BRIGHTNESS)
        assertEquals(VerticalGesture.BRIGHTNESS, retainedLevelGesture(shown, VerticalGesture.NONE))
    }

    @Test
    fun `volume stays volume on release but the next brightness gesture replaces it`() {
        val fading = retainedLevelGesture(VerticalGesture.VOLUME, VerticalGesture.NONE)
        assertEquals(VerticalGesture.VOLUME, fading)
        assertEquals(VerticalGesture.BRIGHTNESS, retainedLevelGesture(fading, VerticalGesture.BRIGHTNESS))
    }

    @Test
    fun `a new volume gesture replaces a fading brightness gesture`() {
        assertEquals(
            VerticalGesture.VOLUME,
            retainedLevelGesture(VerticalGesture.BRIGHTNESS, VerticalGesture.VOLUME),
        )
    }

    @Test
    fun `speed hold is enabled only for the mobile build on touch devices`() {
        assertTrue(supportsHoldToBoost(true, FormFactor.COMPACT))
        assertTrue(supportsHoldToBoost(true, FormFactor.TABLET))
        assertFalse(supportsHoldToBoost(true, FormFactor.TV))
        FormFactor.entries.forEach { assertFalse(supportsHoldToBoost(false, it)) }
    }

    @Test
    fun `either side supports holding without claiming the centre`() {
        assertTrue(isSpeedHoldArea(100f, 1_000))
        assertTrue(isSpeedHoldArea(900f, 1_000))
        assertFalse(isSpeedHoldArea(500f, 1_000))
        assertFalse(isSpeedHoldArea(-1f, 1_000))
        assertFalse(isSpeedHoldArea(1_001f, 1_000))
        assertFalse(isSpeedHoldArea(0f, 0))
    }

    @Test
    fun `holding temporarily overrides every selected speed with exactly two x`() {
        listOf(0.5f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { selected ->
            assertEquals(2f, effectivePlaybackSpeed(selected, holding = true))
            assertEquals(selected, effectivePlaybackSpeed(selected, holding = false))
        }
    }
}
