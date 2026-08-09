package space.nicart.watchbox.ui.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import space.nicart.watchbox.data.local.AppSettings

/**
 * How subtitle text is separated from the picture behind it.
 *
 * Subtitles have to stay readable over arbitrary video, and the right answer is
 * content-dependent: an outline survives busy scenes, a shadow is lighter over
 * calm ones, and a solid box is the only thing that reliably works over
 * bright or high-contrast footage. Offering one would leave some content unreadable.
 */
enum class SubtitleBackground(val label: String) {
    /** Text only. Cleanest, but can disappear over pale scenes. */
    NONE("None"),

    /** Dark rim on each glyph. Works over almost anything. */
    OUTLINE("Outline"),

    /** Offset shadow. Lighter than an outline, less robust over detail. */
    DROP_SHADOW("Drop shadow"),

    /** Filled box behind the text only. */
    BACKGROUND("Solid background"),

    /** Filled band across the full subtitle width. */
    FULL_BACKGROUND("Full-width band"),
}

/**
 * Text size as a fraction of view height.
 *
 * Fractional rather than absolute so a size chosen on a phone still looks right
 * cast to a television, and so it scales with the video surface instead of the
 * screen.
 */
enum class SubtitleSize(val label: String, val fraction: Float) {
    SMALL("Small", 0.0400f),

    /** Media3's own default. */
    MEDIUM("Medium", 0.0533f),
    LARGE("Large", 0.0700f),
    EXTRA_LARGE("Extra large", 0.0900f),
}

/**
 * How heavy the outline or drop shadow is.
 *
 * Media3's own renderer offers no control here - `SubtitlePainter` hardcodes the
 * outline to 2dp and `CaptionStyleCompat` exposes no width - which is why subtitles
 * are drawn by [ComposeSubtitleView] instead.
 *
 * Width matters more than it sounds: a thin outline vanishes against detailed video
 * at a distance, and a heavy one closes up the counters of small text. The right
 * value depends on text size and viewing distance, so it has to be a choice.
 */
enum class SubtitleEdgeWidth(
    val label: String,
    /** Outline stroke half-width in dp. */
    val outlineDp: Float,
    /** Drop-shadow offset in dp. */
    val shadowDp: Float,
    /** Drop-shadow blur in dp. */
    val shadowBlurDp: Float,
) {
    THIN("Thin", outlineDp = 0.75f, shadowDp = 1f, shadowBlurDp = 1.5f),

    /** Approximates Media3's fixed 2dp outline. */
    MEDIUM("Medium", outlineDp = 1.5f, shadowDp = 2f, shadowBlurDp = 3f),
    THICK("Thick", outlineDp = 2.5f, shadowDp = 3f, shadowBlurDp = 4f),
    HEAVY("Heavy", outlineDp = 4f, shadowDp = 4f, shadowBlurDp = 6f),
}

/**
 * Builds the Media3 caption style for the current settings.
 *
 * `windowColor` is what distinguishes a box behind the text from a band across the
 * whole line: Media3 paints the background per-cue and the window across the
 * subtitle's full width, so a full-width band needs the window rather than a
 * wider background.
 *
 * Edge and background are mutually exclusive by construction - an outline drawn
 * over a solid box is invisible, so combining them only wastes a setting.
 */
@UnstableApi
fun subtitleCaptionStyle(
    background: SubtitleBackground,
    textColor: Int,
    opacity: Float,
): CaptionStyleCompat {
    val backgroundColor = TRANSPARENT.withAlpha(0f)

    return when (background) {
        SubtitleBackground.NONE -> CaptionStyleCompat(
            textColor,
            backgroundColor,
            TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_NONE,
            EDGE_COLOR,
            null,
        )

        SubtitleBackground.OUTLINE -> CaptionStyleCompat(
            textColor,
            backgroundColor,
            TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            EDGE_COLOR,
            null,
        )

        SubtitleBackground.DROP_SHADOW -> CaptionStyleCompat(
            textColor,
            backgroundColor,
            TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
            EDGE_COLOR,
            null,
        )

        SubtitleBackground.BACKGROUND -> CaptionStyleCompat(
            textColor,
            EDGE_COLOR.withAlpha(opacity),
            TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_NONE,
            EDGE_COLOR,
            null,
        )

        // The band is the window, not a wider background.
        SubtitleBackground.FULL_BACKGROUND -> CaptionStyleCompat(
            textColor,
            backgroundColor,
            EDGE_COLOR.withAlpha(opacity),
            CaptionStyleCompat.EDGE_TYPE_NONE,
            EDGE_COLOR,
            null,
        )
    }
}

/** Replaces a colour's alpha channel with [opacity] (0..1). */
internal fun Int.withAlpha(opacity: Float): Int {
    val alpha = (opacity.coerceIn(0f, 1f) * 255).toInt() and 0xFF
    return (alpha shl 24) or (this and 0x00FFFFFF)
}

private const val TRANSPARENT = 0x00000000
private const val EDGE_COLOR = 0xFF000000.toInt()

/**
 * The subtitle appearance derived from settings.
 *
 * Grouped so the player can react to a single value: styling is applied through an
 * imperative Media3 call, and keying an effect on one object avoids re-applying it
 * for every unrelated settings change.
 */
data class SubtitleStyle(
    val size: SubtitleSize,
    val background: SubtitleBackground,
    val textColor: Int,
    val backgroundOpacity: Float,
    val bold: Boolean,
    val edgeWidth: SubtitleEdgeWidth = SubtitleEdgeWidth.MEDIUM,
) {
    /** True when [edgeWidth] affects anything. */
    val usesEdge: Boolean
        get() = background == SubtitleBackground.OUTLINE ||
            background == SubtitleBackground.DROP_SHADOW
}

/** Reads the subtitle appearance out of persisted settings. */
fun AppSettings.subtitleStyle(): SubtitleStyle = SubtitleStyle(
    size = subtitleSize,
    background = subtitleBackground,
    textColor = subtitleTextColor,
    backgroundOpacity = subtitleBackgroundOpacity,
    bold = subtitleBold,
    edgeWidth = subtitleEdgeWidth,
)

/** Preset text colours. White is first because it is what nearly everyone wants. */
val SUBTITLE_TEXT_COLORS: List<Pair<String, Int>> = listOf(
    "White" to 0xFFFFFFFF.toInt(),
    "Yellow" to 0xFFFFEB3B.toInt(),
    "Cyan" to 0xFF4DD0E1.toInt(),
    "Green" to 0xFF81C784.toInt(),
    "Grey" to 0xFFBDBDBD.toInt(),
)
