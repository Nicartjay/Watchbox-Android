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
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.adaptiveFocus
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.core.ui.wbType
import space.nicart.watchbox.data.remote.SubtitleResult
import space.nicart.watchbox.domain.EpisodeEntry
import space.nicart.watchbox.domain.StreamOption
import kotlinx.coroutines.delay
import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.RecordVoiceOver

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
    onSearchSubtitles: () -> Unit,
    onApplySubtitle: (SubtitleResult) -> Unit,
    subtitleStyle: SubtitleStyle,
    onSetSubtitleSize: (SubtitleSize) -> Unit,
    onSetSubtitleBackground: (SubtitleBackground) -> Unit,
    onSetSubtitleEdgeWidth: (SubtitleEdgeWidth) -> Unit,
    onSetSubtitleColor: (Int) -> Unit,
    onMarkSync: (SyncMark) -> Unit,
    onCancelSync: () -> Unit,
    onNudgeSubtitleOffset: (Long) -> Unit,
    onResetSubtitleOffset: () -> Unit,
    onOpenSubtitleSync: () -> Unit,
    /**
     * Text tracks carried inside the stream itself, newest track list first.
     *
     * Separate from `state.subtitles`, which holds only what the source handed
     * over or the user downloaded. An MKV routinely embeds its own, and those had
     * no entry anywhere: the panel listed our list alone, so a file whose only
     * subtitles were embedded showed "Off" and nothing else, and the selection
     * effect disabled the text renderer because nothing was selected.
     */
    embeddedSubtitles: List<String> = emptyList(),
    /** Which embedded track is active, or -1. */
    selectedEmbeddedIndex: Int = -1,
    /** Selects an embedded track by index, or -1 to turn subtitles off. */
    onSelectEmbedded: (Int) -> Unit = {},
) {
    val visible = panel != PlayerPanel.NONE
    val panelFocus = remember { FocusRequester() }

    // Pull focus into the panel when it opens, so the D-pad acts on it rather than the
    // controls behind it, which stay laid out and focusable. Without this the panel could
    // be opened but never used with a remote: it is not adjacent to the controls in any
    // direction, so focus had no way to travel into it.
    //
    // Keyed on the panel, not just visibility, so switching straight from one panel to
    // another re-homes focus instead of leaving it on the row that was just replaced.
    // Retried because requestFocus reports success even when its target has no node yet,
    // which it does while the panel is still animating in.
    LaunchedEffect(panel) {
        if (!visible) return@LaunchedEffect
        repeat(PANEL_FOCUS_ATTEMPTS) {
            withFrameNanos { }
            runCatching { panelFocus.requestFocus() }
            delay(PANEL_FOCUS_RETRY_MS)
        }
    }

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
            PlayerPanelSurface(panelFocus = panelFocus) {
                when (panel) {
                    // The three axes below all end in the same place: a stream is
                    // chosen, not a track. Changing one axis carries the other two
                    // over where the new server has them, so picking a server does
                    // not silently discard a chosen resolution - see pickStream.
                    PlayerPanel.SERVER -> {
                        val current = state.selectedStream?.facets
                        val servers = state.streams.serverOptions()
                        val best = state.streams.serverBestQuality()
                        PanelList(
                            title = stringResource(R.string.player_server),
                            entries = servers,
                            selectedIndex = servers.indexOf(current?.server),
                            // Server names say nothing about what they carry, so the
                            // best resolution each one offers is shown alongside.
                            secondary = servers.map { server ->
                                best[server]?.let {
                                    stringResource(R.string.player_server_up_to, it)
                                }
                            },
                            onSelect = { index ->
                                servers.getOrNull(index)?.let { server ->
                                    pickStream(
                                        streams = state.streams,
                                        server = server,
                                        quality = current?.quality,
                                        dub = current?.dub,
                                    )?.let(onSelectStream)
                                }
                            },
                        )
                    }

                    PlayerPanel.QUALITY -> {
                        val current = state.selectedStream?.facets
                        val choices = state.streams.qualityChoices(current?.server, current?.dub)
                        // Falls back to the flat list when the labels carry no
                        // resolution to group by, so this panel is never empty.
                        if (choices.isEmpty()) {
                            PanelList(
                                title = stringResource(R.string.player_quality),
                                entries = state.streams.map { it.label },
                                selectedIndex = state.streams
                                    .indexOfFirst { it.url == state.selectedStream?.url },
                                onSelect = { index ->
                                    state.streams.getOrNull(index)?.let(onSelectStream)
                                },
                            )
                        } else {
                            PanelList(
                                title = stringResource(R.string.player_quality),
                                entries = choices.map { it.quality },
                                // Only a row playable on the current track can be the
                                // selected one, so an annotated row never shows a tick.
                                selectedIndex = choices.indexOfFirst {
                                    it.quality == current?.quality && it.requiresDub == null
                                },
                                secondary = choices.map { choice ->
                                    choice.requiresDub?.let {
                                        stringResource(R.string.player_quality_requires_dub, it)
                                    }
                                },
                                onSelect = { index ->
                                    choices.getOrNull(index)?.let { choice ->
                                        pickStream(
                                            streams = state.streams,
                                            server = current?.server,
                                            quality = choice.quality,
                                            // Follows the row: a resolution carried only
                                            // by another track has to move the audio too,
                                            // or the pick resolves back to what is already
                                            // playing and the tap does nothing.
                                            dub = choice.requiresDub ?: current?.dub,
                                        )?.let(onSelectStream)
                                    }
                                },
                            )
                        }
                    }

                    PlayerPanel.DUB -> {
                        val current = state.selectedStream?.facets
                        val dubs = state.streams.dubOptions(current?.server)
                        PanelList(
                            title = stringResource(R.string.player_dub),
                            entries = dubs,
                            selectedIndex = dubs.indexOf(current?.dub),
                            onSelect = { index ->
                                dubs.getOrNull(index)?.let { dub ->
                                    pickStream(
                                        streams = state.streams,
                                        server = current?.server,
                                        quality = current?.quality,
                                        dub = dub,
                                    )?.let(onSelectStream)
                                }
                            },
                        )
                    }

                    // Track choice and appearance in one panel: both are "the
                    // subtitle settings" as far as the user is concerned, and
                    // appearance is most often adjusted while watching something
                    // whose subtitles are hard to read.
                    PlayerPanel.SUBTITLES -> Column {
                        // One list over three sources: off, the stream's own tracks,
                        // then ours. Embedded tracks come first because they need no
                        // download and are what a file arrives with.
                        val offLabel = stringResource(R.string.player_subtitles_off)
                        val embeddedLabel = stringResource(R.string.player_subtitle_embedded)
                        PanelList(
                            title = stringResource(R.string.player_subtitles),
                            entries = listOf(offLabel) +
                                embeddedSubtitles +
                                state.subtitles.map { it.label },
                            selectedIndex = when {
                                state.selectedSubtitleIndex >= 0 ->
                                    1 + embeddedSubtitles.size + state.selectedSubtitleIndex
                                selectedEmbeddedIndex >= 0 -> 1 + selectedEmbeddedIndex
                                else -> 0
                            },
                            // Says where a track came from, so an embedded one is not
                            // mistaken for a download that failed to appear.
                            secondary = listOf(null) +
                                embeddedSubtitles.map { embeddedLabel } +
                                state.subtitles.map { null },
                            onSelect = { index ->
                                when {
                                    index == 0 -> {
                                        onSelectEmbedded(-1)
                                        onSelectSubtitle(-1)
                                    }
                                    index <= embeddedSubtitles.size -> {
                                        // Ours has to be cleared as well, or the
                                        // override for it would win over the
                                        // embedded track just chosen.
                                        onSelectSubtitle(-1)
                                        onSelectEmbedded(index - 1)
                                    }
                                    else -> {
                                        onSelectEmbedded(-1)
                                        onSelectSubtitle(index - 1 - embeddedSubtitles.size)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f, fill = false),
                        )

                        Spacer(Modifier.height(12.dp))

                        // Offered even when the source supplied tracks: the usual reason to
                        // come here is that the ones on offer are absent, wrong or out of sync.
                        PanelActionRow(
                            label = stringResource(R.string.player_subtitle_search),
                            onClick = onSearchSubtitles,
                            icon = Icons.Rounded.Search,
                        )

                        Spacer(Modifier.height(8.dp))

                        PanelActionRow(
                            label = stringResource(R.string.player_subtitle_appearance),
                            onClick = onOpenSubtitleSettings,
                        )

                        Spacer(Modifier.height(8.dp))

                        // Next to search and appearance because they are the three answers
                        // to "these subtitles are wrong": missing, ugly, or mistimed.
                        //
                        // Carries its own value, and goes amber once a correction is set.
                        // Both, because they answer different questions: the colour says
                        // "this has been changed" at a glance across the whole panel, while
                        // the number says what it was changed to without opening it.
                        //
                        // Amber rather than the accent, matching every other rating and
                        // measurement in the app, and distinct from the accent the other
                        // rows use so an active correction stands out among them.
                        PanelActionRow(
                            label = stringResource(R.string.player_subtitle_sync),
                            onClick = onOpenSubtitleSync,
                            icon = Icons.Rounded.Schedule,
                            value = formatSubtitleOffset(state.subtitleOffsetMs)
                                .takeIf { state.subtitleOffsetMs != 0L },
                            highlighted = state.subtitleOffsetMs != 0L,
                        )
                    }

                    PlayerPanel.SUBTITLE_SEARCH -> SubtitleSearchPanel(
                        search = state.subtitleSearch,
                        onApply = onApplySubtitle,
                        onRetry = onSearchSubtitles,
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

                    PlayerPanel.SUBTITLE_STYLE -> SubtitleStylePanel(
                        style = subtitleStyle,
                        onSetSize = onSetSubtitleSize,
                        onSetBackground = onSetSubtitleBackground,
                        onSetEdgeWidth = onSetSubtitleEdgeWidth,
                        onSetColor = onSetSubtitleColor,
                    )

                    PlayerPanel.SUBTITLE_SYNC -> SubtitleSyncPanel(
                        offsetMs = state.subtitleOffsetMs,
                        calibration = state.syncCalibration,
                        embedded = state.subtitleIsEmbedded,
                        onMark = onMarkSync,
                        onCancel = onCancelSync,
                        onNudge = onNudgeSubtitleOffset,
                        onReset = onResetSubtitleOffset,
                    )

                    PlayerPanel.NONE -> Unit
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlayerPanelSurface(
    panelFocus: FocusRequester,
    content: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.wb
    Box(
        modifier = Modifier
            .width(420.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .background(tokens.colors.surfaceElevated)
            // Keeps D-pad focus inside the panel. The controls behind it are still laid
            // out and focusable, and a remote has no pointer to dismiss with, so focus
            // escaping the panel would leave no way back into it. Back closes it.
            .focusRequester(panelFocus)
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .padding(24.dp),
    ) {
        content()
    }
}

/**
 * Results from an online subtitle search.
 *
 * Every state of the search draws something, because a panel that opens empty while a request
 * is in flight is indistinguishable from one that is broken. Selecting a row downloads it and
 * turns it on in one step - a user who picked from this list wants to see that subtitle, not to
 * then find it again in the track list.
 */
@Composable
private fun SubtitleSearchPanel(
    search: SubtitleSearchState,
    onApply: (SubtitleResult) -> Unit,
    onRetry: () -> Unit,
) {
    val tokens = MaterialTheme.wb

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.player_subtitle_search),
            style = MaterialTheme.typography.headlineSmall,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(Modifier.height(16.dp))

        when (search) {
            is SubtitleSearchState.Searching -> PanelNotice(
                text = stringResource(R.string.player_subtitle_searching),
                busy = true,
            )

            is SubtitleSearchState.Empty -> PanelNotice(
                text = stringResource(R.string.player_subtitle_search_empty),
                actionLabel = stringResource(R.string.player_subtitle_search_retry),
                onAction = onRetry,
            )

            is SubtitleSearchState.Unsupported -> PanelNotice(
                text = stringResource(R.string.player_subtitle_search_unsupported),
            )

            is SubtitleSearchState.Failed -> PanelNotice(
                text = stringResource(R.string.player_subtitle_search_failed),
                actionLabel = stringResource(R.string.player_subtitle_search_retry),
                onAction = onRetry,
            )

            is SubtitleSearchState.Results -> SubtitleResultList(
                results = search.results,
                downloadingId = null,
                onApply = onApply,
            )

            // The list stays on screen during a download, with a spinner on the chosen row:
            // replacing it with a bare spinner would hide what was picked, and the download is
            // brief enough that the flash of an empty panel is worse than the wait.
            is SubtitleSearchState.Downloading -> SubtitleResultList(
                results = search.previous,
                downloadingId = search.id,
                onApply = onApply,
            )

            // Applied is transient - the player closes this panel on seeing it - so there is
            // nothing to draw for either of these.
            SubtitleSearchState.Idle,
            SubtitleSearchState.Applied,
            -> Unit
        }
    }
}

/** One tappable search result. */
@Composable
private fun SubtitleResultList(
    results: List<SubtitleResult>,
    downloadingId: String?,
    onApply: (SubtitleResult) -> Unit,
) {
    val tokens = MaterialTheme.wb

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(results, key = { it.id }) { result ->
            val interaction = rememberFocusInteraction()
            val busy = result.id == downloadingId

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .adaptiveFocus(interaction, RoundedCornerShape(12.dp), scale = false)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        // Locked during a download so a second press cannot start a competing
                        // request that would finish out of order.
                        enabled = downloadingId == null,
                    ) { onApply(result) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // Download count is the only quality signal these catalogues give that
                        // is not self-reported, so it is worth the second line.
                        text = buildString {
                            append(result.languageName.ifBlank { result.language }.uppercase())
                            if (result.downloads > 0) append("  ·  ${result.downloads} ↓")
                            if (result.hearingImpaired) append("  ·  HI")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (busy) {
                    CircularProgressIndicator(
                        color = tokens.colors.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** A line of explanation, optionally with a spinner or a retry action. */
@Composable
private fun PanelNotice(
    text: String,
    busy: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.wb

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    color = tokens.colors.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
        }

        if (actionLabel != null && onAction != null) {
            PanelActionRow(
                label = actionLabel,
                onClick = onAction,
                icon = Icons.Rounded.Search,
            )
        }
    }
}

@Composable
private fun PanelList(
    title: String,
    entries: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Optional note under an entry, indexed alongside [entries].
     *
     * Used to say that a row would also change something else - a resolution
     * carried only by another audio track, for instance. Kept as a parallel list
     * rather than folded into the label so the entry text stays the thing being
     * chosen, and a null leaves the row exactly as it was.
     */
    secondary: List<String?> = emptyList(),
) {
    val tokens = MaterialTheme.wb

    // Honours the caller's modifier. It used to hardcode fillMaxSize and drop the parameter,
    // which made the subtitle panel's list take the whole drawer and push the action rows
    // beneath it off-screen - "Subtitle appearance" was unreachable whenever a track existed.
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                // fill = false so a short list takes only the height it needs and leaves room
                // for whatever the caller puts below it. A plain weight(1f) claims the entire
                // share even when there is one entry, which is what hid the action rows.
                .weight(1f, fill = false)
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(entries) { index, label ->
                val selected = index == selectedIndex
                val interaction = rememberFocusInteraction()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .adaptiveFocus(interaction, RoundedCornerShape(12.dp), scale = false)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) tokens.colors.accent else Color.Transparent)
                        .clickable(
                            interactionSource = interaction,
                            indication = LocalIndication.current,
                        ) { onSelect(index) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
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
                        )
                        secondary.getOrNull(index)?.let { note ->
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) {
                                    tokens.colors.onAccent
                                } else {
                                    tokens.colors.textMuted
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
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
                val interaction = rememberFocusInteraction()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .adaptiveFocus(interaction, RoundedCornerShape(12.dp), scale = false)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (selected) tokens.colors.accent else tokens.colors.surfaceCard,
                        )
                        .clickable(
                            interactionSource = interaction,
                            indication = LocalIndication.current,
                        ) { onSelect(episode) }
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
private fun PanelActionRow(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector = Icons.Rounded.Tune,
    /**
     * When false the row stays in place, dimmed and inert.
     *
     * Disabling beats removing for a row whose own action makes it redundant. A row
     * that vanishes under the D-pad takes the focus with it, and Compose does not
     * re-home focus when the focused node leaves the tree - the remote goes dead and
     * only Back recovers. Keeping the row keeps the focus target.
     */
    enabled: Boolean = true,
    /** Set when another row needs to hand focus here after disabling itself. */
    focusRequester: FocusRequester? = null,
    /**
     * Current value, shown at the trailing edge.
     *
     * For a row that opens a sub-panel this answers "what is it set to" without opening it -
     * otherwise the only way to know a correction is active is to go and look.
     */
    value: String? = null,
    /**
     * Tints the icon, label and value.
     *
     * Used to mark a row as carrying a non-default setting, which a label alone cannot say.
     */
    highlighted: Boolean = false,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .adaptiveFocus(interaction, RoundedCornerShape(12.dp), scale = false)
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surfaceCard)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = when {
                !enabled -> tokens.colors.textDisabled
                highlighted -> tokens.colors.warning
                else -> tokens.colors.accent
            },
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                !enabled -> tokens.colors.textDisabled
                highlighted -> tokens.colors.warning
                else -> tokens.colors.textPrimary
            },
            modifier = Modifier.weight(1f),
        )

        // Trailing, so the label stays left-aligned with the rows above it and the values
        // line up with each other down the panel.
        value?.takeIf { it.isNotBlank() }?.let { shown ->
            Text(
                text = shown,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (highlighted) tokens.colors.warning else tokens.colors.textMuted,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * Subtitle timing correction.
 *
 * Two ways to set it, because the useful one depends on what the user can tell:
 *
 *  - a measurement, when they can see the desync but not quantify it. They mark the
 *    moment a subtitle appears and the moment the line is spoken, and the gap between
 *    those positions is the correction.
 *  - a stepper, when they already know roughly how far out it is, or want to fine-tune
 *    what a measurement produced.
 *
 * Either mark may be taken first: which one comes first is itself the diagnosis
 * (subtitles early versus late), and requiring a fixed order would make the feature
 * unusable in half the cases it exists for. Once one is taken the other is the only one
 * left enabled, since two taps at the same event measure nothing.
 */
@Composable
private fun SubtitleSyncPanel(
    offsetMs: Long,
    calibration: SyncCalibration,
    /** True when the track is embedded in the stream and can only be delayed. */
    embedded: Boolean,
    onMark: (SyncMark) -> Unit,
    onCancel: () -> Unit,
    onNudge: (Long) -> Unit,
    onReset: () -> Unit,
) {
    val tokens = MaterialTheme.wb

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.player_subtitle_sync),
            style = MaterialTheme.typography.titleLarge,
            color = tokens.colors.textPrimary,
        )

        PanelSectionLabel(stringResource(R.string.player_subtitle_sync_offset))

        Text(
            text = formatSubtitleOffset(offsetMs),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = if (offsetMs == 0L) tokens.colors.textSecondary else tokens.colors.accent,
        )

        Text(
            text = stringResource(R.string.player_subtitle_sync_hint),
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
        )

        // Only for a track embedded in the stream, where it is a real constraint.
        //
        // Those have no file to parse, so the only way to move them is to hold the
        // decoder's own cues back - which delays a line but cannot surface one early. Said
        // here rather than left to be discovered by a stepper press that does nothing.
        if (embedded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tokens.colors.warning.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = tokens.colors.warning,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = stringResource(R.string.player_subtitle_sync_embedded_warning),
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.warning,
                )
            }
        }

        PanelSectionLabel(stringResource(R.string.player_subtitle_sync_measure))

        // Every row here can disable itself by being pressed, and Compose does not
        // re-home focus when the focused node stops being focusable - the remote goes
        // dead and only Back recovers. So each action names where focus should land
        // afterwards, and the request is made once rather than on a retry loop: these
        // rows are already on screen, so there is no node to wait for, and a loop would
        // fight the user for its duration.
        val subtitleFocus = remember { FocusRequester() }
        val spokenFocus = remember { FocusRequester() }
        val laterFocus = remember { FocusRequester() }

        // Requested on the next frame, not inline: the recomposition that disables the
        // row has to land first, or focus is moved and then immediately invalidated.
        var pendingFocus by remember { mutableStateOf<FocusRequester?>(null) }
        LaunchedEffect(pendingFocus) {
            val target = pendingFocus ?: return@LaunchedEffect
            withFrameNanos { }
            runCatching { target.requestFocus() }
            pendingFocus = null
        }

        // These two are the only controls in the panel that capture a moment rather than
        // set a value, so they are drawn as buttons rather than as list rows. Everything
        // else here is a choice or a nudge; these are a stopwatch, and mistaking one for a
        // menu entry is what makes the measurement hard to find.
        SyncMarkButton(
            label = stringResource(R.string.player_subtitle_sync_mark_subtitle),
            icon = Icons.Rounded.Subtitles,
            armed = calibration.firstMark == SyncMark.SUBTITLE,
            enabled = calibration.isEnabled(SyncMark.SUBTITLE),
            onClick = {
                // Arming disables this button, so focus moves to the mark still
                // outstanding; completing re-enables both, and the stepper is the
                // natural next stop.
                pendingFocus = if (calibration.isArmed) laterFocus else spokenFocus
                onMark(SyncMark.SUBTITLE)
            },
            focusRequester = subtitleFocus,
        )

        Spacer(Modifier.height(8.dp))

        SyncMarkButton(
            label = stringResource(R.string.player_subtitle_sync_mark_spoken),
            icon = Icons.Rounded.RecordVoiceOver,
            armed = calibration.firstMark == SyncMark.SPOKEN,
            enabled = calibration.isEnabled(SyncMark.SPOKEN),
            onClick = {
                pendingFocus = if (calibration.isArmed) laterFocus else subtitleFocus
                onMark(SyncMark.SPOKEN)
            },
            focusRequester = spokenFocus,
        )

        // The prompt is conditional, the button is not.
        //
        // Cancel stays in place and greys out instead of disappearing, because tapping
        // it is what makes it redundant: a row that removes itself under the D-pad takes
        // the focus with it and leaves the remote dead. Same for Reset below.
        if (calibration.isArmed) {
            Text(
                text = if (calibration.firstMark == SyncMark.SUBTITLE) {
                    stringResource(R.string.player_subtitle_sync_waiting_spoken)
                } else {
                    stringResource(R.string.player_subtitle_sync_waiting_subtitle)
                },
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.accent,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        PanelActionRow(
            label = stringResource(R.string.player_subtitle_sync_cancel),
            onClick = {
                pendingFocus = subtitleFocus
                onCancel()
            },
            icon = Icons.Rounded.Close,
            enabled = calibration.isArmed,
        )

        PanelSectionLabel(stringResource(R.string.player_subtitle_sync_adjust))

        // Disabled below zero on an embedded track, where a negative offset cannot be
        // honoured. Leaving it live would let the value go negative and the subtitles not
        // move, which is the silent failure the warning above exists to prevent.
        //
        // Still enabled while the offset is positive: stepping down from +3s to +2s is a
        // reduction of a delay, not a negative shift, and that works.
        PanelActionRow(
            label = stringResource(R.string.player_subtitle_sync_earlier),
            onClick = { onNudge(-SUBTITLE_OFFSET_STEP_MS) },
            icon = Icons.Rounded.Remove,
            enabled = !embedded || offsetMs > 0L,
        )

        PanelActionRow(
            label = stringResource(R.string.player_subtitle_sync_later),
            onClick = { onNudge(SUBTITLE_OFFSET_STEP_MS) },
            icon = Icons.Rounded.Add,
            focusRequester = laterFocus,
        )

        PanelActionRow(
            label = stringResource(R.string.player_subtitle_sync_reset),
            onClick = {
                // Resetting to zero disables this row, so focus goes to the stepper
                // above it, which is always live.
                pendingFocus = laterFocus
                onReset()
            },
            icon = Icons.Rounded.Refresh,
            enabled = offsetMs != 0L,
        )
    }
}

/**
 * A tap-to-mark button for the two-tap sync measurement.
 *
 * Deliberately unlike the panel's other controls. Everything else here selects a value from
 * a list or nudges one; these capture the instant they are pressed, and drawn as list rows
 * they read as settings rather than as a stopwatch.
 *
 * Taller, accent-outlined, with an icon and a hint line, so the pair reads as one action
 * with two halves. The armed one fills solid, which is the clearest available signal that
 * a measurement is in progress and the other button is what completes it.
 */
@Composable
private fun SyncMarkButton(
    label: String,
    icon: ImageVector,
    armed: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    val background = when {
        armed -> tokens.colors.accent
        !enabled -> tokens.colors.surface
        else -> tokens.colors.surfaceCard
    }
    val content = when {
        armed -> tokens.colors.onAccent
        !enabled -> tokens.colors.textDisabled
        else -> tokens.colors.textPrimary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .adaptiveFocus(interaction, RoundedCornerShape(14.dp), scale = false)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            // An outline only while it is actionable: on a disabled button it would suggest
            // something to press, and on the armed one the fill already carries the emphasis.
            .then(
                if (enabled && !armed) {
                    Modifier.border(
                        width = 1.dp,
                        color = tokens.colors.accent.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(14.dp),
                    )
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (armed) content else tokens.colors.accent.takeIf { enabled } ?: content,
            modifier = Modifier.size(22.dp),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = content,
            )
            Text(
                text = stringResource(R.string.player_subtitle_sync_tap_hint),
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.7f),
            )
        }

        // Only on the armed button, where it names what the fill means.
        if (armed) {
            Text(
                text = stringResource(R.string.player_subtitle_sync_marked),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = content,
            )
        }
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
private fun PanelChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    /**
     * When false the row is inert and dimmed.
     *
     * Disabled rather than hidden: the two sync marks are a pair, and removing one
     * mid-measurement would make the panel appear to lose a button.
     */
    enabled: Boolean = true,
    /** Set when something needs to move focus onto this row programmatically. */
    focusRequester: FocusRequester? = null,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .adaptiveFocus(interaction, RoundedCornerShape(10.dp), scale = false)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) tokens.colors.accent else tokens.colors.surface)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                selected -> tokens.colors.onAccent
                !enabled -> tokens.colors.textDisabled
                else -> tokens.colors.textSecondary
            },
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

/**
 * Retry budget for handing focus to a newly opened panel.
 *
 * Matches the controls' own budget: the panel slides in over several frames and has no
 * focusable node until it has composed, so a single attempt lands nowhere.
 */
private const val PANEL_FOCUS_ATTEMPTS = 12
private const val PANEL_FOCUS_RETRY_MS = 60L
