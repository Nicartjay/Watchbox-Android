package space.nicart.watchbox.core.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens.
 *
 * Ported 1:1 from NuvioMobile `core/ui/Tokens.kt`. Raw scales live in the
 * [WbTokens] object; resolved, theme-aware values live in [WbThemeTokens] and
 * are reached with `MaterialTheme.wb`.
 */
object WbTokens {

    /** Spacing scale (`Tokens.kt:19-48`). */
    object Space {
        val none = 0.dp
        val hairline = 0.5.dp
        val s1 = 1.dp
        val s2 = 2.dp
        val s3 = 3.dp
        val s4 = 4.dp
        val s5 = 5.dp
        val s6 = 6.dp
        val s7 = 7.dp
        val s8 = 8.dp
        val s10 = 10.dp
        val s12 = 12.dp
        val s14 = 14.dp
        val s16 = 16.dp
        val s18 = 18.dp
        val s20 = 20.dp
        val s22 = 22.dp
        val s24 = 24.dp
        val s28 = 28.dp
        val s32 = 32.dp
        val s36 = 36.dp
        val s40 = 40.dp
        val s48 = 48.dp
        val s56 = 56.dp
        val s64 = 64.dp
        val s72 = 72.dp
        val s80 = 80.dp
        val s96 = 96.dp
    }

    /** Corner radii (`Tokens.kt:50-69`). */
    object Radius {
        val none = 0.dp
        val xs = 4.dp
        val sm = 6.dp
        val md = 8.dp
        val lg = 12.dp
        val xl = 16.dp
        val xxl = 24.dp
        val full = 999.dp

        val card = xxl
        val compactCard = lg
        val sheet = xxl
        val dialog = xxl
        val button = xl
        val chip = full
        val poster = lg
        val avatar = full
        val playerPanel = xxl
    }

    /** Border widths (`Tokens.kt:71-75`). */
    object Border {
        val hairline = 0.5.dp
        val thin = 1.dp
        val medium = 2.dp
    }

    /** Elevation (`Tokens.kt:77-83`). */
    object Elevation {
        val none = 0.dp
        val raised = 2.dp
        val modal = 8.dp
        val overlay = 12.dp
        val playerControls = 4.dp
    }

    /** Alpha values (`Tokens.kt:85-100`). */
    object Opacity {
        const val INVISIBLE = 0f
        const val DISABLED = 0.38f
        const val SECONDARY = 0.70f
        const val MUTED = 0.60f
        const val SELECTED = 0.15f
        const val HOVER = 0.08f
        const val PRESSED = 0.12f
        const val SUBTLE = 0.06f
        const val MEDIUM = 0.52f
        const val STRONG = 0.75f
        const val OVERLAY_LIGHT = 0.35f
        const val OVERLAY_MEDIUM = 0.56f
        const val OVERLAY_HEAVY = 0.82f
        const val VISIBLE = 1f
    }

    /** Motion durations + easings (`Tokens.kt:102-115`). */
    object Motion {
        const val INSTANT = 0
        const val FAST = 150
        const val NORMAL = 220
        const val SHEET_ENTER = 300
        const val SHEET_EXIT = 250
        const val SLOW = 400
        const val CINEMATIC = 700

        val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val decelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
        val accelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    }

    /** Icon sizes (`Tokens.kt:117-124`). */
    object Icon {
        val xs = 12.dp
        val sm = 16.dp
        val md = 20.dp
        val lg = 24.dp
        val xl = 32.dp
        val xxl = 40.dp
    }

    /** Raw type scale (`Tokens.kt:126-164`). */
    object Type {
        val labelXs = 11.sp
        val labelXsLine = 14.sp
        val labelSm = 12.sp
        val labelSmLine = 15.sp
        val bodySm = 13.sp
        val bodySmLine = 18.sp
        val bodyMd = 14.sp
        val bodyMdLine = 20.sp
        val bodyApp = 15.sp
        val bodyAppLine = 22.sp
        val bodyLg = 16.sp
        val bodyLgLine = 22.sp
        val titleSm = 18.sp
        val titleSmLine = 22.sp
        val titleMd = 22.sp
        val titleMdLine = 26.sp
        val headline = 26.sp
        val headlineLine = 30.sp
        val titleLg = 28.sp
        val titleLgLine = 32.sp
        val displaySm = 32.sp
        val displaySmLine = 36.sp
        val pageDisplay = 38.sp
        val pageDisplayLine = 42.sp
        val displayMd = 48.sp
        val displayMdLine = 52.sp
        val materialTitleLargeLine = 24.sp

