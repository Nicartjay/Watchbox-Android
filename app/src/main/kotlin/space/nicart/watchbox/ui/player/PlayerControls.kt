package space.nicart.watchbox.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.wbType
import space.nicart.watchbox.domain.formatTimecode

/**
 * Player control overlay.
 *
 * Ported from NuvioMobile `features/player/PlayerControls.kt` +
 * `PlayerLayout.kt`. Phone metrics:
 *   horizontal padding 20dp, top scrim 160dp, bottom scrim 220dp,
 *   centre gap 56dp, slider bottom offset 16dp / touch height 22dp / scaleY 0.82,
 *   header icons 20dp in 36dp circles, play icon 34dp.
 */
data class PlayerMetrics(
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val centerGap: Dp,
    val centerLift: Dp,
    val sliderBottomOffset: Dp,
    val sliderTouchHeight: Dp,
    val sliderScaleY: Float,
    val headerIconSize: Dp,
    val sideIconSize: Dp,
    val playIconSize: Dp,
)

fun playerMetricsFor(width: Dp): PlayerMetrics = when {
    width >= 1440.dp -> PlayerMetrics(
        28.dp, 24.dp, 112.dp, 10.dp, 28.dp, 28.dp, 0.72f, 24.dp, 34.dp, 44.dp,
    )

    width >= 1024.dp -> PlayerMetrics(
        24.dp, 20.dp, 88.dp, 10.dp, 24.dp, 26.dp, 0.74f, 22.dp, 32.dp, 42.dp,
    )

    width >= 768.dp -> PlayerMetrics(
        20.dp, 16.dp, 72.dp, 10.dp, 20.dp, 24.dp, 0.78f, 20.dp, 30.dp, 38.dp,
    )

    else -> PlayerMetrics(
        20.dp, 16.dp, 56.dp, 10.dp, 16.dp, 22.dp, 0.82f, 20.dp, 26.dp, 34.dp,
    )
}

/**
 * Which sheet is open, if any.
 *
 * No audio-track or alternate-host panels: the lib-14 extension ABI exposes
 * neither, so offering them would be dead UI.
 */
enum class PlayerPanel {
    NONE,
    QUALITY,
    SUBTITLES,

    /**
     * Subtitle appearance, opened from the subtitle panel.
     *
     * A panel rather than navigation to Settings: leaving the player tears down the
     * surface and loses the position, and appearance is exactly the setting you want
     * to adjust against the video you are watching.
     */
    SUBTITLE_STYLE,
    SPEED,
    EPISODES,
}

@Composable
fun PlayerControlsOverlay(
    state: PlayerUiState,
    metrics: PlayerMetrics,
    isPlaying: Boolean,
    isBuffering: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onBack: () -> Unit,
    onToggleLock: () -> Unit,
    onCycleAspect: () -> Unit,
    onOpenPanel: (PlayerPanel) -> Unit,
    isCasting: Boolean = false,
    onOpenCast: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // --- scrims
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(160.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(220.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.horizontalPadding),
        ) {
            PlayerHeader(
                state = state,
                metrics = metrics,
                onBack = onBack,
                onToggleLock = onToggleLock,
                isCasting = isCasting,
                onOpenCast = onOpenCast,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = metrics.verticalPadding / 4),
            )

            CenterControls(
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                metrics = metrics,
                onPlayPause = onPlayPause,
                onSeekBy = onSeekBy,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = metrics.centerLift),
            )

            ProgressControls(
                state = state,
                metrics = metrics,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                onSeek = onSeek,
                onCycleAspect = onCycleAspect,
                onOpenPanel = onOpenPanel,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = metrics.sliderBottomOffset),
            )
        }
    }
}

