package space.nicart.watchbox.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.math.abs

class TimelineKeySeekTest {
    private val duration = 7_200_000L

    private fun TimelineKeySeek.press(
        key: Int = KEY_DPAD_RIGHT,
        time: Long = 0L,
        repeat: Int = 0,
        position: Long = 300_000L,
        duration: Long = this@TimelineKeySeekTest.duration,
    ) = keyDown(key, repeat, time, position, duration)

    @Test
    fun `a tap still moves fifteen seconds in either direction`() {
        assertEquals(315_000L, TimelineKeySeek().press())
        assertEquals(285_000L, TimelineKeySeek().press(key = KEY_DPAD_LEFT))
    }

    @Test
    fun `holding accelerates exponentially before reaching the speed cap`() {
        val firstSecond = timelineHoldDistanceMs(1_000) - timelineHoldDistanceMs(0)
        val secondSecond = timelineHoldDistanceMs(2_000) - timelineHoldDistanceMs(1_000)
        val thirdSecond = timelineHoldDistanceMs(3_000) - timelineHoldDistanceMs(2_000)
        assertTrue(abs(firstSecond * 2 - secondSecond) <= 2L)
        assertTrue(abs(secondSecond * 2 - thirdSecond) <= 2L)
    }

    @Test
    fun `long holds are capped at two minutes of video per second`() {
        assertEquals(120_000L, timelineHoldDistanceMs(4_000) - timelineHoldDistanceMs(3_000))
        assertEquals(120_000L, timelineHoldDistanceMs(60_000) - timelineHoldDistanceMs(59_000))
    }

    @Test
    fun `repeat rate does not change how far a held key travels`() {
        val fast = TimelineKeySeek().apply { press() }
        val slow = TimelineKeySeek().apply { press() }
        for (time in 50L..2_000L step 50L) fast.press(time = time, repeat = (time / 50).toInt())
        assertEquals(
            fast.press(time = 2_000, repeat = 41),
            slow.press(time = 2_000, repeat = 1),
        )
    }

    @Test
    fun `stale playback ticks cannot overwrite the held seek target`() {
        val seek = TimelineKeySeek()
        seek.press()
        assertEquals(
            300_000L + timelineHoldDistanceMs(2_000),
            seek.press(time = 2_000, repeat = 5, position = 100_000L),
        )
    }

    @Test
    fun `release resets acceleration for the next press`() {
        val seek = TimelineKeySeek()
        seek.press()
        seek.press(time = 2_000, repeat = 5)
        seek.keyUp(KEY_DPAD_RIGHT)
        assertEquals(615_000L, seek.press(time = 3_000, position = 600_000L))
    }

    @Test
    fun `reversing direction starts with a precise nudge from the last target`() {
        val seek = TimelineKeySeek()
        seek.press()
        val forward = seek.press(time = 2_000, repeat = 5)!!
        assertEquals(forward - SLIDER_SEEK_STEP_MS, seek.press(key = KEY_DPAD_LEFT, time = 2_010))
    }

    @Test
    fun `focus loss resets both the target and acceleration`() {
        val seek = TimelineKeySeek()
        seek.press()
        seek.press(time = 2_000, repeat = 5)
        seek.reset()
        assertEquals(615_000L, seek.press(time = 3_000, repeat = 6, position = 600_000L))
    }

    @Test
    fun `seeks cannot pass either end even during very long holds`() {
        val forward = TimelineKeySeek().apply { press() }
        val back = TimelineKeySeek().apply { press(key = KEY_DPAD_LEFT) }
        assertEquals(duration, forward.press(time = Long.MAX_VALUE, repeat = 1))
        assertEquals(0L, back.press(key = KEY_DPAD_LEFT, time = Long.MAX_VALUE, repeat = 1))
        assertEquals(0L, TimelineKeySeek().press(key = KEY_DPAD_LEFT, position = 5_000))
        assertEquals(duration, TimelineKeySeek().press(position = duration - 5_000))
    }

    @Test
    fun `unknown duration and non-directional keys cannot seek`() {
        assertNull(TimelineKeySeek().press(duration = 0))
        assertNull(TimelineKeySeek().press(duration = -1))
        assertNull(TimelineKeySeek().press(key = KEY_DPAD_UP))
        assertNull(TimelineKeySeek().press(key = KEY_DPAD_CENTER))
    }

    @Test
    fun `an unrelated key release does not cancel the active hold`() {
        val seek = TimelineKeySeek().apply { press() }
        seek.keyUp(KEY_DPAD_CENTER)
        assertEquals(300_000L + timelineHoldDistanceMs(2_000), seek.press(time = 2_000, repeat = 5))
    }

    @Test
    fun `negative elapsed time retains the initial tap distance`() {
        assertEquals(SLIDER_SEEK_STEP_MS, timelineHoldDistanceMs(-1))
        assertTrue(timelineHoldDistanceMs(500) > SLIDER_SEEK_STEP_MS)
    }
}
