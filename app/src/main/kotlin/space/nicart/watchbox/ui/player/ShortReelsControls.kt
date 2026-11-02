package space.nicart.watchbox.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.domain.formatTimecode

/** Space kept clear of the persistent title/timeline by subtitles and skip buttons. */
internal val SHORT_REELS_BOTTOM_INSET = 200.dp

/**
 * Portrait presentation over the existing player/transport. No second ExoPlayer:
 * streams, downloads, casting, subtitles and progress keep their established paths.
 */
@Composable
internal fun ShortReelsControls(
    state: PlayerUiState,
    isPlaying: Boolean,
    isBuffering: Boolean,
    showPlaybackControl: Boolean,
    hideTransport: Boolean,
    seekable: Boolean,
    positionMs: Long,
    durationMs: Long,
    isCasting: Boolean,
    audioTrackCount: Int,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenPanel: (PlayerPanel) -> Unit,
    onOpenCast: () -> Unit,
    onLock: () -> Unit,
) {
    val accent = MaterialTheme.wb.colors.accent
    val hasStream = state.selectedStream != null && !state.isResolving
    val hasServers = state.streams.serverOptions().size > 1 || state.isLoadingMoreStreams
    val hasAudioChoices = audioTrackCount > 1 ||
        state.streams.dubOptions(state.selectedStream?.facets?.server).size > 1
    val hasEpisodes = state.episodes.isNotEmpty()
    val hasQualityChoices = hasStream && state.streams.hasMultipleQualityChoices(state.selectedStream)
    val hasSubtitles = hasStream && state.hasSubtitleTracks
    var optionsOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(120.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(280.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))),
        )

        BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReelIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    label = stringResource(R.string.player_back),
                    onClick = onBack,
                )
                Text(
                    text = stringResource(R.string.nav_short_reels),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                ReelIconButton(
                    icon = if (isCasting) Icons.Filled.CastConnected else Icons.Rounded.Cast,
                    label = stringResource(R.string.player_cast),
                    enabled = hasStream,
                    onClick = onOpenCast,
                )
                Spacer(Modifier.width(12.dp))
                ReelIconButton(
                    icon = Icons.Filled.Lock,
                    label = stringResource(R.string.player_lock),
                    onClick = onLock,
                )
                // Only less common server/audio choices stay in the overflow.
                if (hasServers || hasAudioChoices) {
                    Box {
                        ReelIconButton(
                            icon = Icons.Rounded.MoreVert,
                            label = stringResource(R.string.player_options),
                            onClick = { optionsOpen = true },
                        )
                        DropdownMenu(expanded = optionsOpen, onDismissRequest = { optionsOpen = false }) {
                            fun open(panel: PlayerPanel) {
                                optionsOpen = false
                                onOpenPanel(panel)
                            }
                            if (hasServers) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.player_server)) },
                                    onClick = { open(PlayerPanel.SERVER) },
                                )
                            }
                            if (hasAudioChoices) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.player_audio)) },
                                    onClick = { open(PlayerPanel.DUB) },
                                )
                            }
                        }
                    }
                }
            }

            // Icon-only actions above the episode controls, with accessible names retained.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        top = 72.dp,
                        end = 12.dp,
                        bottom = shortReelsRailBottomPadding(
                            maxHeight, hasEpisodes, hasQualityChoices, hasSubtitles,
                        ),
                    )
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hasQualityChoices) {
                    ReelIconButton(
                        icon = Icons.Rounded.HighQuality,
                        label = stringResource(R.string.player_quality),
                        onClick = { onOpenPanel(PlayerPanel.QUALITY) },
                    )
                }
                if (hasSubtitles) {
                    ReelIconButton(
                        icon = Icons.Rounded.Subtitles,
                        label = stringResource(R.string.player_subtitles),
                        onClick = { onOpenPanel(PlayerPanel.SUBTITLES) },
                    )
                }
                ReelIconButton(
                    icon = Icons.Rounded.Speed,
                    label = stringResource(R.string.player_speed),
                    onClick = { onOpenPanel(PlayerPanel.SPEED) },
                )
                if (hasEpisodes) {
                    ReelIconButton(
                        icon = Icons.Rounded.KeyboardArrowUp,
                        label = stringResource(R.string.player_previous_episode),
                        enabled = state.hasPreviousEpisode,
                        onClick = onPrevious,
                    )
                    ReelIconButton(
                        icon = Icons.Rounded.VideoLibrary,
                        label = stringResource(R.string.player_episodes),
                        onClick = { onOpenPanel(PlayerPanel.EPISODES) },
                    )
                    ReelIconButton(
                        icon = Icons.Rounded.KeyboardArrowDown,
                        label = stringResource(R.string.player_next_episode),
                        enabled = state.hasNextEpisode,
                        onClick = onNext,
                    )
                }
            }

            if (hasStream && !hideTransport) {
                if (isBuffering) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center).size(42.dp),
                    )
                } else if (showPlaybackControl || !isPlaying) {
                    ReelIconButton(
                        icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        label = stringResource(
                            if (isPlaying) R.string.player_pause else R.string.action_play,
                        ),
                        onClick = onPlayPause,
                        modifier = Modifier.align(Alignment.Center).size(72.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 80.dp, bottom = 76.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.detail?.sourceName?.takeIf { it.isNotBlank() }?.let { provider ->
                    Text(
                        text = provider,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(accent.copy(alpha = 0.8f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                if (state.title.isNotBlank()) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                state.episodeLabel?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(
                        if (state.episodes.size > 1) R.string.short_reels_swipe_hint
                        else R.string.short_reels_tap_hint,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.65f),
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                // Seek once on release, not on every drag pixel. Keyed by episode so an
                // unfinished scrub cannot be carried into a different reel.
                var draft by remember(state.episode?.url) { mutableFloatStateOf(Float.NaN) }
                Slider(
                    value = if (!draft.isNaN()) draft else {
                        if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                    },
                    onValueChange = { draft = it },
                    onValueChangeFinished = {
                        if (!draft.isNaN() && durationMs > 0) onSeek((draft * durationMs).toLong())
                        draft = Float.NaN
                    },
                    enabled = hasStream && seekable && durationMs > 0,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = accent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                    ),
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatTimecode(positionMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Text(formatTimecode(durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ReelIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f)),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.3f),
            modifier = Modifier.size(28.dp),
        )
    }
}
