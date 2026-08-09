package space.nicart.watchbox.ui.settings

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.core.ui.LocalLayoutMetrics
import space.nicart.watchbox.core.ui.adaptiveFocus
import space.nicart.watchbox.core.ui.wb
import kotlin.math.roundToInt

/**
 * A scale control.
 *
 * Two presentations, because a slider is not operable with a D-pad. Compose's [Slider]
 * takes focus and then consumes left/right to move its thumb - but only when it has
 * focus, and reaching it means the surrounding rows have to hand focus over. In practice
 * on a television it either cannot be reached or cannot be left, so the value never
 * changes.
 *
 * On TV the same value is exposed as two stepper buttons instead. Each is an ordinary
 * focusable button, so it works with the same navigation as everything else on the
 * screen, and pressing OK is unambiguous where dragging a thumb by proxy is not.
 *
 * Both forms step rather than moving continuously: the value is applied through density,
 * so every intermediate value relayouts the whole app, and a repeatable "110%" is more
 * useful than "somewhere past the middle".
 */
@Composable
fun ScaleSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb
    val isTv = LocalLayoutMetrics.current.isTv

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textPrimary,
            )
            Text(
                text = "${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.accent,
            )
        }

        if (isTv) {
            TvScaleStepper(value = value, range = range, onValueChange = onValueChange)
        } else {
            TouchScaleSlider(value = value, range = range, onValueChange = onValueChange)
        }
    }
}

/** Stepper buttons plus a progress track, for remote control. */
@Composable
private fun TvScaleStepper(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    val tokens = MaterialTheme.wb

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StepButton(
            icon = Icons.Rounded.Remove,
            description = "Decrease",
            // Disabled at the bound rather than allowed to no-op, so a press that does
            // nothing is visibly a limit rather than a broken button.
            enabled = value > range.start,
            onClick = { onValueChange((value - STEP).coerceIn(range) ) },
        )

        // Fill showing where the value sits in its range: the number above is exact, but
        // a bar is what makes "how much room is left" legible at distance.
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(tokens.colors.surface),
        ) {
            val fraction = ((value - range.start) / (range.endInclusive - range.start))
                .coerceIn(0f, 1f)

            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp))
                    .background(tokens.colors.accent),
            )
        }

        StepButton(
            icon = Icons.Rounded.Add,
            description = "Increase",
            enabled = value < range.endInclusive,
            onClick = { onValueChange((value + STEP).coerceIn(range)) },
        )
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.wb
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surface)
            .adaptiveFocus(interaction, RoundedCornerShape(12.dp), scale = false)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) tokens.colors.textPrimary else tokens.colors.textMuted,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * The touch slider.
 *
 * Kept local while dragging and committed on release: the value is applied through
 * density, so writing on every frame would relayout the app continuously.
 */
@Composable
private fun TouchScaleSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    val tokens = MaterialTheme.wb
    var draft by remember(value) { mutableFloatStateOf(value) }

    val steps = ((range.endInclusive - range.start) / STEP - 1).roundToInt()

    Slider(
        value = draft,
        onValueChange = { draft = it },
        onValueChangeFinished = { onValueChange(draft) },
        valueRange = range,
        steps = steps.coerceAtLeast(0),
        colors = SliderDefaults.colors(
            thumbColor = tokens.colors.accent,
            activeTrackColor = tokens.colors.accent,
            inactiveTrackColor = tokens.colors.surface,
        ),
    )
}

private const val STEP = 0.05f
