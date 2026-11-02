package space.nicart.watchbox.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import space.nicart.watchbox.extension.ExtensionUpdateCounts

class NavigationUpdateBadgeTest {

    @Test
    fun `phone Browse includes both update categories without a separate shorts tab`() {
        assertEquals(
            5,
            navigationUpdateCount(
                AppTab.BROWSE, ExtensionUpdateCounts(standard = 3, shortReels = 2),
                separateShortReels = false,
            ),
        )
        assertEquals(
            1,
            navigationUpdateCount(
                AppTab.BROWSE, ExtensionUpdateCounts(shortReels = 1),
                separateShortReels = false,
            ),
        )
    }

    @Test
    fun `zero or negative counts hide the badge`() {
        assertNull(navigationBadgeText(0))
        assertNull(navigationBadgeText(-1))
    }

    @Test
    fun `normal counts are shown`() {
        assertEquals("1", navigationBadgeText(1))
        assertEquals("42", navigationBadgeText(42))
    }

    @Test
    fun `large counts are capped`() {
        assertEquals("99+", navigationBadgeText(100))
    }

    @Test
    fun `Browse and Short Reels receive independent update badges`() {
        val counts = ExtensionUpdateCounts(standard = 3, shortReels = 2)
        assertEquals(3, navigationUpdateCount(AppTab.BROWSE, counts))
        assertEquals(2, navigationUpdateCount(AppTab.SHORT_REELS, counts))
        AppTab.entries.filterNot { it in listOf(AppTab.BROWSE, AppTab.SHORT_REELS) }.forEach {
            assertEquals(0, navigationUpdateCount(it, counts))
        }
    }

    @Test
    fun `short updates alone cannot light the Browse icon`() {
        assertNull(
            navigationBadgeText(
                navigationUpdateCount(AppTab.BROWSE, ExtensionUpdateCounts(shortReels = 1)),
            ),
        )
    }
}
