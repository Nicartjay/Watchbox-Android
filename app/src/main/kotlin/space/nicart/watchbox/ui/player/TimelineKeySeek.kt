package space.nicart.watchbox.ui.player

import kotlin.math.ln
import kotlin.math.pow

/** A tap stays precise; holding the focused TV timeline accelerates in either direction. */
internal const val SLIDER_SEEK_STEP_MS = 15_000L

/**
 * Integrate a seek rate that doubles each second, capped at two video minutes per real second.
 * Using elapsed time, not repeat count, gives fast and slow remotes the same travel distance.
 */
internal fun timelineHoldDistanceMs(heldMs: Long): Long {
    val seconds = heldMs.coerceAtLeast(0L) / 1_000.0
    val acceleratingSeconds = seconds.coerceAtMost(3.0)
    val accelerated = SLIDER_SEEK_STEP_MS * (2.0.pow(acceleratingSeconds) - 1.0) / ln(2.0)
    val capped = (seconds - acceleratingSeconds) * 120_000.0
    return (SLIDER_SEEK_STEP_MS + accelerated + capped).toLong()
}

/** Keeps repeated keys independent of the player's slower position ticker. */
internal class TimelineKeySeek {
    private var heldKey: Int? = null
    private var startedAtMs = 0L
    private var startPositionMs = 0L
    private var targetPositionMs: Long? = null

    fun keyDown(
        keyCode: Int,
        repeatCount: Int,
        eventTimeMs: Long,
        positionMs: Long,
        durationMs: Long,
    ): Long? {
        if (keyCode != KEY_DPAD_LEFT && keyCode != KEY_DPAD_RIGHT || durationMs <= 0L) {
            reset()
            return null
        }

        if (repeatCount == 0 || heldKey != keyCode) {
            startPositionMs = (targetPositionMs ?: positionMs).coerceIn(0L, durationMs)
            startedAtMs = eventTimeMs
            heldKey = keyCode
        }
        val distance = timelineHoldDistanceMs((eventTimeMs - startedAtMs).coerceAtLeast(0L))
        val start = startPositionMs.coerceIn(0L, durationMs)
        return if (keyCode == KEY_DPAD_LEFT) {
            start - distance.coerceAtMost(start)
        } else {
            start + distance.coerceAtMost(durationMs - start)
        }.also { targetPositionMs = it }
    }

    fun keyUp(keyCode: Int) {
        if (heldKey == keyCode) reset()
    }

    fun reset() {
        heldKey = null
        targetPositionMs = null
    }
}
