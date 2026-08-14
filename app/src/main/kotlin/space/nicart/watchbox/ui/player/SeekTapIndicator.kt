package space.nicart.watchbox.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.core.ui.wbType
import space.nicart.watchbox.domain.formatTimecode
import kotlin.math.abs

/**
 * Feedback for a double-tap seek: where playback landed, and by how much it moved.
 *
 * A double-tap on a still frame is otherwise invisible - the picture may not change perceptibly,
 * so there is no way to tell a registered tap from an ignored one, or to know how far a run of
 * taps has travelled.
 *
 * The accumulated total is shown, not a flat ±10s: tapping four times quickly reads as "+40s",
 * which is the number the viewer actually cares about.
 *
 * Side placement is the caller's business - it aligns this within the player Box - so nothing
 * here needs to know which half was tapped.
 */
@Composable
fun SeekTapIndicator(
    /** Signed accumulated offset in milliseconds; zero hides the indicator. */
    accumulatedMs: Long,
    /** Position seeked to, for the absolute timecode. */
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb
    val type = MaterialTheme.wbType

    AnimatedVisibility(
        visible = accumulatedMs != 0L,
        enter = fadeIn(tween(90)),
        // Lingers so the final value of a rapid run of taps stays readable after the last one.
        exit = fadeOut(tween(340)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                // Mirrors the direction of travel rather than the side tapped. They agree in
                // normal use, but a seek clamped at either end does not move the way the tap
                // implied, and the arrow should not claim otherwise.
                imageVector = if (accumulatedMs < 0) {
                    Icons.Rounded.Replay10
                } else {
                    Icons.Rounded.Forward10
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(34.dp),
            )

            Text(
                // Signed, because a run of taps in one direction is the case worth reporting:
                // "+30s" is more use than three separate "+10s" flashes.
                text = buildString {
                    append(if (accumulatedMs < 0) '-' else '+')
                    append(abs(accumulatedMs) / 1000)
                    append('s')
                },
                style = type.titleSm,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )

            Text(
                // The absolute position, so a long seek can be judged against the runtime
                // rather than only as a relative jump.
                text = formatTimecode(positionMs),
                style = type.labelSm,
                color = tokens.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Folds a new double-tap into the running total.
 *
 * Reversing direction starts over rather than netting off. A backward tap after three forward
 * ones is a correction, not part of the same run - reporting the net "+20s" would answer a
 * question nobody asked, and could even read as "+0s" and look like the tap was ignored.
 *
 * Top-level so the rule can be tested directly: it is one comparison, and getting the sign
 * wrong produces a readout that quietly disagrees with where playback actually landed.
 */
internal fun accumulateSeekTap(currentMs: Long, deltaMs: Long): Long {
    val sameDirection = currentMs == 0L || (currentMs < 0) == (deltaMs < 0)
    return if (sameDirection) currentMs + deltaMs else deltaMs
}
