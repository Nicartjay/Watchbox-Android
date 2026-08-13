package space.nicart.watchbox.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.wb

/**
 * Covers the video surface while casting, naming the receiver.
 *
 * The local player is muted and paused during a session, so the surface behind this is a frozen
 * frame or a black rectangle. Without something drawn over it the screen reads as a stalled
 * player rather than a working remote control - the picture is stopped, the timeline is moving,
 * and nothing explains why.
 *
 * Deliberately not part of the auto-hiding controls: the state it reports is true for the whole
 * session, not just while the controls happen to be up. The controls draw on top of it.
 */
@Composable
fun CastingOverlay(
    isCasting: Boolean,
    controlsVisible: Boolean,
    deviceName: String?,
    title: String,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb

    AnimatedVisibility(
        visible = isCasting,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(160)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Opaque, not a scrim: the frame underneath belongs to a moment the receiver
                // has long since played past, so showing it through would be misleading.
                .background(tokens.colors.background),
            contentAlignment = Alignment.Center,
        ) {
            // The centred block yields the screen to the controls rather than sharing it.
            //
            // CenterControls is aligned dead centre, which is exactly where this sits, so the
            // two would print on top of each other. Hiding it while the controls are up is a
            // guarantee by construction - no measured header height, no tuned offsets, nothing
            // to drift when the metrics change per screen size.
            //
            // The receiver stays named throughout: the header carries a badge that is part of
            // its own layout, so that cannot collide either. The opaque background above is
            // unconditional, so the stale video frame stays hidden either way.
            AnimatedVisibility(
                visible = !controlsVisible,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(120)),
            ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            ) {
                // Pulsed rather than static. A cast session has no other movement on this
                // screen once the controls hide, and a still icon over a black rectangle is
                // hard to distinguish from a frozen app.
                val transition = rememberInfiniteTransition(label = "cast-pulse")
                val pulse by transition.animateFloat(
                    initialValue = 0.55f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1_400),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "cast-pulse-alpha",
                )

                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(tokens.colors.surfaceCard),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.CastConnected,
                        contentDescription = null,
                        tint = tokens.colors.accent,
                        modifier = Modifier
                            .size(44.dp)
                            .alpha(pulse),
                    )
                }

                Spacer(Modifier.height(24.dp))

                Text(
                    // Falls back to a bare "Casting" when the name is not known yet: the
                    // receiver reports it asynchronously, and an empty gap where the device
                    // should be reads as a failure.
                    text = deviceName?.takeIf { it.isNotBlank() }?.let {
                        stringResource(R.string.cast_now_casting, it)
                    } ?: stringResource(R.string.cast_button),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                    textAlign = TextAlign.Center,
                )

                if (title.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = tokens.colors.textSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    // States what the screen is for. The controls still work and still drive
                    // the receiver, which is not obvious when the picture has gone.
                    text = stringResource(R.string.cast_now_casting_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
            }
        }
    }
}
