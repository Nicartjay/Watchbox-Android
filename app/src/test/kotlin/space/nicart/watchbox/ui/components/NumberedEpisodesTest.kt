package space.nicart.watchbox.ui.components

import androidx.compose.ui.unit.dp
import space.nicart.watchbox.domain.EpisodeEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NumberedEpisodesTest {
    @Test
    fun `episode buttons display only the provider's number`() {
        assertEquals("12", episode(12f, "Episode 12 - A long title").numberLabelOrIndex(0))
        assertEquals("104", episode(104f).numberLabelOrIndex(3))
    }

    @Test
    fun `unknown numbers use a one-based position`() {
        assertEquals("1", episode(-1f).numberLabelOrIndex(0))
        assertEquals("9", episode(-1f).numberLabelOrIndex(8))
    }

    @Test
    fun `fallback numbers do not restart at the next range of fifty`() {
        assertEquals("51", episode(-1f).numberLabelOrIndex(50))
        assertEquals("101", episode(-1f).numberLabelOrIndex(100))
    }

    @Test
    fun `fractional episodes and episode zero are not renumbered`() {
        assertEquals("12.5", episode(12.5f).numberLabelOrIndex(12))
        assertEquals("0", episode(0f).numberLabelOrIndex(0))
    }

    @Test
    fun `invalid source numbers never appear as NaN or Infinity`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertEquals("4", episode(it).numberLabelOrIndex(3))
        }
    }

    @Test
    fun `narrow pickers always retain at least one column`() {
        listOf(0, 32, 55, 56, 119).forEach {
            assertEquals(1, numberedEpisodeColumns(it.dp))
        }
        assertEquals(2, numberedEpisodeColumns(120.dp))
    }

    @Test
    fun `phone and tablet grids leave space for every touch target and gap`() {
        listOf(120, 272, 312, 372, 720, 800, 1200).forEach { width ->
            val columns = numberedEpisodeColumns(width.dp)
            val minimum = EPISODE_NUMBER_MIN_SIZE * columns +
                EPISODE_NUMBER_GAP * (columns - 1)
            assertTrue(minimum <= width.dp, "$columns columns do not fit in $width dp")
        }
        assertEquals(5, numberedEpisodeColumns(312.dp))
        assertEquals(12, numberedEpisodeColumns(800.dp))
    }

    private fun episode(number: Float, name: String = "Episode") = EpisodeEntry(
        url = "/episode/$number",
        name = name,
        number = number,
        dateUpload = 0L,
        scanlator = null,
    )
}
