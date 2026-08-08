package space.nicart.watchbox.core.ui

import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import space.nicart.watchbox.R

/**
 * JetBrains Sans, the typeface NuvioMobile ships (`Theme.kt:55-61`).
 * Only three weights exist; Medium/ExtraBold/Black are synthesised.
 */
val JetBrainsSans: FontFamily = FontFamily(
    Font(R.font.jetbrains_sans_regular, FontWeight.Normal),
    Font(R.font.jetbrains_sans_semibold, FontWeight.SemiBold),
    Font(R.font.jetbrains_sans_bold, FontWeight.Bold),
)

/** Material3 type ramp (`Theme.kt:63-117`). */
private fun wbTypography(): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = WbTokens.Type.pageDisplay,
            lineHeight = WbTokens.Type.pageDisplayLine,
            fontWeight = FontWeight.Bold,
            letterSpacing = WbTokens.Type.trackingPageDisplay,
        ),
        displayMedium = base.displayMedium.copy(fontFamily = JetBrainsSans),
        displaySmall = base.displaySmall.copy(fontFamily = JetBrainsSans),
        headlineLarge = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = WbTokens.Type.headline,
            lineHeight = WbTokens.Type.headlineLine,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = WbTokens.Type.trackingHeadline,
        ),
        headlineMedium = base.headlineMedium.copy(fontFamily = JetBrainsSans),
        headlineSmall = base.headlineSmall.copy(fontFamily = JetBrainsSans),
        titleLarge = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = WbTokens.Type.titleSm,
            lineHeight = WbTokens.Type.materialTitleLargeLine,
            fontWeight = FontWeight.SemiBold,
        ),
        titleMedium = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = WbTokens.Type.bodyLg,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        titleSmall = base.titleSmall.copy(fontFamily = JetBrainsSans),
        bodyLarge = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = WbTokens.Type.bodyApp,
            lineHeight = WbTokens.Type.bodyAppLine,
            fontWeight = FontWeight.Normal,
        ),
        bodyMedium = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = WbTokens.Type.bodyMd,
            lineHeight = WbTokens.Type.bodyMdLine,
            fontWeight = FontWeight.Normal,
        ),
        bodySmall = base.bodySmall.copy(fontFamily = JetBrainsSans),
        labelLarge = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = WbTokens.Type.bodyMd,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        labelMedium = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = WbTokens.Type.labelSm,
            lineHeight = WbTokens.Type.labelXsLine,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = WbTokens.Type.trackingLabel,
        ),
        labelSmall = base.labelSmall.copy(fontFamily = JetBrainsSans),
    )
}

/**
 * Root theme.
 *
 * Always dark — Nuvio ships no light scheme. Font scaling is pinned to 1x to
 * match upstream (`Theme.kt:200-204`), which keeps the dense player and detail
 * layouts intact regardless of the device font-size setting.
 */
@Composable
fun WatchBoxTheme(
    appTheme: AppTheme = AppTheme.Default,
    amoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val tokens = remember(appTheme, amoled) { wbThemeTokens(appTheme, amoled) }
    val palette = remember(appTheme) { paletteFor(appTheme) }
    val typography = remember { wbTypography() }
    val typeScale = remember { wbTypeScale() }

    val colorScheme = remember(tokens, palette) {
        darkColorScheme(
            primary = Color(palette.secondary),
            onPrimary = Color(palette.onSecondary),
            primaryContainer = Color(palette.focusBackground),
            onPrimaryContainer = tokens.colors.textPrimary,
            secondary = Color(palette.secondaryVariant),
            onSecondary = Color(palette.onSecondary),
            background = tokens.colors.background,
            onBackground = tokens.colors.textPrimary,
            surface = tokens.colors.surface,
            onSurface = tokens.colors.textPrimary,
            surfaceVariant = tokens.colors.surfaceCard,
            onSurfaceVariant = tokens.colors.textMuted,
            outline = tokens.colors.borderDefault,
            error = Color(0xFFE36A8A),
            onError = Color(0xFFFCE5EC),
        )
    }

    val density = LocalDensity.current
    val fixedDensity = remember(density.density) {
        Density(density = density.density, fontScale = 1f)
    }

    CompositionLocalProvider(
        LocalWbTokens provides tokens,
        LocalWbTypeScale provides typeScale,
        LocalDensity provides fixedDensity,
        LocalRippleConfiguration provides RippleConfiguration(color = Color.Black),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content,
        )
    }
}

/** `MaterialTheme.wb` — resolved design tokens. */
val MaterialTheme.wb: WbThemeTokens
    @Composable
    @ReadOnlyComposable
    get() = LocalWbTokens.current

/** `MaterialTheme.wbType` — the extended type scale. */
val MaterialTheme.wbType: WbTypeScale
    @Composable
    @ReadOnlyComposable
    get() = LocalWbTypeScale.current
