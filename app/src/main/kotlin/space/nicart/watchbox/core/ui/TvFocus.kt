package space.nicart.watchbox.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Focus affordances for D-pad navigation.
 *
 * On a touchscreen the finger *is* the cursor, so focus needs no representation. On
 * a television the remote moves an invisible cursor, and without a strong visual
 * indicator the user has no idea what pressing OK will do. This is the single most
 * important difference between a phone UI shown on a TV and a TV UI.
 *
 * The indicator is deliberately loud - a bright border plus a scale-up - because it
 * has to be legible from three metres, where the subtle ripple a phone uses is
 * invisible.
 */

/**
 * Marks a composable as D-pad focusable and gives it a visible focused state.
 *
 * Returns the [MutableInteractionSource] so the caller can pass the same instance to
 * `clickable`, which is what makes a single element both focusable and clickable
 * without two competing interaction states.
 */
@Composable
fun rememberFocusInteraction(): MutableInteractionSource = remember { MutableInteractionSource() }

/**
 * Scales and outlines a composable while focused.
 *
 * Scale is applied rather than a shadow because a scale reads at distance and costs
 * no extra draw layer. It is kept small: anything above about 1.1 makes neighbouring
 * items visibly shift, which looks like the layout is jumping.
 */
fun Modifier.tvFocusable(
    interactionSource: MutableInteractionSource,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    borderColor: Color = Color.White,
    borderWidth: Dp = 3.dp,
    focusedScale: Float = 1.06f,
    enabled: Boolean = true,
): Modifier = composed {
    val isFocused by interactionSource.collectIsFocusedAsState()

    // Animated so movement across a row reads as continuous rather than snapping,
    // which at TV distance is what makes navigation feel located.
    val scale by animateFloatAsState(
        targetValue = if (isFocused && enabled) focusedScale else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "tvFocusScale",
    )

    this
        .scale(scale)
        .border(
            width = if (isFocused && enabled) borderWidth else 0.dp,
            color = if (isFocused && enabled) borderColor else Color.Transparent,
            shape = shape,
        )
}

/**
 * A focus outline without the scale.
 *
 * For items inside a scrolling container whose height is fixed by its neighbours -
 * list rows, nav items - where scaling would push adjacent content around.
 */
fun Modifier.tvFocusOutline(
    interactionSource: MutableInteractionSource,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    borderColor: Color = Color.White,
    borderWidth: Dp = 3.dp,
): Modifier = composed {
    val isFocused by interactionSource.collectIsFocusedAsState()

    border(
        width = if (isFocused) borderWidth else 0.dp,
        color = if (isFocused) borderColor else Color.Transparent,
        shape = shape,
    )
}
