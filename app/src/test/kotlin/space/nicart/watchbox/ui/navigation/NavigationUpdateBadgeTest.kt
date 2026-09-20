package space.nicart.watchbox.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NavigationUpdateBadgeTest {

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
}
