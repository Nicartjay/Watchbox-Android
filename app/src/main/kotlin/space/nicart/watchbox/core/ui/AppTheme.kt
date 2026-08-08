package space.nicart.watchbox.core.ui

import androidx.compose.runtime.Immutable

/**
 * Accent palettes.
 *
 * Ported from NuvioMobile `core/ui/AppTheme.kt` + `core/ui/ThemeColors.kt`.
 * Every palette is dark-only; [WHITE] is the default (monochrome accent).
 */
enum class AppTheme(val label: String) {
    WHITE("Monochrome"),
    CRIMSON("Crimson"),
    OCEAN("Ocean"),
    VIOLET("Violet"),
    EMERALD("Emerald"),
    AMBER("Amber"),
    ROSE("Rose"),
    ;

    companion object {
        val Default = WHITE

        fun fromName(name: String?): AppTheme =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Default
    }
}

/**
 * The seven raw colour slots each accent palette must define.
 * Values are ARGB longs so they read identically to the Nuvio source.
 */
@Immutable
data class ThemeColorPalette(
    val secondary: Long,
    val secondaryVariant: Long,
    val onSecondary: Long,
    val focusRing: Long,
    val focusBackground: Long,
    val background: Long,
    val backgroundElevated: Long,
    val backgroundCard: Long,
)

/** Exact palette values from NuvioMobile `ThemeColors.kt:20-97`. */
internal fun paletteFor(theme: AppTheme): ThemeColorPalette = when (theme) {
    AppTheme.CRIMSON -> ThemeColorPalette(
        secondary = 0xFFE53935,
        secondaryVariant = 0xFFC62828,
        onSecondary = 0xFFFFFFFF,
        focusRing = 0xFFFF5252,
        focusBackground = 0xFF3D1A1A,
        background = 0xFF0D0D0D,
        backgroundElevated = 0xFF1A1A1A,
        backgroundCard = 0xFF241A1A,
    )

    AppTheme.OCEAN -> ThemeColorPalette(
        secondary = 0xFF1E88E5,
        secondaryVariant = 0xFF1565C0,
        onSecondary = 0xFFFFFFFF,
        focusRing = 0xFF42A5F5,
        focusBackground = 0xFF1A2D3D,
        background = 0xFF0D0D0F,
        backgroundElevated = 0xFF1A1A1E,
        backgroundCard = 0xFF1A1F24,
    )

    AppTheme.VIOLET -> ThemeColorPalette(
        secondary = 0xFF8E24AA,
        secondaryVariant = 0xFF6A1B9A,
        onSecondary = 0xFFFFFFFF,
        focusRing = 0xFFAB47BC,
        focusBackground = 0xFF2D1A3D,
        background = 0xFF0D0D0F,
        backgroundElevated = 0xFF1A1A1E,
        backgroundCard = 0xFF1F1A24,
    )

    AppTheme.EMERALD -> ThemeColorPalette(
        secondary = 0xFF43A047,
        secondaryVariant = 0xFF2E7D32,
        onSecondary = 0xFFFFFFFF,
        focusRing = 0xFF66BB6A,
        focusBackground = 0xFF1A3D1E,
        background = 0xFF0D0D0D,
        backgroundElevated = 0xFF1A1A1A,
        backgroundCard = 0xFF1A241A,
    )

    AppTheme.AMBER -> ThemeColorPalette(
        secondary = 0xFFFB8C00,
        secondaryVariant = 0xFFEF6C00,
        onSecondary = 0xFF111111,
        focusRing = 0xFFFFA726,
        focusBackground = 0xFF3D2D1A,
        background = 0xFF0F0D0D,
        backgroundElevated = 0xFF1E1A1A,
        backgroundCard = 0xFF24201A,
    )

    AppTheme.ROSE -> ThemeColorPalette(
        secondary = 0xFFD81B60,
        secondaryVariant = 0xFFC2185B,
        onSecondary = 0xFFFFFFFF,
        focusRing = 0xFFEC407A,
        focusBackground = 0xFF3D1A2D,
        background = 0xFF0D0D0D,
        backgroundElevated = 0xFF1A1A1A,
        backgroundCard = 0xFF241A1F,
    )

    AppTheme.WHITE -> ThemeColorPalette(
        secondary = 0xFFF5F5F5,
        secondaryVariant = 0xFFE0E0E0,
        onSecondary = 0xFF111111,
        focusRing = 0xFFFFFFFF,
        focusBackground = 0xFF303030,
        background = 0xFF0D0D0D,
        backgroundElevated = 0xFF1A1A1A,
        backgroundCard = 0xFF222222,
    )
}

/**
 * Public accessor for a theme's raw palette.
 * Used by the settings screen to draw accent swatches outside a [WatchBoxTheme].
 */
fun paletteForPreview(theme: AppTheme): ThemeColorPalette = paletteFor(theme)
