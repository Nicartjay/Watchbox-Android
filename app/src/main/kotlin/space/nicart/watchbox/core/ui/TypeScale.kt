package space.nicart.watchbox.core.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Extended type scale.
 *
 * Ported from NuvioMobile `core/ui/TypeScale.kt` + `Theme.kt:119-182`. These
 * styles sit alongside the Material3 ramp for cases the ramp doesn't cover
 * (player chrome, card meta lines, hero labels).
 */
@Immutable
data class WbTypeScale(
    val labelXs: TextStyle,
    val labelSm: TextStyle,
    val bodySm: TextStyle,
    val bodyMd: TextStyle,
    val bodyLg: TextStyle,
    val titleSm: TextStyle,
    val titleMd: TextStyle,
    val titleLg: TextStyle,
    val displaySm: TextStyle,
    val displayMd: TextStyle,
)

fun wbTypeScale(): WbTypeScale = WbTypeScale(
    labelXs = TextStyle(
        fontFamily = JetBrainsSans,
        fontSize = WbTokens.Type.labelXs,
        lineHeight = WbTokens.Type.labelXsLine,
        fontWeight = FontWeight.SemiBold,
    ),
    labelSm = TextStyle(
        fontFamily = JetBrainsSans,
        fontSize = WbTokens.Type.labelSm,
        lineHeight = WbTokens.Type.labelSmLine,
        fontWeight = FontWeight.SemiBold,
    ),
    bodySm = TextStyle(
        fontFamily = JetBrainsSans,
        fontSize = WbTokens.Type.bodySm,
        lineHeight = WbTokens.Type.bodySmLine,
        fontWeight = FontWeight.Normal,
    ),
    bodyMd = TextStyle(
        fontFamily = JetBrainsSans,
        fontSize = WbTokens.Type.bodyMd,
        lineHeight = WbTokens.Type.bodyMdLine,
        fontWeight = FontWeight.Normal,
    ),
    bodyLg = TextStyle(
        fontFamily = JetBrainsSans,
        fontSize = WbTokens.Type.bodyLg,
        lineHeight = WbTokens.Type.bodyLgLine,
        fontWeight = FontWeight.Normal,
    ),
    titleSm = TextStyle(
        fontFamily = JetBrainsSans,
        fontSize = WbTokens.Type.titleSm,
        lineHeight = WbTokens.Type.titleSmLine,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMd = TextStyle(
        fontFamily = JetBrainsSans,
        fontSize = WbTokens.Type.titleMd,
        lineHeight = WbTokens.Type.titleMdLine,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLg = TextStyle(
        fontFamily = JetBrainsSans,
        fontSize = WbTokens.Type.titleLg,
        lineHeight = WbTokens.Type.titleLgLine,
        fontWeight = FontWeight.SemiBold,
    ),
    displaySm = TextStyle(
        fontFamily = JetBrainsSans,
        fontSize = WbTokens.Type.displaySm,
        lineHeight = WbTokens.Type.displaySmLine,
        fontWeight = FontWeight.Bold,
    ),
    displayMd = TextStyle(
        fontFamily = JetBrainsSans,
        fontSize = WbTokens.Type.displayMd,
        lineHeight = WbTokens.Type.displayMdLine,
        fontWeight = FontWeight.Bold,
    ),
)

internal val LocalWbTypeScale = staticCompositionLocalOf { wbTypeScale() }
