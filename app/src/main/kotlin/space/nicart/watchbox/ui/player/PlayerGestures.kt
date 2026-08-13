package space.nicart.watchbox.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Screen brightness for the player only.
 *
 * Deliberately window-local rather than a change to the system setting: a video player dimming
 * the whole device permanently would be hostile, and writing system brightness needs
 * WRITE_SETTINGS.
 *
 * The override must be released explicitly - see [release]. Android only restores the system
 * value when the *window* is destroyed, and this app has one Activity for every screen, so
 * leaving the player does not destroy anything. A brightness set here therefore outlived the
 * player and applied to the whole app, with no gesture anywhere else to undo it.
 */
class BrightnessController(private val activity: Activity?) {

    /**
     * Current level as 0..1.
     *
     * A window that has never set brightness reports
     * [WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE] (-1), which is "follow
     * the system". There is no reliable way to read the resolved system value
     * without WRITE_SETTINGS, so the first gesture starts from a mid-point rather
     * than jumping to an extreme.
     */
    var level: Float = INITIAL_UNKNOWN
        private set

    fun apply(target: Float) {
        val activity = activity ?: return
        // Never fully black: a 0 brightness screen looks like a crash.
        val clamped = target.coerceIn(MIN_BRIGHTNESS, 1f)

        activity.window?.let { window ->
            window.attributes = window.attributes.apply { screenBrightness = clamped }
        }
        level = clamped
    }

    /** Resolves the starting point for a gesture. */
    fun currentOrDefault(): Float =
        if (level == INITIAL_UNKNOWN) DEFAULT_START else level

    /**
     * Hands brightness back to the system.
     *
     * BRIGHTNESS_OVERRIDE_NONE is the documented "follow the system" value, so this restores the
     * device's own brightness - including automatic brightness - rather than guessing at a level
     * to set. Safe to call when nothing was ever changed.
     */
    fun release() {
        val activity = activity ?: return

        activity.window?.let { window ->
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
        level = INITIAL_UNKNOWN
    }

    private companion object {
        const val INITIAL_UNKNOWN = -1f
        const val DEFAULT_START = 0.5f
        // 0.01 rather than 0: the panel stays lit, just very dim.
        const val MIN_BRIGHTNESS = 0.01f
    }
}

/**
 * Media volume, tracked as a fraction.
 *
 * [AudioManager] volume is a small integer range — commonly 0..15 — so driving it
 * straight from a drag makes the gesture feel like it snaps. A float is kept here
 * and rounded only when writing, so a slow drag moves smoothly and the reported
 * percentage matches what the user sees.
 */
class VolumeController(context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val maxVolume: Int =
        audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1

    var level: Float = readSystemLevel()
        private set

    fun apply(target: Float) {
        val clamped = target.coerceIn(0f, 1f)
        level = clamped

        runCatching {
            audioManager?.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                (clamped * maxVolume).roundToInt().coerceIn(0, maxVolume),
                0,
            )
        }
    }

    /**
     * Re-reads the system value.
     *
     * Called when a gesture begins so the hardware volume keys and this control
     * cannot drift apart.
     */
    fun sync() {
        level = readSystemLevel()
    }

    private fun readSystemLevel(): Float = runCatching {
        val current = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        current.toFloat() / maxVolume
    }.getOrDefault(0.5f)
}

/** Which vertical gesture is in progress, for the on-screen indicator. */
enum class VerticalGesture { NONE, BRIGHTNESS, VOLUME }

/**
 * Converts a vertical drag into a level change.
 *
 * Dragging up increases, so the sign is inverted against screen coordinates.
 * A full-height swipe deliberately covers more than the whole range
 * ([SWIPE_FRACTION] < 1) because needing the entire screen height to go from mute
 * to full is tiring; two thirds is the range most players settle on.
 */
fun verticalDragToDelta(dragPx: Float, heightPx: Int): Float {
    if (heightPx <= 0) return 0f
    return -dragPx / (heightPx * SWIPE_FRACTION)
}

private const val SWIPE_FRACTION = 0.7f

/**
 * Whether a gesture starting at [y] is too close to a screen edge to be ours.
 *
 * The top edge is where the notification shade is pulled from and the bottom is
 * the navigation-gesture area. Claiming drags that start there means the user
 * fights the player to reach system UI, so those are left alone.
 */
fun isInSystemEdgeZone(y: Float, heightPx: Int, edgePx: Float): Boolean {
    if (heightPx <= 0) return false
    // Capped so the exclusion never eats a meaningful share of a short screen.
    val edge = edgePx.coerceAtMost(heightPx * MAX_EDGE_FRACTION)
    return y < edge || y > heightPx - edge
}

private const val MAX_EDGE_FRACTION = 0.2f

/**
 * Decides whether a drag is vertical, given the first movement past touch slop.
 *
 * A ratio rather than a simple comparison: diagonal drags are ambiguous, and
 * requiring the vertical component to clearly dominate stops a slightly-slanted
 * seek from being read as a volume change.
 */
fun isVerticalDrag(dx: Float, dy: Float): Boolean =
    abs(dy) > abs(dx) * AXIS_DOMINANCE

private const val AXIS_DOMINANCE = 1.2f
