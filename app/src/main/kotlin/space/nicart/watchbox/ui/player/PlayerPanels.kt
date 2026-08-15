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
import androidx.compose.runtime.remember
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
                        PanelActionRow(
                            label = stringResource(R.string.player_subtitle_sync),
                            onClick = onOpenSubtitleSync,
                            icon = Icons.Rounded.Schedule,
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
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .adaptiveFocus(interaction, RoundedCornerShape(12.dp), scale = false)
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surfaceCard)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
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

        PanelSectionLabel(stringResource(R.string.player_subtitle_sync_measure))

        // Focus follows the measurement onto whichever mark is still outstanding.
        //
        // On a remote the second tap is the whole point of the pair, and leaving focus on
        // the button just pressed - which is now disabled - would strand it: a disabled row
        // is not focusable, so the next directional press has to hunt for the other one.
        // Moving focus makes the sequence two presses of OK.
        val subtitleFocus = remember { FocusRequester() }
        val spokenFocus = remember { FocusRequester() }

        LaunchedEffect(calibration.firstMark) {
            // Only while a measurement is pending. On completion the panel returns to
            // having both buttons live, and stealing focus then would fight the user.
            val target = when (calibration.firstMark) {
                SyncMark.SUBTITLE -> spokenFocus
                SyncMark.SPOKEN -> subtitleFocus
                null -> return@LaunchedEffect
            }
            // Same retry shape as the panel's own initial focus: requestFocus reports
            // success even before its target has a node.
            repeat(PANEL_FOCUS_ATTEMPTS) {
                withFrameNanos { }
                runCatching { target.requestFocus() }
                delay(PANEL_FOCUS_RETRY_MS)
            }
        }

        PanelChoiceRow(
            label = stringResource(R.string.player_subtitle_sync_mark_subtitle),
            selected = calibration.firstMark == SyncMark.SUBTITLE,
            enabled = calibration.isEnabled(SyncMark.SUBTITLE),
            onClick = { onMark(SyncMark.SUBTITLE) },
            focusRequester = subtitleFocus,
        )

        PanelChoiceRow(
            label = stringResource(R.string.player_subtitle_sync_mark_spoken),
            selected = calibration.firstMark == SyncMark.SPOKEN,
            enabled = calibration.isEnabled(SyncMark.SPOKEN),
            onClick = { onMark(SyncMark.SPOKEN) },
            focusRequester = spokenFocus,
        )

        // Only while a measurement is pending: a cancel button with nothing to cancel
        // invites the user to wonder what it would undo.
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

            PanelActionRow(
                label = stringResource(R.string.player_subtitle_sync_cancel),
                onClick = onCancel,
                icon = Icons.Rounded.Close,
            )
        }

        PanelSectionLabel(stringResource(R.string.player_subtitle_sync_adjust))

        PanelActionRow(
            label = stringResource(R.string.player_subtitle_sync_earlier),
            onClick = { onNudge(-SUBTITLE_OFFSET_STEP_MS) },
            icon = Icons.Rounded.Remove,
        )

        PanelActionRow(
            label = stringResource(R.string.player_subtitle_sync_later),
            onClick = { onNudge(SUBTITLE_OFFSET_STEP_MS) },
            icon = Icons.Rounded.Add,
        )

        if (offsetMs != 0L) {
            PanelActionRow(
                label = stringResource(R.string.player_subtitle_sync_reset),
                onClick = onReset,
                icon = Icons.Rounded.Refresh,
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
