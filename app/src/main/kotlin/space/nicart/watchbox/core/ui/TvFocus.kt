package space.nicart.watchbox.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
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

/**
 * Focus affordance that costs nothing on a touchscreen.
 *
 * Shared components are used by both form factors, so they cannot simply always draw a
 * focus border - on a phone, focus exists but is meaningless, and a stray outline after
 * a tap looks like a rendering fault. This applies the indicator only where focus is
 * the navigation method.
 *
 * Returns the interaction source so the caller passes the same instance to `clickable`.
 * That matters for ordering: adding a separate `focusable()` before `clickable` inserts
 * a focus target that swallows the D-pad centre key, so the item highlights but cannot
 * be activated.
 */
@Composable
fun Modifier.adaptiveFocus(
    interactionSource: MutableInteractionSource,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    scale: Boolean = true,
    /** Overridden where the item is itself white, which a white outline vanishes into. */
    borderColor: Color = Color.White,
): Modifier {
    val metrics = LocalLayoutMetrics.current
    if (!metrics.isFocusDriven) return this

    return if (scale) {
        tvFocusable(interactionSource, shape, borderColor)
    } else {
        tvFocusOutline(interactionSource, shape, borderColor)
    }
}

/**
 * Claims initial focus for a screen, on TV only.
 *
 * Every screen needs something focused before the first key press, or that press is
 * spent establishing focus and the remote appears dead. Compose focuses nothing by
 * default, and screens shared with the phone build have no reason to do it themselves.
 *
 * Applied to a container that wraps the screen's focusable content, so focus lands on
 * the first child rather than on the container itself.
 *
 * The retry matters: on the frame this runs, the screen's children may not have
 * composed yet, and `requestFocus` reports success even when its target has no node -
 * so observed focus is what the loop waits for, not the call's return value.
 */
@Composable
fun Modifier.tvInitialFocus(): Modifier {
    val metrics = LocalLayoutMetrics.current
    if (!metrics.isFocusDriven) return this

    val requester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        repeat(INITIAL_FOCUS_ATTEMPTS) {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
            if (hasFocus) return@LaunchedEffect
            delay(INITIAL_FOCUS_RETRY_MS)
        }
    }

    return this
        .focusRequester(requester)
        .onFocusChanged { hasFocus = it.hasFocus }
        .focusGroup()
}

private const val INITIAL_FOCUS_ATTEMPTS = 12
private const val INITIAL_FOCUS_RETRY_MS = 60L
