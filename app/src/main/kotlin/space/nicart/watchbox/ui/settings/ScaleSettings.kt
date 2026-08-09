package space.nicart.watchbox.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import space.nicart.watchbox.core.ui.wb
import kotlin.math.roundToInt

/**
 * A labelled scale slider.
 *
 * Stepped rather than continuous. The value is applied through density, so every
 * intermediate value triggers a full relayout - dragging a continuous slider would
 * relayout the whole screen on every pixel of travel. Steps also make the setting
 * repeatable: "110%" is a value you can return to, "somewhere past the middle" is not.
 *
 * The change is committed on release rather than during the drag, for the same reason.
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

    // Local while dragging so the slider tracks the finger without relayouting the app
    // on every frame.
    var draft by remember(value) { mutableFloatStateOf(value) }

    // 5% increments across the range.
    val steps = ((range.endInclusive - range.start) / STEP - 1).roundToInt()

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
                text = "${(draft * 100).roundToInt()}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.accent,
            )
        }

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
}

private const val STEP = 0.05f
