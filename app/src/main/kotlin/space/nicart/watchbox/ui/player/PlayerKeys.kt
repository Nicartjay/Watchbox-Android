package space.nicart.watchbox.ui.player

/**
 * What a remote key press should do in the player.
 *
 * Mapped to an intent rather than acted on directly so the mapping is testable
 * without a player, a surface or a device - the part that is easy to get wrong is
 * which key means what in which state, not the call that follows.
 */
enum class PlayerKeyAction {
    /** Reveal the controls without changing playback. */
    SHOW_CONTROLS,
    TOGGLE_PLAY,
    PLAY,
    PAUSE,
    SEEK_BACK,
    SEEK_FORWARD,
    NEXT_EPISODE,
    PREVIOUS_EPISODE,

    /** Close a panel, or leave the player when none is open. */
    DISMISS,
    NONE,
}

/**
 * Maps a key code to a player action.
 *
 * Written against raw key codes so it can be unit-tested; the caller translates from
 * Compose's `KeyEvent`.
 *
 * The rules encode three behaviours a remote needs and a touchscreen does not:
 *
 *  - **Directional keys reveal the controls, then hand them over.** On a TV the
 *    controls are usually hidden, and a blind seek gives no feedback about where you
 *    landed. The first press surfaces the scrubber; afterwards the D-pad moves focus
 *    between the on-screen buttons instead of being consumed here, so what the remote
 *    is about to activate is always visible.
 *  - **Dedicated media keys always act.** A remote's play/pause button should work
 *    whether the controls are showing or not, since its meaning is unambiguous. These
 *    are also what makes seeking without the controls still possible.
 *  - **Locked ignores everything except unlock.** Matching the touch behaviour, so a
 *    child pressing buttons cannot change what is playing.
 */
fun mapPlayerKey(
    keyCode: Int,
    controlsVisible: Boolean,
    panelOpen: Boolean,
    isLocked: Boolean,
): PlayerKeyAction {
    // A panel takes precedence: while it is open the D-pad belongs to its list, and
    // only Back closes it.
    if (panelOpen) {
        return when (keyCode) {
            KEY_BACK, KEY_ESCAPE -> PlayerKeyAction.DISMISS
            else -> PlayerKeyAction.NONE
        }
    }

    if (isLocked) {
        // Any press reveals the locked notice; nothing else responds.
        return when (keyCode) {
            KEY_BACK, KEY_ESCAPE -> PlayerKeyAction.DISMISS
            else -> PlayerKeyAction.SHOW_CONTROLS
        }
    }

    // Media keys are unconditional: a remote's transport buttons mean one thing.
    when (keyCode) {
        KEY_MEDIA_PLAY -> return PlayerKeyAction.PLAY
        KEY_MEDIA_PAUSE -> return PlayerKeyAction.PAUSE
        KEY_MEDIA_PLAY_PAUSE, KEY_SPACE -> return PlayerKeyAction.TOGGLE_PLAY
        KEY_MEDIA_REWIND -> return PlayerKeyAction.SEEK_BACK
        KEY_MEDIA_FAST_FORWARD -> return PlayerKeyAction.SEEK_FORWARD
        KEY_MEDIA_NEXT -> return PlayerKeyAction.NEXT_EPISODE
        KEY_MEDIA_PREVIOUS -> return PlayerKeyAction.PREVIOUS_EPISODE
        KEY_BACK, KEY_ESCAPE -> return PlayerKeyAction.DISMISS
    }

    // With the controls hidden, left and right seek directly.
    //
    // They used to reveal the controls instead, on the reasoning that a blind seek is
    // worse than a visible one. In practice it made a ten-second skip a two-press
    // operation - reveal, then seek - and the second press then belonged to the focus
    // system rather than the transport, so it did nothing.
    //
    // The seek is not blind either: it draws the same readout the double-tap gesture uses,
    // which sits outside the controls layer precisely so it works while they are hidden.
    //
    // Up, down and centre still reveal: they have no transport meaning of their own, and
    // revealing is the only useful thing they can do from here.
    if (!controlsVisible) {
        return when (keyCode) {
            KEY_DPAD_LEFT -> PlayerKeyAction.SEEK_BACK
            KEY_DPAD_RIGHT -> PlayerKeyAction.SEEK_FORWARD

            KEY_DPAD_UP, KEY_DPAD_DOWN, KEY_DPAD_CENTER, KEY_ENTER,
            -> PlayerKeyAction.SHOW_CONTROLS

            else -> PlayerKeyAction.NONE
        }
    }

    // Controls are on screen, so the D-pad belongs to them.
    //
    // These used to seek and toggle playback here as well, which consumed every
    // directional press. Compose moves focus off unconsumed key events, so nothing
    // could ever move: focus stayed on the surface, no button highlighted, and the
    // buttons were unreachable by remote even though they were focusable.
    //
    // Returning NONE lets the press fall through to focus navigation, which is what
    // makes the visible buttons usable. The blind-seek case the old mapping served is
    // still covered by the branch above - while the controls are hidden - and by the
    // dedicated transport keys, which remain unconditional.
    return PlayerKeyAction.NONE
}

/**
 * How far a single directional press seeks.
 *
 * Ten seconds matches the double-tap gesture on touch, so the two input methods agree
 * about what a "skip" is.
 */
const val PLAYER_KEY_SEEK_MS = 10_000L

// Key codes, mirrored from android.view.KeyEvent so this file needs no Android
// dependency and can be tested on the JVM.
const val KEY_DPAD_UP = 19
const val KEY_DPAD_DOWN = 20
const val KEY_DPAD_LEFT = 21
const val KEY_DPAD_RIGHT = 22
const val KEY_DPAD_CENTER = 23
const val KEY_BACK = 4
const val KEY_ENTER = 66
const val KEY_SPACE = 62
const val KEY_ESCAPE = 111
const val KEY_MEDIA_PLAY_PAUSE = 85
const val KEY_MEDIA_REWIND = 89
const val KEY_MEDIA_FAST_FORWARD = 90
const val KEY_MEDIA_NEXT = 87
const val KEY_MEDIA_PREVIOUS = 88
const val KEY_MEDIA_PLAY = 126
const val KEY_MEDIA_PAUSE = 127
