package space.nicart.watchbox.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.data.local.DownloadState
import space.nicart.watchbox.domain.EpisodeEntry
import space.nicart.watchbox.ui.components.EPISODE_NUMBER_GAP
import space.nicart.watchbox.ui.components.NumberedEpisodeButton
import space.nicart.watchbox.ui.components.numberedEpisodeColumns
import space.nicart.watchbox.ui.download.EpisodeDownloadStatus

/**
 * All short-drama episodes, laid out without a nested vertical scroller or range selector.
 * This keeps the detail page's scroll and D-pad navigation in charge.
 */
@Composable
internal fun ShortReelsEpisodeGrid(
    episodes: List<EpisodeEntry>,
    firstIndex: Int,
    watchedUrls: Set<String>,
    currentUrl: String?,
    horizontalPadding: Dp,
    onPlay: (EpisodeEntry) -> Unit,
    downloadStatus: Map<String, EpisodeDownloadStatus>,
    onDownload: ((EpisodeEntry) -> Unit)?,
    onPauseDownload: ((EpisodeEntry) -> Unit)?,
    onResumeDownload: ((EpisodeEntry) -> Unit)?,
    onDeleteDownload: ((EpisodeEntry) -> Unit)?,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding)) {
        val columns = numberedEpisodeColumns(maxWidth)
        val rows = remember(episodes, columns) { episodes.chunked(columns) }

        Column(verticalArrangement = Arrangement.spacedBy(EPISODE_NUMBER_GAP)) {
            rows.forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(EPISODE_NUMBER_GAP),
                ) {
                    row.forEachIndexed { columnIndex, episode ->
                        key(episode.url) {
                            ShortReelsEpisodeCell(
                                episode = episode,
                                index = firstIndex + rowIndex * columns + columnIndex,
                                watched = episode.url in watchedUrls,
                                isCurrent = episode.url == currentUrl,
                                onPlay = { onPlay(episode) },
                                status = downloadStatus[episode.url],
                                onDownload = onDownload?.let { { it(episode) } },
                                onPauseDownload = onPauseDownload?.let { { it(episode) } },
                                onResumeDownload = onResumeDownload?.let { { it(episode) } },
                                onDeleteDownload = onDeleteDownload?.let { { it(episode) } },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (onDownload != null && episodes.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.short_reels_episode_download_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.wb.colors.textMuted,
                )
            }
        }
    }
}

/** Downloads remain available on hold, without adding icons or details to the number tiles. */
@Composable
private fun ShortReelsEpisodeCell(
    episode: EpisodeEntry,
    index: Int,
    watched: Boolean,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    status: EpisodeDownloadStatus?,
    onDownload: (() -> Unit)?,
    onPauseDownload: (() -> Unit)?,
    onResumeDownload: (() -> Unit)?,
    onDeleteDownload: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var optionsOpen by remember(episode.url) { mutableStateOf(false) }

    Box(modifier = modifier) {
        NumberedEpisodeButton(
            number = episode.numberLabelOrIndex(index),
            watched = watched,
            isCurrent = isCurrent,
            onClick = onPlay,
            onLongClick = if (onDownload != null) ({
                when {
                    status == null -> onDownload()
                    !status.unavailable &&
                        status.state in listOf(DownloadState.FAILED, DownloadState.PAUSED) ->
                        onResumeDownload?.invoke()
                    else -> optionsOpen = true
                }
            }) else null,
            onLongClickLabel = if (status == null) stringResource(R.string.download_action_start) else null,
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = optionsOpen,
            onDismissRequest = { optionsOpen = false },
            containerColor = MaterialTheme.wb.colors.surfaceCard,
        ) {
            val action = when {
                status == null -> R.string.download_action_start
                status.unavailable -> R.string.download_action_unavailable
                status.state == DownloadState.COMPLETED -> R.string.download_action_delete
                status.state == DownloadState.FAILED -> R.string.download_action_retry
                status.state == DownloadState.PAUSED -> R.string.download_action_resume
                status.isRemuxed -> R.string.download_action_cancel
                else -> R.string.download_action_pause
            }
            DropdownMenuItem(
                text = { Text(stringResource(action)) },
                enabled = status?.unavailable != true,
                onClick = {
                    optionsOpen = false
                    when {
                        status == null -> onDownload?.invoke()
                        status.state == DownloadState.COMPLETED -> onDeleteDownload?.invoke()
                        status.state == DownloadState.FAILED || status.state == DownloadState.PAUSED ->
                            onResumeDownload?.invoke()
                        status.isRemuxed -> onDeleteDownload?.invoke()
                        else -> onPauseDownload?.invoke()
                    }
                },
            )
            if (status != null && !status.unavailable &&
                status.state != DownloadState.COMPLETED && !status.isRemuxed &&
                onDeleteDownload != null
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.download_action_cancel)) },
                    onClick = {
                        optionsOpen = false
                        onDeleteDownload()
                    },
                )
            }
        }
    }
}
