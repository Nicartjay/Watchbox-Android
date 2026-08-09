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
 *  - **Directional keys reveal the controls before they act.** On a TV the controls
 *    are usually hidden, and a blind seek gives no feedback about where you landed.
 *    The first press surfaces the scrubber; the next one seeks.
 *  - **Dedicated media keys always act.** A remote's play/pause button should work
 *    whether the controls are showing or not, since its meaning is unambiguous.
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

    // Directional and centre keys reveal the controls first, so a seek is never blind.
    if (!controlsVisible) {
        return when (keyCode) {
            KEY_DPAD_LEFT, KEY_DPAD_RIGHT, KEY_DPAD_UP, KEY_DPAD_DOWN,
            KEY_DPAD_CENTER, KEY_ENTER,
            -> PlayerKeyAction.SHOW_CONTROLS

            else -> PlayerKeyAction.NONE
        }
    }

    return when (keyCode) {
        KEY_DPAD_LEFT -> PlayerKeyAction.SEEK_BACK
        KEY_DPAD_RIGHT -> PlayerKeyAction.SEEK_FORWARD
        KEY_DPAD_CENTER, KEY_ENTER -> PlayerKeyAction.TOGGLE_PLAY
        else -> PlayerKeyAction.NONE
    }
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
