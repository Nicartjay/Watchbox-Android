package space.nicart.watchbox.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.core.ui.wbType
import kotlin.math.roundToInt

/**
 * Level indicator shown while a brightness or volume drag is in progress.
 *
 * Centred and compact rather than a full-height slider pinned to the edge: the
 * gesture already communicates *which* side is being adjusted, so the indicator
 * only needs to report the value, and a centred pill stays legible whichever
 * hand is covering the screen.
 */
@Composable
fun GestureLevelIndicator(
    gesture: VerticalGesture,
    level: Float,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb
    val type = MaterialTheme.wbType

    AnimatedVisibility(
        visible = gesture != VerticalGesture.NONE,
        enter = fadeIn(tween(120)),
        // Lingers slightly on exit so a quick adjustment is still readable.
        exit = fadeOut(tween(240)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = when {
                    gesture == VerticalGesture.BRIGHTNESS -> Icons.Rounded.Brightness6
                    // A distinct icon at zero, so silence is unmistakable.
                    level <= 0.01f -> Icons.AutoMirrored.Rounded.VolumeMute
                    else -> Icons.AutoMirrored.Rounded.VolumeUp
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )

            Text(
                text = "${(level.coerceIn(0f, 1f) * 100).roundToInt()}%",
                style = type.titleSm,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )

            // Vertical fill, matching the direction of the gesture itself.
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.24f)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(level.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(999.dp))
                        .background(tokens.colors.accent),
                )
            }
        }
    }
}