        val trackingNone = 0.sp
        val trackingPageDisplay = (-1.2).sp
        val trackingHeadline = (-0.8).sp
        val trackingLabel = 0.8.sp
    }

    /** Width breakpoints in dp (`Tokens.kt:166-173`). */
    object Breakpoint {
        val phone = 0.dp
        val largePhone = 420.dp
        val tablet = 600.dp
        val largeTablet = 840.dp
        val desktop = 1024.dp
        val playerWide = 1280.dp
    }

    /** Stacking order (`Tokens.kt:175-183`). */
    object Z {
        const val BASE = 0f
        const val STICKY_HEADER = 2f
        const val NAVIGATION = 4f
        const val SHEET = 8f
        const val DIALOG = 10f
        const val PLAYER_OVERLAY = 12f
        const val TOAST = 16f
    }
}

/** Resolved semantic colours (`Tokens.kt:373-430`). */
@Immutable
data class WbColorTokens(
    val background: Color,
    val backgroundAmoled: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceCard: Color,
    val surfaceSheet: Color,
    val surfaceDialog: Color,
    val surfacePopover: Color,
    val nativeChrome: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textDisabled: Color,
    val textInverse: Color,
    val accent: Color,
    val accentStrong: Color,
    val onAccent: Color,
    val focusRing: Color,
    val focusBackground: Color,
    val borderSubtle: Color,
    val borderDefault: Color,
    val borderStrong: Color,
    val borderFocus: Color,
    val borderSelected: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val neutral: Color,
    val overlayScrim: Color,
    val overlayHover: Color,
    val overlayPressed: Color,
    val overlaySelected: Color,
    val overlayDisabled: Color,
    val shimmer: Color,
    val skeleton: Color,
    val playerControlsBackground: Color,
    val playerControlsForeground: Color,
    val playerTimelineTrack: Color,
    val playerTimelineFill: Color,
    val playerSubtitlePreview: Color,
    val playerBuffering: Color,
    val parentalGuide: Color,
)

/** Semantic spacing (`Tokens.kt:431-444`). */
@Immutable
data class WbSpacingTokens(
    val screenHorizontal: androidx.compose.ui.unit.Dp = 16.dp,
    val screenTop: androidx.compose.ui.unit.Dp = 10.dp,
    val screenBottom: androidx.compose.ui.unit.Dp = 18.dp,
    val sectionGap: androidx.compose.ui.unit.Dp = 24.dp,
    val listGap: androidx.compose.ui.unit.Dp = 12.dp,
    val railGap: androidx.compose.ui.unit.Dp = 14.dp,
    val cardPadding: androidx.compose.ui.unit.Dp = 18.dp,
    val cardPaddingCompact: androidx.compose.ui.unit.Dp = 14.dp,
    val controlGap: androidx.compose.ui.unit.Dp = 8.dp,
    val dialogPadding: androidx.compose.ui.unit.Dp = 20.dp,
    val sheetPadding: androidx.compose.ui.unit.Dp = 20.dp,
    val playerOverlayPadding: androidx.compose.ui.unit.Dp = 24.dp,
)

/** Shapes derived from [WbTokens.Radius] (`Tokens.kt:445-455`). */
@Immutable
data class WbShapeTokens(
    val card: RoundedCornerShape = RoundedCornerShape(WbTokens.Radius.card),
    val compactCard: RoundedCornerShape = RoundedCornerShape(WbTokens.Radius.compactCard),
    val sheet: RoundedCornerShape = RoundedCornerShape(WbTokens.Radius.sheet),
    val dialog: RoundedCornerShape = RoundedCornerShape(WbTokens.Radius.dialog),
    val button: RoundedCornerShape = RoundedCornerShape(WbTokens.Radius.button),
    val chip: RoundedCornerShape = RoundedCornerShape(WbTokens.Radius.chip),
    val poster: RoundedCornerShape = RoundedCornerShape(WbTokens.Radius.poster),
    val avatar: RoundedCornerShape = RoundedCornerShape(WbTokens.Radius.avatar),
    val playerPanel: RoundedCornerShape = RoundedCornerShape(WbTokens.Radius.playerPanel),
)

