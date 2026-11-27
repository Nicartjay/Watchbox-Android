package space.nicart.watchbox.ui.browse

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowseInteractionsTest {
    @Test
    fun `the mobile filter drawer does not consume Right or request Apply focus`() {
        var requests = 0
        assertFalse(
            handleFilterApplyShortcut(false, Key.DirectionRight, KeyEventType.KeyDown) {
                requests++
                true
            },
        )
        assertEquals(0, requests)
    }

    @Test
    fun `the enabled shortcut requests Apply focus once on Right key down`() {
        var requests = 0
        val focusApply = { requests++; true }

        assertTrue(handleFilterApplyShortcut(true, Key.DirectionRight, KeyEventType.KeyDown, focusApply))
        assertFalse(handleFilterApplyShortcut(true, Key.DirectionRight, KeyEventType.KeyUp, focusApply))

        assertEquals(1, requests)
    }

    @Test
    fun `the Apply shortcut leaves selection activation and other directions alone`() {
        var requests = 0
        listOf(Key.DirectionLeft, Key.DirectionUp, Key.DirectionDown, Key.Enter, Key.Escape).forEach { key ->
            assertFalse(
                handleFilterApplyShortcut(true, key, KeyEventType.KeyDown) {
                    requests++
                    true
                },
            )
        }
        assertEquals(0, requests)
    }

    @Test
    fun `failed Apply focus does not consume the remote press`() {
        assertFalse(handleFilterApplyShortcut(true, Key.DirectionRight, KeyEventType.KeyDown) { false })
    }

    @Test
    fun `an explicitly submitted blank query returns to Popular`() {
        listOf("", " ", "\t\n").forEach { query ->
            assertEquals(BrowseMode.POPULAR, submittedBrowseMode(query))
        }
    }

    @Test
    fun `a nonblank explicit query uses Search`() {
        listOf("Title", " two words ", "123").forEach { query ->
            assertEquals(BrowseMode.SEARCH, submittedBrowseMode(query))
        }
    }
}
