package space.nicart.watchbox.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the player gesture maths.
 *
 * Unit-tested because these are the parts that are hard to judge by feel on a
 * device: an inverted sign or a too-greedy edge zone feels like "the gesture is
 * fussy" rather than pointing at a specific mistake, and axis locking only
 * misbehaves on slightly-diagonal drags that are awkward to reproduce by hand.
 */
class PlayerGesturesTest {

    private val screenHeight = 1080

    // ----------------------------------------------------------- drag → delta

    @Test
    fun `dragging up increases the level`() {
        // Screen y grows downward, so an upward drag is negative and the sign
        // must be inverted or the control works backwards.
        assertTrue(verticalDragToDelta(dragPx = -100f, heightPx = screenHeight) > 0f)
    }

    @Test
    fun `dragging down decreases the level`() {
        assertTrue(verticalDragToDelta(dragPx = 100f, heightPx = screenHeight) < 0f)
    }

    @Test
    fun `a full-height swipe covers more than the whole range`() {
        // Deliberately over 1.0: needing the entire screen height to go from mute
        // to full is tiring, so the usable travel is about two thirds.
        val delta = verticalDragToDelta(dragPx = -screenHeight.toFloat(), heightPx = screenHeight)
        assertTrue(delta > 1f, "expected >1 for a full swipe, was $delta")
    }

    @Test
    fun `delta scales with screen height so the feel is device independent`() {
        // The same physical fraction of the screen should move the level equally
        // on a short and a tall display.
        val short = verticalDragToDelta(-540f, 1080)
        val tall = verticalDragToDelta(-1080f, 2160)
        assertEquals(short, tall, absoluteTolerance = 0.0001f)
    }

    @Test
    fun `zero height cannot divide by zero`() {
        assertEquals(0f, verticalDragToDelta(-100f, 0))
    }

    // ---------------------------------------------------------- axis locking

    @Test
    fun `clearly vertical drag is vertical`() {
        assertTrue(isVerticalDrag(dx = 2f, dy = 40f))
    }

    @Test
    fun `clearly horizontal drag is not vertical`() {
        assertFalse(isVerticalDrag(dx = 40f, dy = 2f))
    }

    @Test
    fun `ambiguous diagonal drag is not treated as vertical`() {
        // A 45-degree drag is most likely a sloppy seek, so it must not be
        // claimed as a volume change.
        assertFalse(isVerticalDrag(dx = 30f, dy = 30f))
    }

    @Test
    fun `vertical must clearly dominate to win`() {
        // Just past parity is still not enough.
        assertFalse(isVerticalDrag(dx = 30f, dy = 33f))
        assertTrue(isVerticalDrag(dx = 30f, dy = 50f))
    }

    @Test
    fun `direction does not affect axis detection`() {
        // Down and left must classify the same as up and right.
        assertTrue(isVerticalDrag(dx = -2f, dy = -40f))
        assertFalse(isVerticalDrag(dx = -40f, dy = -2f))
    }

    // -------------------------------------------------------- system edges

    @Test
    fun `drags starting at the top edge are left to the system`() {
        // That is where the notification shade is pulled from.
        assertTrue(isInSystemEdgeZone(y = 10f, heightPx = screenHeight, edgePx = 48f))
    }

    @Test
    fun `drags starting at the bottom edge are left to the system`() {
        // The navigation gesture area.
        assertTrue(isInSystemEdgeZone(y = 1075f, heightPx = screenHeight, edgePx = 48f))
    }

    @Test
    fun `drags starting in the middle belong to the player`() {
        assertFalse(isInSystemEdgeZone(y = 540f, heightPx = screenHeight, edgePx = 48f))
        assertFalse(isInSystemEdgeZone(y = 60f, heightPx = screenHeight, edgePx = 48f))
    }

    @Test
    fun `edge zone is capped so it cannot swallow a short screen`() {
        // An absurd exclusion on a small viewport would leave no usable area,
        // so it is capped as a fraction of height instead.
        val tiny = 200
        assertFalse(
            isInSystemEdgeZone(y = 100f, heightPx = tiny, edgePx = 500f),
            "the centre of the screen must stay usable",
        )
    }

    @Test
    fun `zero height is not an edge`() {
        assertFalse(isInSystemEdgeZone(y = 0f, heightPx = 0, edgePx = 48f))
    }

    // ------------------------------------------------- double-tap seek readout

    /**
     * A run of taps in one direction reads as one total.
     *
     * Four forward taps should report "+40s", not flash "+10s" four times - the total is the
     * number a viewer is actually judging, and a per-tap flash makes a fast run unreadable.
     */
    @Test
    fun `taps in the same direction accumulate`() {
        var total = 0L
        repeat(4) { total = accumulateSeekTap(total, 10_000L) }

        assertEquals(40_000L, total)
    }

    @Test
    fun `backward taps accumulate too`() {
        var total = 0L
        repeat(3) { total = accumulateSeekTap(total, -10_000L) }

        assertEquals(-30_000L, total)
    }

    /**
     * Reversing starts over rather than netting off.
     *
     * A backward tap after forward ones is a correction, not part of the same run. Netting would
     * report a figure nobody asked about - and at exactly one tap back it would read "+0s", which
     * looks identical to the tap having been ignored.
     */
    @Test
    fun `reversing direction restarts the total`() {
        val forward = accumulateSeekTap(accumulateSeekTap(0L, 10_000L), 10_000L)
        assertEquals(20_000L, forward)

        assertEquals(-10_000L, accumulateSeekTap(forward, -10_000L))
    }

    @Test
    fun `the first tap sets the total from zero`() {
        assertEquals(10_000L, accumulateSeekTap(0L, 10_000L))
        assertEquals(-10_000L, accumulateSeekTap(0L, -10_000L))
    }

    /** The sign drives the arrow direction, so it must survive accumulation. */
    @Test
    fun `the sign reflects the direction of travel`() {
        assertTrue(accumulateSeekTap(0L, 10_000L) > 0)
        assertTrue(accumulateSeekTap(-20_000L, -10_000L) < 0)
    }
}