/** Per-component constants (`Tokens.kt:513-524`). */
@Immutable
data class WbComponentTokens(
    val navItemShape: RoundedCornerShape = RoundedCornerShape(16.dp),
    val navIconSize: androidx.compose.ui.unit.Dp = 32.dp,
    val navItemMaxWidth: androidx.compose.ui.unit.Dp = 150.dp,
    val sheetMaxWidth: androidx.compose.ui.unit.Dp = 520.dp,
    val dialogMaxWidth: androidx.compose.ui.unit.Dp = 460.dp,
    val chipHorizontalPadding: androidx.compose.ui.unit.Dp = 14.dp,
    val chipVerticalPadding: androidx.compose.ui.unit.Dp = 8.dp,
    val posterRadius: androidx.compose.ui.unit.Dp = 12.dp,
    val avatarSize: androidx.compose.ui.unit.Dp = 48.dp,
    val playerPanelMaxWidth: androidx.compose.ui.unit.Dp = 600.dp,
)

@Immutable
data class WbThemeTokens(
    val colors: WbColorTokens,
    val spacing: WbSpacingTokens = WbSpacingTokens(),
    val shapes: WbShapeTokens = WbShapeTokens(),
    val components: WbComponentTokens = WbComponentTokens(),
)

/**
 * Build the resolved token set for [theme].
 * Mirrors Nuvio's `defaultNuvioThemeTokens()`; note that `textPrimary`,
 * `textSecondary` and `textMuted` are hardcoded upstream rather than
 * palette-derived, so they stay constant across accents.
 */
fun wbThemeTokens(theme: AppTheme, amoled: Boolean): WbThemeTokens {
    val p = paletteFor(theme)
    val background = if (amoled) Color.Black else Color(p.background)
    val surface = Color(p.backgroundElevated)
    val card = Color(p.backgroundCard)
    val accent = Color(p.secondary)
    val textMuted = Color(0xFF969CA3)

    return WbThemeTokens(
        colors = WbColorTokens(
            background = background,
            backgroundAmoled = Color.Black,
            surface = surface,
            surfaceElevated = surface,
            surfaceCard = card,
            surfaceSheet = surface,
            surfaceDialog = surface,
            surfacePopover = card,
            nativeChrome = background,
            textPrimary = Color(0xFFF5F7F8),
            textSecondary = Color(0xFFB8BEC5),
            textMuted = textMuted,
            textDisabled = textMuted.copy(alpha = WbTokens.Opacity.DISABLED),
            textInverse = Color(0xFF111111),
            accent = accent,
            accentStrong = Color(p.secondaryVariant),
            onAccent = Color(p.onSecondary),
            focusRing = Color(p.focusRing),
            focusBackground = Color(p.focusBackground),
            borderSubtle = Color(0xFF252A2A).copy(alpha = 0.55f),
            borderDefault = Color(0xFF252A2A),
            borderStrong = Color(0xFF3A4040),
            borderFocus = Color(p.focusRing),
            borderSelected = accent.copy(alpha = WbTokens.Opacity.STRONG),
            success = Color(0xFF66BB6A),
            warning = Color(0xFFFFC857),
            danger = Color(0xFFE36A8A),
            info = Color(0xFF42A5F5),
            neutral = textMuted,
            overlayScrim = Color.Black.copy(alpha = WbTokens.Opacity.OVERLAY_MEDIUM),
            overlayHover = Color.White.copy(alpha = WbTokens.Opacity.HOVER),
            overlayPressed = Color.White.copy(alpha = WbTokens.Opacity.PRESSED),
            overlaySelected = Color.White.copy(alpha = WbTokens.Opacity.SELECTED),
            overlayDisabled = Color.Black.copy(alpha = WbTokens.Opacity.DISABLED),
            shimmer = Color.White.copy(alpha = 0.10f),
            skeleton = Color.White.copy(alpha = WbTokens.Opacity.SUBTLE),
            playerControlsBackground = Color.Black.copy(alpha = 0.72f),
            playerControlsForeground = Color.White,
            playerTimelineTrack = Color.White.copy(alpha = 0.30f),
            playerTimelineFill = accent,
            playerSubtitlePreview = Color.Black.copy(alpha = 0.55f),
            playerBuffering = accent,
            parentalGuide = Color(0xFF5D1F1F),
        ),
    )
}

internal val LocalWbTokens = compositionLocalOf {
    wbThemeTokens(AppTheme.Default, amoled = false)
}
