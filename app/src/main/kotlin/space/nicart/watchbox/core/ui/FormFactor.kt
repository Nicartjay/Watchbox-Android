package space.nicart.watchbox.core.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.BuildConfig

/**
 * Which shape of device the UI is running on.
 *
 * Drives layout decisions that width alone cannot answer. A 1080p television and a
 * 1080p tablet report almost identical widths in dp, yet they need opposite
 * treatments: the TV is viewed from three metres with a D-pad and needs *fewer*,
 * larger targets, while the tablet is held at arm's length with a finger and can
 * take more, smaller ones. Sizing off width alone gets the TV wrong every time.
 */
enum class FormFactor {
    /** Phone, and small tablets in portrait. */
    COMPACT,

    /** Tablets and foldables opened out. */
    TABLET,

    /** Leanback: viewed at distance, driven by a remote. */
    TV,
}

/**
 * Detects the form factor.
 *
 * The TV flavor is trusted first, because it is the only signal that is certain: it
 * is a build-time fact rather than a runtime guess. The runtime checks still run so
 * the mobile APK behaves sensibly if someone sideloads it onto a TV box - which
 * people do.
 *
 * Three runtime signals are used, because none is reliable alone:
 *  - `UiModeManager` is authoritative when it reports television, but some cheap
 *    boxes report `UI_MODE_TYPE_NORMAL`.
 *  - `FEATURE_LEANBACK` catches most of those boxes.
 *  - Absence of a touchscreen catches the rest, and is what actually matters for
 *    the UI: no touch means everything has to be reachable by D-pad.
 */
fun detectFormFactor(context: Context, widthDp: Int): FormFactor {
    if (BuildConfig.FORM_FACTOR == "tv") return FormFactor.TV
    if (context.looksLikeTelevision()) return FormFactor.TV

    return if (widthDp >= TABLET_WIDTH_DP) FormFactor.TABLET else FormFactor.COMPACT
}

private fun Context.looksLikeTelevision(): Boolean {
    val uiMode = (getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
        ?.currentModeType
    if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) return true

    val leanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    val noTouch = !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

    return leanback || noTouch
}

/**
 * Width at which a tablet layout is used.
 *
 * 600dp is Android's own smallest-width tablet threshold, but layout here switches
 * at 840 - the Material window-size-class "expanded" boundary - because between the
 * two there is enough room for wider content yet not enough for a persistent nav
 * rail beside it without cramping.
 */
private const val TABLET_WIDTH_DP = 840

/**
 * Layout metrics resolved from the form factor.
 *
 * Grouped into one object so a screen reads its numbers from a single source rather
 * than repeating the same `when (formFactor)` in eight places, which is how the
 * breakpoints drift apart.
 */
data class LayoutMetrics(
    val formFactor: FormFactor,
    /** Padding at the screen edge. Large on TV to clear overscan. */
    val screenPadding: Dp,
    /** Poster width in a horizontal rail. */
    val posterWidth: Dp,
    /** Columns in a content grid. */
    val gridColumns: Int,
    /** Whether navigation is a side rail rather than a bottom bar. */
    val usesNavRail: Boolean,
    /** Whether detail shows a two-pane layout. */
    val usesTwoPaneDetail: Boolean,
    /** Whether the UI must be fully operable without touch. */
    val isFocusDriven: Boolean,
) {
    val isTv: Boolean get() = formFactor == FormFactor.TV
    val isTablet: Boolean get() = formFactor == FormFactor.TABLET
}

/**
 * Builds metrics for a form factor and width.
 *
 * TV numbers are deliberately not a continuation of the phone/tablet scale:
 *
 *  - **Padding is 48dp.** Televisions overscan, cropping up to about 5% of each
 *    edge, so content flush to the edge can be physically cut off. 48dp is the
 *    figure Android's own leanback guidance uses.
 *  - **Posters are larger and fewer.** Read from three metres, a phone-sized poster
 *    is unreadable, and a D-pad makes a long row tedious to traverse.
 */
fun layoutMetricsFor(formFactor: FormFactor, widthDp: Int): LayoutMetrics = when (formFactor) {
    FormFactor.TV -> LayoutMetrics(
        formFactor = formFactor,
        screenPadding = 48.dp,
        posterWidth = 168.dp,
        gridColumns = 6,
        usesNavRail = true,
        usesTwoPaneDetail = true,
        isFocusDriven = true,
    )

    FormFactor.TABLET -> LayoutMetrics(
        formFactor = formFactor,
        screenPadding = if (widthDp >= 1200) 32.dp else 24.dp,
        posterWidth = 150.dp,
        gridColumns = tabletColumns(widthDp),
        // A rail beside the content beats a bottom bar on a held tablet: the
        // bottom edge is the hardest place to reach two-handed in landscape.
        usesNavRail = widthDp >= 1000,
        usesTwoPaneDetail = widthDp >= 1000,
        isFocusDriven = false,
    )

    FormFactor.COMPACT -> LayoutMetrics(
        formFactor = formFactor,
        screenPadding = 16.dp,
        posterWidth = 126.dp,
        gridColumns = 3,
        usesNavRail = false,
        usesTwoPaneDetail = false,
        isFocusDriven = false,
    )
}

private fun tabletColumns(widthDp: Int): Int = when {
    widthDp >= 1400 -> 7
    widthDp >= 1200 -> 6
    widthDp >= 1000 -> 5
    else -> 4
}

/** Metrics for the current window. Provided once, near the root. */
val LocalLayoutMetrics = staticCompositionLocalOf {
    layoutMetricsFor(FormFactor.COMPACT, widthDp = 400)
}

/** Resolves metrics from the current configuration. */
@Composable
@ReadOnlyComposable
fun rememberLayoutMetrics(widthDp: Int): LayoutMetrics {
    val context = LocalContext.current
    return layoutMetricsFor(detectFormFactor(context, widthDp), widthDp)
}
