package space.nicart.watchbox.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the two-tap subtitle sync measurement and its offset arithmetic.
 *
 * The sign convention is the part worth pinning: getting it backwards doubles the error
 * instead of correcting it, and the symptom - subtitles drifting further out - looks like
 * the feature not working rather than working inverted.
 */
class SubtitleSyncTest {

    // -------------------------------------------------------------- arming

    @Test
    fun `nothing is armed until a mark is taken`() {
        val idle = SyncCalibration()

        assertFalse(idle.isArmed)
        // Both buttons available at rest.
        assertTrue(idle.isEnabled(SyncMark.SUBTITLE))
        assertTrue(idle.isEnabled(SyncMark.SPOKEN))
    }

    @Test
    fun `taking one mark disables that button and leaves the other`() {
        val armed = SyncCalibration(firstMark = SyncMark.SUBTITLE, firstPositionMs = 10_000L)

        assertTrue(armed.isArmed)
        assertFalse(armed.isEnabled(SyncMark.SUBTITLE))
        assertTrue(armed.isEnabled(SyncMark.SPOKEN))
    }

    // ------------------------------------------------------------- resolve

    @Test
    fun `subtitle before speech yields a positive delay`() {
        // Subtitle at 10s, spoken at 12s: the subtitles are 2s early, so delay them.
        val armed = SyncCalibration(SyncMark.SUBTITLE, 10_000L)

        assertEquals(2_000L, armed.resolve(SyncMark.SPOKEN, 12_000L))
    }

    @Test
    fun `speech before subtitle yields a negative delay`() {
        // Spoken at 10s, subtitle at 12s: the subtitles are 2s late, so pull them back.
        val armed = SyncCalibration(SyncMark.SPOKEN, 10_000L)

        assertEquals(-2_000L, armed.resolve(SyncMark.SUBTITLE, 12_000L))
    }

    @Test
    fun `the order of the two taps does not change the result`() {
        // The same physical situation measured both ways must agree. This is why the
        // arithmetic is spoken-minus-subtitle rather than second-minus-first.
        val subtitleFirst = SyncCalibration(SyncMark.SUBTITLE, 30_000L)
            .resolve(SyncMark.SPOKEN, 31_500L)
        val spokenFirst = SyncCalibration(SyncMark.SPOKEN, 31_500L)
            .resolve(SyncMark.SUBTITLE, 30_000L)

        assertEquals(1_500L, subtitleFirst)
        assertEquals(subtitleFirst, spokenFirst)
    }

    @Test
    fun `marking the same event twice is not a measurement`() {
        val armed = SyncCalibration(SyncMark.SUBTITLE, 10_000L)

        assertNull(armed.resolve(SyncMark.SUBTITLE, 12_000L))
    }

    @Test
    fun `resolving without a first mark yields nothing`() {
        assertNull(SyncCalibration().resolve(SyncMark.SPOKEN, 12_000L))
    }

    @Test
    fun `simultaneous marks yield no correction`() {
        val armed = SyncCalibration(SyncMark.SUBTITLE, 10_000L)

        assertEquals(0L, armed.resolve(SyncMark.SPOKEN, 10_000L))
    }

    // ------------------------------------------------- repeated measurement

    @Test
    fun `a second measurement measures only the remaining error`() {
        // The marks are taken against subtitles that are ALREADY shifted by the current
        // offset, so what the user measures is the error that is left - never the total.
        // Adding it is therefore correct, and is what makes repeated measurement converge.
        //
        // Cue at media 10s, offset already +3s, so it displays at 13s. True speech at 15s.
        val armed = SyncCalibration(SyncMark.SUBTITLE, firstPositionMs = 13_000L)
        val remaining = armed.resolve(SyncMark.SPOKEN, 15_000L)

        assertEquals(2_000L, remaining)

        // 3s + 2s = 5s, which puts the cue at 10 + 5 = 15s: exactly the speech.
        assertEquals(5_000L, 3_000L + remaining!!)
    }

    @Test
    fun `converging measurements approach zero remaining error`() {
        // Each pass halves the error in this example; the point is that the total only ever
        // grows towards the right answer rather than oscillating.
        var offset = 0L
        var displayedError = 4_000L

        repeat(3) {
            val armed = SyncCalibration(SyncMark.SUBTITLE, firstPositionMs = 10_000L)
            val measured = armed.resolve(SyncMark.SPOKEN, 10_000L + displayedError)!!
            offset += measured
            displayedError /= 2
        }

        // 4000 + 2000 + 1000
        assertEquals(7_000L, offset)
    }

    @Test
    fun `a perfectly synced subtitle measures no change`() {
        // Once correct, both taps land at the same instant and the offset is left alone.
        val armed = SyncCalibration(SyncMark.SUBTITLE, firstPositionMs = 20_000L)

        assertEquals(0L, armed.resolve(SyncMark.SPOKEN, 20_000L))
    }

    // --------------------------------------------------------------- clamp

    @Test
    fun `offsets are clamped to the supported range`() {
        assertEquals(SUBTITLE_OFFSET_LIMIT_MS, clampSubtitleOffset(SUBTITLE_OFFSET_LIMIT_MS * 2))
        assertEquals(-SUBTITLE_OFFSET_LIMIT_MS, clampSubtitleOffset(-SUBTITLE_OFFSET_LIMIT_MS * 2))
        assertEquals(1_500L, clampSubtitleOffset(1_500L))
        assertEquals(0L, clampSubtitleOffset(0L))
    }

    @Test
    fun `a minute of desync is accepted`() {
        // The earlier ten-second ceiling refused corrections that were legitimate: a
        // subtitle timed to a different release - an extra recap, a broadcast cut - can be
        // out by far more than that, and clamping it read as the feature not working.
        assertEquals(60_000L, clampSubtitleOffset(60_000L))
        assertEquals(-90_000L, clampSubtitleOffset(-90_000L))
    }

    @Test
    fun `a mistap beyond the bound is clamped rather than rejected`() {
        // Walking away mid-measurement must not produce an offset that moves every cue
        // outside the runtime, leaving no subtitles to correct by.
        val armed = SyncCalibration(SyncMark.SUBTITLE, 10_000L)
        val raw = armed.resolve(SyncMark.SPOKEN, SUBTITLE_OFFSET_LIMIT_MS * 3)!!

        assertEquals(SUBTITLE_OFFSET_LIMIT_MS, clampSubtitleOffset(raw))
    }

    // -------------------------------------------------------------- format

    @Test
    fun `offsets are formatted with an explicit sign`() {
        assertEquals("+2.00s", formatSubtitleOffset(2_000L))
        assertEquals("-2.00s", formatSubtitleOffset(-2_000L))
        assertEquals("+0.25s", formatSubtitleOffset(250L))
        // Zero reads as "no correction" and needs no sign.
        assertEquals("0.00s", formatSubtitleOffset(0L))
    }

    @Test
    fun `the step divides evenly into a second`() {
        // Four nudges per second keeps the stepper predictable.
        assertEquals(0L, 1_000L % SUBTITLE_OFFSET_STEP_MS)
    }
}
