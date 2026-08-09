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
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import space.nicart.watchbox.domain.EpisodeEntry
import space.nicart.watchbox.domain.StreamOption

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
    onSelectStream: (StreamOption) -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSelectEpisode: (EpisodeEntry) -> Unit,
    onOpenSubtitleSettings: () -> Unit,
    subtitleStyle: SubtitleStyle,
    onSetSubtitleSize: (SubtitleSize) -> Unit,
    onSetSubtitleBackground: (SubtitleBackground) -> Unit,
    onSetSubtitleEdgeWidth: (SubtitleEdgeWidth) -> Unit,
    onSetSubtitleColor: (Int) -> Unit,
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
                        entries = state.streams.map { it.label },
                        selectedIndex = state.streams
                            .indexOfFirst { it.url == state.selectedStream?.url },
                        onSelect = { index ->
                            state.streams.getOrNull(index)?.let(onSelectStream)
                        },
                    )

                    // Track choice and appearance in one panel: both are "the
                    // subtitle settings" as far as the user is concerned, and
                    // appearance is most often adjusted while watching something
                    // whose subtitles are hard to read.
                    PlayerPanel.SUBTITLES -> Column {
                        PanelList(
                            title = stringResource(R.string.player_subtitles),
                            entries = listOf(stringResource(R.string.player_subtitles_off)) +
                                state.subtitles.map { it.label },
                            selectedIndex = state.selectedSubtitleIndex + 1,
                            onSelect = { onSelectSubtitle(it - 1) },
                            modifier = Modifier.weight(1f, fill = false),
                        )

                        Spacer(Modifier.height(12.dp))

                        PanelActionRow(
                            label = stringResource(R.string.player_subtitle_appearance),
                            onClick = onOpenSubtitleSettings,
                        )
                    }

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

                    PlayerPanel.SUBTITLE_STYLE -> SubtitleStylePanel(
                        style = subtitleStyle,
                        onSetSize = onSetSubtitleSize,
                        onSetBackground = onSetSubtitleBackground,
                        onSetEdgeWidth = onSetSubtitleEdgeWidth,
                        onSetColor = onSetSubtitleColor,
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
    modifier: Modifier = Modifier,
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
    onSelect: (EpisodeEntry) -> Unit,
) {
    val tokens = MaterialTheme.wb

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.player_episodes),
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
            items(items = state.episodes, key = { it.url }) { episode ->
                val selected = episode.url == state.episode?.url
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (selected) tokens.colors.accent else tokens.colors.surfaceCard,
                        )
                        .clickable { onSelect(episode) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = episode.numberLabel?.let { "Episode $it" } ?: "",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) {
                            tokens.colors.onAccent.copy(alpha = 0.8f)
                        } else {
                            tokens.colors.textMuted
                        },
                    )
                    Text(
                        text = episode.displayName,
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

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/**
 * A plain action row for the panel footer.
 *
 * Styled unlike the selectable rows above it: it navigates rather than selecting,
 * and making it look selectable would suggest it is another subtitle track.
 */
@Composable
private fun PanelActionRow(label: String, onClick: () -> Unit) {
    val tokens = MaterialTheme.wb

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surfaceCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Tune,
            contentDescription = null,
            tint = tokens.colors.accent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
        )
    }
}

/**
 * Subtitle appearance, adjusted without leaving the player.
 *
 * Only the options worth changing mid-episode are here: size, background style,
 * edge weight and colour. Opacity and bold live in Settings, where there is room for
 * them and where they are rarely the thing that needs fixing right now.
 */
@Composable
private fun SubtitleStylePanel(
    style: SubtitleStyle,
    onSetSize: (SubtitleSize) -> Unit,
    onSetBackground: (SubtitleBackground) -> Unit,
    onSetEdgeWidth: (SubtitleEdgeWidth) -> Unit,
    onSetColor: (Int) -> Unit,
) {
    val tokens = MaterialTheme.wb

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.player_subtitle_appearance),
            style = MaterialTheme.typography.titleLarge,
            color = tokens.colors.textPrimary,
        )

        PanelSectionLabel(stringResource(R.string.settings_subtitle_size))
        SubtitleSize.entries.forEach { option ->
            PanelChoiceRow(
                label = option.label,
                selected = style.size == option,
                onClick = { onSetSize(option) },
            )
        }

        PanelSectionLabel(stringResource(R.string.settings_subtitle_background))
        SubtitleBackground.entries.forEach { option ->
            PanelChoiceRow(
                label = option.label,
                selected = style.background == option,
                onClick = { onSetBackground(option) },
            )
        }

        // Hidden unless it applies: an edge width means nothing with no edge drawn.
        if (style.usesEdge) {
            PanelSectionLabel(stringResource(R.string.settings_subtitle_edge_width))
            SubtitleEdgeWidth.entries.forEach { option ->
                PanelChoiceRow(
                    label = option.label,
                    selected = style.edgeWidth == option,
                    onClick = { onSetEdgeWidth(option) },
                )
            }
        }

        PanelSectionLabel(stringResource(R.string.settings_subtitle_color))
        SUBTITLE_TEXT_COLORS.forEach { (name, color) ->
            PanelChoiceRow(
                label = name,
                selected = style.textColor == color,
                onClick = { onSetColor(color) },
            )
        }
    }
}

@Composable
private fun PanelSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.wb.colors.textMuted,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun PanelChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.wb

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) tokens.colors.accent else tokens.colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) tokens.colors.onAccent else tokens.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = tokens.colors.onAccent,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
