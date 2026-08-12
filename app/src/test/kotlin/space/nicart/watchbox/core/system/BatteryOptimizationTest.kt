package space.nicart.watchbox.core.system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for which battery action applies in which state.
 *
 * Worth pinning because Android's API is asymmetric in a way that invites a wrong
 * simplification: there is a system dialog for *asking* for the exemption, but none for giving
 * it back. "Just always show the dialog" therefore looks correct and does nothing at all for a
 * user trying to turn the exemption off - the dialog does not appear for an app that already
 * holds it, so the row would silently do nothing.
 *
 * The intent-firing itself is not tested: it needs a real Context and PackageManager, and this
 * project has no Robolectric, so a test around it could only assert against a stub of the thing
 * being tested.
 */
class BatteryOptimizationTest {

    @Test
    fun `a restricted app is offered the request dialog`() {
        assertEquals(BatteryAction.REQUEST, batteryActionFor(isExempt = false))
    }

    /** The dialog cannot revoke, so an exempt app must be sent to the settings list. */
    @Test
    fun `an exempt app is sent to system settings instead`() {
        assertEquals(BatteryAction.MANAGE, batteryActionFor(isExempt = true))
    }

    @Test
    fun `the two states never map to the same action`() {
        assertEquals(2, setOf(batteryActionFor(true), batteryActionFor(false)).size)
    }

    /**
     * Android TV points both battery intents at a placeholder that finishes on creation, so
     * `startActivity` succeeds and no screen ever appears. The suffix is the only signal the
     * platform gives, and it is what makes the fall back to the app's details page happen -
     * without it the row silently did nothing on a television.
     */
    @Test
    fun `the stub activity is recognised by its suffix`() {
        assertTrue("com.android.tv.settings.EmptyStubActivity".endsWith("EmptyStubActivity"))
    }

    @Test
    fun `a real settings activity is not mistaken for the stub`() {
        val real = "com.android.tv.settings.device.apps.AppManagementActivity"

        assertFalse(real.endsWith("EmptyStubActivity"))
    }
}
