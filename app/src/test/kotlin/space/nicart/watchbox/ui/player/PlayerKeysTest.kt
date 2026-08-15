package space.nicart.watchbox.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the player's remote-key mapping.
 *
 * Unit-tested because the failure modes are all "the remote does the wrong thing",
 * which is slow to diagnose on a device and easy to introduce: the correct action
 * depends on three pieces of state at once, and there is no error when a combination
 * is wrong - just a player that seeks when it should have opened a panel.
 *
 * The state-precedence cases matter most. A panel open, a locked player and hidden
 * controls each change what the same key means, and getting the order wrong produces
 * a player that cannot be dismissed.
 */
class PlayerKeysTest {

    private fun map(
        keyCode: Int,
        controlsVisible: Boolean = true,
        panelOpen: Boolean = false,
        isLocked: Boolean = false,
    ) = mapPlayerKey(keyCode, controlsVisible, panelOpen, isLocked)

    // ------------------------------------------------- controls visible

    /**
     * The D-pad must not be consumed while the controls are on screen.
     *
     * Compose only moves focus on a key event nothing claimed. These keys used to seek
     * and toggle playback here, which consumed every press, so focus never left the
     * player surface: no button highlighted and none could be activated by remote.
     * Falling through is what makes the visible controls reachable.
     */
    @Test
    fun `directions are left to focus navigation when the controls are showing`() {
        assertEquals(PlayerKeyAction.NONE, map(KEY_DPAD_LEFT))
        assertEquals(PlayerKeyAction.NONE, map(KEY_DPAD_RIGHT))
        assertEquals(PlayerKeyAction.NONE, map(KEY_DPAD_UP))
        assertEquals(PlayerKeyAction.NONE, map(KEY_DPAD_DOWN))
    }

    /**
     * Centre activates whatever is focused, which is the focused button's own job.
     * Toggling playback here would steal OK from every other control.
     */
    @Test
    fun `centre is left to the focused control when the controls are showing`() {
        assertEquals(PlayerKeyAction.NONE, map(KEY_DPAD_CENTER))
        assertEquals(PlayerKeyAction.NONE, map(KEY_ENTER))
    }

    // ------------------------------------------------- controls hidden

    @Test
    fun `left and right seek directly with the controls hidden`() {
        // Revealing first made a skip a two-press operation, and the second press then
        // belonged to focus navigation rather than the transport - so it did nothing. The
        // seek readout sits outside the controls layer, so this is not a blind seek.
        assertEquals(
            PlayerKeyAction.SEEK_FORWARD,
            map(KEY_DPAD_RIGHT, controlsVisible = false),
        )
        assertEquals(
            PlayerKeyAction.SEEK_BACK,
            map(KEY_DPAD_LEFT, controlsVisible = false),
        )
    }

    @Test
    fun `centre reveals the controls rather than seeking`() {
        // Centre has no transport meaning of its own from here.
        assertEquals(
            PlayerKeyAction.SHOW_CONTROLS,
            map(KEY_DPAD_CENTER, controlsVisible = false),
        )
        assertEquals(
            PlayerKeyAction.SHOW_CONTROLS,
            map(KEY_ENTER, controlsVisible = false),
        )
    }

    @Test
    fun `left and right belong to focus once the controls are showing`() {
        // The hidden-controls seek must not leak into the visible state, where the same
        // keys move focus between buttons.
        assertEquals(PlayerKeyAction.NONE, map(KEY_DPAD_LEFT))
        assertEquals(PlayerKeyAction.NONE, map(KEY_DPAD_RIGHT))
    }

    @Test
    fun `up and down reveal the controls rather than doing nothing`() {
        // Any directional press should acknowledge itself; silence reads as a dead
        // remote.
        assertEquals(PlayerKeyAction.SHOW_CONTROLS, map(KEY_DPAD_UP, controlsVisible = false))
        assertEquals(PlayerKeyAction.SHOW_CONTROLS, map(KEY_DPAD_DOWN, controlsVisible = false))
    }

    @Test
    fun `media keys still act with the controls hidden`() {
        // A remote's transport buttons are unambiguous, so they never need a
        // reveal-first step.
        assertEquals(
            PlayerKeyAction.TOGGLE_PLAY,
            map(KEY_MEDIA_PLAY_PAUSE, controlsVisible = false),
        )
        assertEquals(
            PlayerKeyAction.SEEK_FORWARD,
            map(KEY_MEDIA_FAST_FORWARD, controlsVisible = false),
        )
    }

    // ------------------------------------------------------- media keys

    @Test
    fun `dedicated play and pause are not a toggle`() {
        // A remote with separate buttons must not have them behave identically, or
        // pressing Play on a playing video would pause it.
        assertEquals(PlayerKeyAction.PLAY, map(KEY_MEDIA_PLAY))
        assertEquals(PlayerKeyAction.PAUSE, map(KEY_MEDIA_PAUSE))
    }

