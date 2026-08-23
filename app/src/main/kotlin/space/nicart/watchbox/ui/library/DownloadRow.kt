package space.nicart.watchbox.ui.library

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.adaptiveFocus
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.data.local.DownloadEntry
import space.nicart.watchbox.data.local.DownloadState
import space.nicart.watchbox.ui.components.WbAsyncImage
import space.nicart.watchbox.ui.download.EpisodeDownloadButton
import space.nicart.watchbox.ui.download.EpisodeDownloadStatus
import space.nicart.watchbox.ui.download.formatBytes

/**
 * One downloaded episode.
 *
 * A row rather than a poster, unlike the rest of the Library. A download has a state, a size
 * and a control that a poster tile cannot carry - a tile gets one progress bar and no room
 * for "Paused · 1.2 GB of 3.4 GB" or a pause button. The poster is still shown, small, to
 * keep the tab recognisably part of the same screen.
 */
@Composable
internal fun DownloadRow(
    entry: DownloadEntry,
    status: EpisodeDownloadStatus,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    // Only a finished download on mounted storage can be opened. Anything else has no file
    // to play, and a row that looks tappable but does nothing is worse than one that does
    // not invite the press.
    val playable = entry.isComplete && !status.unavailable

    Row(
        modifier = modifier
            .fillMaxWidth()
            .adaptiveFocus(interaction, RoundedCornerShape(14.dp), scale = false)
            .clip(RoundedCornerShape(14.dp))
            .background(tokens.colors.surfaceCard)
            .then(
                if (playable) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        onClick = onPlay,
                    )
                } else {
                    Modifier
                },
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WbAsyncImage(
            url = entry.posterUrl,
            contentDescription = entry.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(POSTER_WIDTH)
                .height(POSTER_HEIGHT)
                .clip(RoundedCornerShape(8.dp)),
            fallbackLabel = entry.title,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = entry.episodeName.ifBlank {
                    entry.episodeNumber.takeIf { it >= 0f }?.let { "Episode ${it.toInt()}" }
                        ?: entry.streamLabel
                },
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = statusLine(entry, status),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Shown for anything unfinished, including a paused download: the bar is where
            // "how far did it get" is read, and that question outlives the transfer stopping.
            // A finished row has nothing to plot, and a bar pinned at 100% is noise.
            if (!entry.isComplete && !status.unavailable) {
                Spacer(Modifier.height(5.dp))
                LinearProgressIndicator(
                    progress = { status.fraction },
                    color = if (entry.state == DownloadState.FAILED) {
                        tokens.colors.textMuted
                    } else {
                        tokens.colors.accent
                    },
                    trackColor = tokens.colors.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
            }
        }

        EpisodeDownloadButton(
            status = status,
            // Never null on this screen: a row exists only because a download does, so
            // there is nothing to start.
            onDownload = onResume,
            onPause = onPause,
            onResume = onResume,
            onDelete = onDelete,
        )
    }
}

/**
 * `47% · 1.2 GB of 2.6 GB`, `Paused · 1.2 GB`, `On removed storage`, `3.4 GB`.
 *
 * The pair is shown where a total is known and a percentage where it is not, because only a
 * progressive file declares its length: a segmented download has no total until it finishes,
 * and "1.2 GB of 0 B" is worse than no denominator at all.
 */
@Composable
private fun statusLine(entry: DownloadEntry, status: EpisodeDownloadStatus): String {
    if (status.unavailable) return stringResource(R.string.download_state_unavailable)

    val done = formatBytes(status.sizeBytes)
    val percent = (status.fraction * 100).toInt()

    return when (entry.state) {
        DownloadState.QUEUED -> stringResource(R.string.download_state_queued)

        DownloadState.DOWNLOADING -> if (status.totalBytes > 0L) {
            stringResource(
                R.string.download_state_downloading_of,
                percent,
                done,
                formatBytes(status.totalBytes),
            )
        } else {
            stringResource(R.string.download_state_downloading, percent, done)
        }

        DownloadState.PAUSED -> stringResource(R.string.download_state_paused, done)
        DownloadState.FAILED -> stringResource(R.string.download_state_failed)
        DownloadState.COMPLETED -> done
    }
}

private val POSTER_WIDTH = 46.dp
private val POSTER_HEIGHT = 66.dp