@Composable
private fun PlayerHeader(
    state: PlayerUiState,
    metrics: PlayerMetrics,
    onBack: () -> Unit,
    onToggleLock: () -> Unit,
    isCasting: Boolean,
    onOpenCast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = MaterialTheme.wbType

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = state.title,
                style = type.titleLg.copy(fontSize = type.titleSm.fontSize),
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            state.episodeLabel?.let {
                Text(
                    text = it,
                    style = type.bodyMd,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            state.detail?.sourceName?.let { server ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = server,
                        style = type.labelSm,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    state.selectedStream?.label?.let { quality ->
                        Text(
                            text = quality,
                            style = type.labelSm,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CastButton(
                isCasting = isCasting,
                size = metrics.headerIconSize,
                onClick = onOpenCast,
            )
            HeaderIconButton(
                icon = if (state.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                size = metrics.headerIconSize,
                onClick = onToggleLock,
            )
            HeaderIconButton(
                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                size = metrics.headerIconSize,
                onClick = {},
                enabled = false,
            )
            HeaderIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                size = metrics.headerIconSize,
                onClick = onBack,
            )
        }
    }
}

@Composable
private fun HeaderIconButton(
    icon: ImageVector,
    size: Dp,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    if (!enabled) return
    Box(
        modifier = Modifier
            .size(size + 16.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size),
        )
    }
}

@Composable
private fun CenterControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    metrics: PlayerMetrics,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(metrics.centerGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable { onSeekBy(-10_000L) }
                .padding(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Replay10,
                contentDescription = "Back 10 seconds",
                tint = Color.White,
                modifier = Modifier.size(metrics.playIconSize),
            )
        }

        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(enabled = !isBuffering, onClick = onPlayPause)
                .padding(13.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isBuffering) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(metrics.playIconSize),
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(metrics.playIconSize),
                )
            }
        }

        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable { onSeekBy(10_000L) }
                .padding(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Forward10,
                contentDescription = "Forward 10 seconds",
                tint = Color.White,
                modifier = Modifier.size(metrics.playIconSize),
            )
        }
    }
}

@Composable
private fun ProgressControls(
    state: PlayerUiState,
    metrics: PlayerMetrics,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onSeek: (Long) -> Unit,
    onCycleAspect: () -> Unit,
    onOpenPanel: (PlayerPanel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Slider(
            value = if (durationMs > 0) {
                (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            } else {
                0f
            },
            onValueChange = { fraction ->
                if (durationMs > 0) onSeek((fraction * durationMs).toLong())
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.30f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(metrics.sliderTouchHeight)
                .graphicsLayer { scaleY = metrics.sliderScaleY },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TimePill(formatTimecode(positionMs))
            TimePill(formatTimecode(durationMs))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Color.White.copy(alpha = 0.2f),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionPill(
                        icon = Icons.Rounded.AspectRatio,
                        label = state.aspectMode.label,
                        onClick = onCycleAspect,
                    )
                    ActionPill(
                        icon = Icons.Rounded.Speed,
                        label = "${state.speed.formatSpeed()}x",
                        onClick = { onOpenPanel(PlayerPanel.SPEED) },
                    )
                    if (state.streams.size > 1) {
                        ActionPill(
                            icon = Icons.Rounded.SkipNext,
                            label = state.selectedStream?.label ?: "Auto",
                            onClick = { onOpenPanel(PlayerPanel.QUALITY) },
                        )
                    }
                    if (state.subtitles.isNotEmpty()) {
                        ActionPill(
                            icon = Icons.Rounded.ClosedCaption,
                            label = stringResource(R.string.player_subtitles),
                            onClick = { onOpenPanel(PlayerPanel.SUBTITLES) },
                        )
                    }
                    if (state.episodes.size > 1) {
                        ActionPill(
                            icon = Icons.Filled.VideoLibrary,
                            label = stringResource(R.string.player_episodes),
                            onClick = { onOpenPanel(PlayerPanel.EPISODES) },
                        )
                    }
                }
            }
        }
    }
}

/** `Black@50%` pill with a 1dp `White@20%` border (`PlayerControls.kt:686-707`). */
@Composable
private fun TimePill(text: String) {
    val type = MaterialTheme.wbType
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = type.labelSm,
            color = Color.White,
        )
    }
}

@Composable
private fun ActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val type = MaterialTheme.wbType
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = type.labelSm,
            color = Color.White,
            maxLines = 1,
        )
    }
}

/**
 * Locked overlay (`PlayerControls.kt:587-683`): 78dp circle with a 34dp lock and
 * a "Tap to unlock" caption.
 */
@Composable
fun LockedPlayerOverlay(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = MaterialTheme.wbType
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(220.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                    ),
                ),
        )

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.52f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                    .clickable(onClick = onUnlock),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "Unlock",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.player_locked),
                style = type.bodyMd,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.92f),
            )
        }
    }
}

private fun Float.formatSpeed(): String =
    if (this == this.toInt().toFloat()) this.toInt().toString() else "%.2f".format(this).trimEnd('0').trimEnd('.')