    @Test
    fun `space toggles playback for keyboard and mouse users`() {
        assertEquals(PlayerKeyAction.TOGGLE_PLAY, map(KEY_SPACE))
    }

    @Test
    fun `track keys change episode`() {
        assertEquals(PlayerKeyAction.NEXT_EPISODE, map(KEY_MEDIA_NEXT))
        assertEquals(PlayerKeyAction.PREVIOUS_EPISODE, map(KEY_MEDIA_PREVIOUS))
    }

    /**
     * With the D-pad handed to focus navigation, these are the only way left to seek
     * from a remote, so they must work in both control states.
     */
    @Test
    fun `transport seek keys work whether the controls show or not`() {
        assertEquals(PlayerKeyAction.SEEK_BACK, map(KEY_MEDIA_REWIND))
        assertEquals(PlayerKeyAction.SEEK_FORWARD, map(KEY_MEDIA_FAST_FORWARD))
        assertEquals(
            PlayerKeyAction.SEEK_BACK,
            map(KEY_MEDIA_REWIND, controlsVisible = false),
        )
        assertEquals(
            PlayerKeyAction.SEEK_FORWARD,
            map(KEY_MEDIA_FAST_FORWARD, controlsVisible = false),
        )
    }

    // --------------------------------------------------- panel precedence

    @Test
    fun `an open panel keeps the d-pad for its own list`() {
        // Otherwise moving through a subtitle list would also seek the video.
        assertEquals(PlayerKeyAction.NONE, map(KEY_DPAD_LEFT, panelOpen = true))
        assertEquals(PlayerKeyAction.NONE, map(KEY_DPAD_CENTER, panelOpen = true))
    }

    @Test
    fun `back closes an open panel`() {
        assertEquals(PlayerKeyAction.DISMISS, map(KEY_BACK, panelOpen = true))
    }

    @Test
    fun `a panel outranks media keys`() {
        // Deliberate: while a list is open the remote belongs to it entirely, so
        // there is no doubt about what a press will affect.
        assertEquals(PlayerKeyAction.NONE, map(KEY_MEDIA_PLAY_PAUSE, panelOpen = true))
    }

    // ---------------------------------------------------- lock precedence

    @Test
    fun `locked ignores playback keys`() {
        // The point of the lock is that stray presses cannot change what is playing.
        assertEquals(PlayerKeyAction.SHOW_CONTROLS, map(KEY_DPAD_RIGHT, isLocked = true))
        assertEquals(PlayerKeyAction.SHOW_CONTROLS, map(KEY_MEDIA_PLAY_PAUSE, isLocked = true))
    }

    @Test
    fun `back still works while locked`() {
        // Otherwise a locked player is a trap with no way out.
        assertEquals(PlayerKeyAction.DISMISS, map(KEY_BACK, isLocked = true))
    }

    @Test
    fun `a panel outranks the lock`() {
        // Both suppress input; the panel is checked first so its Back closes the
        // panel rather than unlocking underneath it.
        assertEquals(
            PlayerKeyAction.NONE,
            map(KEY_DPAD_LEFT, panelOpen = true, isLocked = true),
        )
    }

    // ------------------------------------------------------------- misc

    @Test
    fun `unknown keys are not consumed`() {
        // Returning NONE lets them fall through to the system, which matters for
        // volume and power.
        assertEquals(PlayerKeyAction.NONE, map(keyCode = 999))
    }

    @Test
    fun `escape behaves like back`() {
        // For anyone testing on an emulator or with a keyboard attached.
        assertEquals(PlayerKeyAction.DISMISS, map(KEY_ESCAPE))
    }

    @Test
    fun `the key seek step matches the double-tap gesture`() {
        // So the two input methods agree about what a skip is.
        assertEquals(10_000L, PLAYER_KEY_SEEK_MS)
    }

    /**
     * The scrubber deliberately moves further per press than the skip buttons.
     *
     * They are separate constants because they answer different needs: the buttons are for a
     * precise nudge past something, the timeline is for crossing a whole film. Pinned so a
     * later tidy-up does not "unify" them and make long seeks unusable on a remote.
     */
    @Test
    fun `the scrubber step is coarser than a skip`() {
        assertTrue(
            SLIDER_SEEK_STEP_MS > PLAYER_KEY_SEEK_MS,
            "scrubber step $SLIDER_SEEK_STEP_MS should exceed skip step $PLAYER_KEY_SEEK_MS",
        )
    }

    /** Directional keys must still fall through, or focus cannot move between controls. */
    @Test
    fun `making the scrubber seekable did not consume directions globally`() {
        // The scrubber handles Left and Right itself, while it holds focus. The global mapping
        // must stay NONE: consuming them here is what previously stranded focus on the surface.
        assertEquals(PlayerKeyAction.NONE, map(KEY_DPAD_LEFT))
        assertEquals(PlayerKeyAction.NONE, map(KEY_DPAD_RIGHT))
    }
}
