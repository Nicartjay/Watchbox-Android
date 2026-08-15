package space.nicart.watchbox.ui.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the episode range chips.
 *
 * The threshold behaviour is the part worth pinning: offering a single "1-50" chip on a
 * 24-episode season is a control that does nothing, and the absence of chips is what tells
 * the viewer the whole list is already on screen.
 */
class EpisodeRangesTest {

    @Test
    fun `a short list gets no ranges`() {
        assertTrue(episodeRanges(1).isEmpty())
        assertTrue(episodeRanges(12).isEmpty())
        assertTrue(episodeRanges(24).isEmpty())
    }

    @Test
    fun `exactly the block size still gets no ranges`() {
        // 50 episodes fit in one block, so chips would offer a single choice.
        assertTrue(episodeRanges(50).isEmpty())
    }

    @Test
    fun `one past the block size splits in two`() {
        val ranges = episodeRanges(51)

        assertEquals(2, ranges.size)
        assertEquals("1-50", ranges[0].label)
        assertEquals("51-51", ranges[1].label)
    }

    @Test
    fun `labels are one-based while indices are not`() {
        val ranges = episodeRanges(120)

        assertEquals(listOf("1-50", "51-100", "101-120"), ranges.map { it.label })
        assertEquals(0, ranges[0].fromIndex)
        assertEquals(49, ranges[0].toIndex)
        assertEquals(50, ranges[1].fromIndex)
        assertEquals(119, ranges[2].toIndex)
    }

    @Test
    fun `a long running series splits cleanly`() {
        // One Piece territory, which is the reason this exists.
        val ranges = episodeRanges(1122)

        assertEquals(23, ranges.size)
        assertEquals("1-50", ranges.first().label)
        assertEquals("1101-1122", ranges.last().label)
        // Every episode is covered exactly once.
        assertEquals(1122, ranges.sumOf { it.toIndex - it.fromIndex + 1 })
    }

    @Test
    fun `ranges are contiguous with no gaps or overlap`() {
        val ranges = episodeRanges(237)

        ranges.zipWithNext().forEach { (a, b) ->
            assertEquals(a.toIndex + 1, b.fromIndex)
        }
        assertEquals(0, ranges.first().fromIndex)
        assertEquals(236, ranges.last().toIndex)
    }

    @Test
    fun `an empty list is safe`() {
        assertTrue(episodeRanges(0).isEmpty())
    }

    @Test
    fun `the block size is configurable for testing`() {
        val ranges = episodeRanges(count = 25, size = 10)

        assertEquals(listOf("1-10", "11-20", "21-25"), ranges.map { it.label })
    }
}
