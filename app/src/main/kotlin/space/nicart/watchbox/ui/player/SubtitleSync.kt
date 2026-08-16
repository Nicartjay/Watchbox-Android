package space.nicart.watchbox.ui.player

/**
 * Measures a subtitle timing offset from two taps.
 *
 * The user marks the same moment twice: once when the subtitle appears, once when the
 * line is actually spoken. The gap between those two positions is the correction.
 *
 * Either order works, because which one comes first is itself the diagnosis - subtitles
 * running early versus late - and demanding a fixed order would make the feature
 * unusable in exactly the case it is for.
 */
enum class SyncMark {
    /** The moment the subtitle appeared on screen. */
    SUBTITLE,

    /** The moment the line was spoken. */
    SPOKEN,
}

/**
 * An in-progress two-tap measurement.
 *
 * [firstMark] is null when nothing has been captured yet. Once one mark is taken, only
 * the *other* button remains available: tapping the same one twice would measure the gap
 * between two attempts at the same event, which is noise.
 */
data class SyncCalibration(
    val firstMark: SyncMark? = null,
    val firstPositionMs: Long = 0L,
) {
    /** True once a mark has been taken and the app is waiting for its counterpart. */
    val isArmed: Boolean get() = firstMark != null

    /** Whether [mark]'s button should be tappable. */
    fun isEnabled(mark: SyncMark): Boolean = firstMark == null || firstMark != mark
}

/**
 * The offset implied by marking [second] at [secondPositionMs].
 *
 * Returns null when no first mark has been taken, or when both taps named the same
 * event - neither is a measurement.
 *
 * The sign follows [subtitleOffsetMs]: positive delays the subtitles. If the subtitle
 * appeared at 10s and the line was spoken at 12s, the subtitles are 2s early and need
 * +2s. Marking them in the opposite order yields the same +2s, because the arithmetic is
 * always spoken-minus-subtitle rather than second-minus-first.
 */
fun SyncCalibration.resolve(second: SyncMark, secondPositionMs: Long): Long? {
    val first = firstMark ?: return null
    if (first == second) return null

    val subtitleAt = if (first == SyncMark.SUBTITLE) firstPositionMs else secondPositionMs
    val spokenAt = if (first == SyncMark.SPOKEN) firstPositionMs else secondPositionMs

    return spokenAt - subtitleAt
}

/**
 * Clamps an offset to the supported range.
 *
 * Ten seconds was too tight. A subtitle matched to a different release can be out by far
 * more than that - a version with an extra recap, a different intro, or one timed to a
 * broadcast cut rather than a stream - and the earlier ceiling silently refused corrections
 * that were entirely legitimate, which read as the feature not working.
 *
 * Still bounded rather than free. The bound is a sanity limit, not a judgement about
 * plausible desync: an offset larger than the episode itself moves every cue outside the
 * runtime, and with no subtitles left on screen there is no feedback to correct it by. Ten
 * minutes is beyond any real mismatch while staying recoverable.
 */
fun clampSubtitleOffset(offsetMs: Long): Long =
    offsetMs.coerceIn(-SUBTITLE_OFFSET_LIMIT_MS, SUBTITLE_OFFSET_LIMIT_MS)

/** Largest correction offered, in either direction. */
const val SUBTITLE_OFFSET_LIMIT_MS = 600_000L

/** One nudge of the manual stepper. */
const val SUBTITLE_OFFSET_STEP_MS = 250L

/**
 * Formats an offset for display, always signed.
 *
 * The sign is the informative half - "0.25s" alone does not say which way - so it is
 * kept even at zero, where it reads as "no correction".
 */
fun formatSubtitleOffset(offsetMs: Long): String {
    if (offsetMs == 0L) return "0.00s"
    val sign = if (offsetMs > 0) "+" else "-"
    val seconds = kotlin.math.abs(offsetMs) / 1000.0
    return "%s%.2fs".format(sign, seconds)
}
