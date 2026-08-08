package space.nicart.watchbox.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.core.ui.wbType
import space.nicart.watchbox.domain.PlayableStream

/**
 * Player side panels + skip button.
 *
 * Panels follow NuvioMobile `features/player/PlayerSidePanel.kt`: a right-edge
 * drawer capped at 520dp, `surfaceElevated` fill, 16dp leading corners, scrim at
 * `Black@34%`, slide-in 250ms / slide-out 200ms.
 */
@Composable
fun PlayerPanels(
    panel: PlayerPanel,
    state: PlayerUiState,
    onDismiss: () -> Unit,
    onSelectStream: (PlayableStream) -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onSelectHost: (Int) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSelectEpisode: (Int) -> Unit,
) {
    val visible = panel != PlayerPanel.NONE

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(160)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f))
                .clickable(onClick = onDismiss),
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(tween(250)) { it },
        exit = slideOutHorizontally(tween(200)) { it },
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            PlayerPanelSurface {
                when (panel) {
                    PlayerPanel.QUALITY -> PanelList(
                        title = stringResource(R.string.player_quality),
                        entries = state.source?.streams.orEmpty().map { it.label },
                        selectedIndex = state.source?.streams.orEmpty()
                            .indexOfFirst { it.url == state.selectedStream?.url },
                        onSelect = { index ->
                            state.source?.streams?.getOrNull(index)?.let(onSelectStream)
                        },
                    )

                    PlayerPanel.SUBTITLES -> PanelList(
                        title = stringResource(R.string.player_subtitles),
                        entries = listOf(stringResource(R.string.player_subtitles_off)) +
                            state.source?.subtitles.orEmpty()
                            .map { it.label },
                        selectedIndex = state.selectedSubtitleIndex + 1,
                        onSelect = { onSelectSubtitle(it - 1) },
                    )

                    PlayerPanel.AUDIO -> PanelList(
                        title = stringResource(R.string.player_audio),
                        entries = state.source?.audioTracks.orEmpty().map { it.label },
                        selectedIndex = state.selectedAudioIndex,
                        onSelect = onSelectAudio,
                    )

                    PlayerPanel.HOSTS -> PanelList(
                        title = stringResource(R.string.player_servers),
                        entries = state.source?.hosts.orEmpty().map { it.label },
                        selectedIndex = state.source?.hosts.orEmpty()
                            .indexOfFirst { it.url == state.selectedStream?.url },
                        onSelect = onSelectHost,
                    )

                    PlayerPanel.SPEED -> PanelList(
                        title = stringResource(R.string.player_speed),
                        entries = SPEEDS.map { "${it}x" },
                        selectedIndex = SPEEDS.indexOfFirst { it == state.speed },
                        onSelect = { onSelectSpeed(SPEEDS[it]) },
                    )

                    PlayerPanel.EPISODES -> EpisodePanel(
                        state = state,
                        onSelect = onSelectEpisode,
                    )

                    PlayerPanel.NONE -> Unit
                }
            }
        }
    }
}

@Composable
private fun PlayerPanelSurface(content: @Composable () -> Unit) {
    val tokens = MaterialTheme.wb
    Box(
        modifier = Modifier
            .width(420.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .background(tokens.colors.surfaceElevated)
            .padding(24.dp),
    ) {
        content()
    }
}

@Composable
private fun PanelList(
    title: String,
    entries: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val tokens = MaterialTheme.wb

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(entries) { index, label ->
                val selected = index == selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) tokens.colors.accent else Color.Transparent)
                        .clickable { onSelect(index) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selected) {
                            tokens.colors.onAccent
                        } else {
                            tokens.colors.textPrimary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = tokens.colors.onAccent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodePanel(
    state: PlayerUiState,
    onSelect: (Int) -> Unit,
) {
    val tokens = MaterialTheme.wb

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Season ${state.season}",
            style = MaterialTheme.typography.headlineSmall,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = state.episodes, key = { it.episode }) { episode ->
                val selected = episode.episode == state.episode
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (selected) tokens.colors.accent else tokens.colors.surfaceCard,
                        )
                        .clickable { onSelect(episode.episode) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = episode.code,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) {
                            tokens.colors.onAccent.copy(alpha = 0.8f)
                        } else {
                            tokens.colors.textMuted
                        },
                    )
                    Text(
                        text = episode.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selected) {
                            tokens.colors.onAccent
                        } else {
                            tokens.colors.textPrimary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Skip intro/outro pill (`skip/SkipIntroButton.kt`): `#1E1E1E` at 85%, 16dp
 * radius, 20dp icon + 14sp label, enters with fade + scale from 0.8.
 */
@Composable
fun SkipSegmentButton(
    state: PlayerUiState,
    positionMs: Long,
    onSkip: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = MaterialTheme.wbType

    val intro = state.source?.introRange
    val outro = state.source?.outroRange

    val active = when {
        intro != null && positionMs in intro ->
            stringResource(R.string.player_skip_intro) to intro.endInclusive
        outro != null && positionMs in outro ->
            stringResource(R.string.player_skip_outro) to outro.endInclusive
        else -> null
    }

    AnimatedVisibility(
        visible = active != null && !state.locked,
        enter = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.8f),
        exit = fadeOut(tween(200)),
        modifier = modifier.padding(start = 20.dp, bottom = 120.dp),
    ) {
        active?.let { (label, target) ->
            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E1E1E).copy(alpha = 0.85f))
                    .clickable { onSkip(target) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = label,
                        style = type.bodyMd,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
