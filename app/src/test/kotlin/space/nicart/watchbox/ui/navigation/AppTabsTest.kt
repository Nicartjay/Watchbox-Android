package space.nicart.watchbox.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AppTabsTest {
    @Test
    fun `Back from every other top-level section returns Home`() {
        AppTab.entries.filter { it != AppTab.HOME }.forEach {
            assertEquals(AppTab.HOME, it.backDestination(), it.name)
        }
    }

    @Test
    fun `Back from Browse returns Home and the next Back falls through to close`() {
        val home = AppTab.BROWSE.backDestination()
        assertEquals(AppTab.HOME, home)
        assertNull(home?.backDestination())
        assertNull(AppTab.HOME.backDestination())
    }

    @Test
    fun `phones retain their five existing tabs`() {
        assertEquals(
            listOf(AppTab.HOME, AppTab.SEARCH, AppTab.BROWSE, AppTab.LIBRARY, AppTab.SETTINGS),
            appTabs(showShortReels = false),
        )
        assertFalse(AppTab.SHORT_REELS in appTabs(showShortReels = false))
    }

    @Test
    fun `tablet and TV navigation add a single Short Reels section`() {
        val tabs = appTabs(showShortReels = true)
        assertEquals(1, tabs.count { it == AppTab.SHORT_REELS })
        assertEquals(6, tabs.size)
        assertEquals(
            appTabs(showShortReels = false),
            tabs.filterNot { it == AppTab.SHORT_REELS },
        )
    }
}
