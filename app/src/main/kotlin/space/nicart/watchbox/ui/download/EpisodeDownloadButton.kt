package space.nicart.watchbox.ui.download

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.adaptiveFocus
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.data.local.DownloadState

/**
 * Download control for one episode.
 *
 * A single button that carries the whole state machine, because an episode row has no room
 * for more and the states are mutually exclusive: not downloaded, working, done, failed, or
 * present but on an unmounted card. The icon says which, and progress is drawn as a ring
 * around it rather than as a bar - a bar wide enough to read would not fit beside a title.
 *
 * The ring is the reason this is not just an [Icon]. A percentage in text is unreadable at
 * television distance, and a spinner cannot distinguish 5% from 95% on a file that takes
 * twenty minutes.
 */
@Composable
fun EpisodeDownloadButton(
    status: EpisodeDownloadStatus?,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    // A long press cancels whatever is unfinished, and the same gesture deletes a finished
    // download - so "get rid of this" is one action whatever state it is in.
    //
    // Needed because a tap cannot carry it: on an unfinished download a tap has to mean
    // pause or resume, which left no way to abandon one at all. Starting a large file by
    // mistake meant either letting it finish or leaving it paused forever.
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    // Animated so a progress report every few hundred milliseconds reads as movement rather
    // than as the ring jumping between positions.
    val target = status?.fraction ?: 0f
    val fraction by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(PROGRESS_TWEEN_MS),
        label = "downloadProgress",
    )

    val state = status?.state
    val icon = when {
        status == null -> Icons.Rounded.Download
        status.unavailable -> Icons.Rounded.CloudOff
        // Deliberately not a tick. The watched badge beside this one is a tick, and two ticks
        // on the same card meant "downloaded" and "finished watching" were indistinguishable.
        // A filled downward chevron reads as "this is here on the device" and matches the
        // outlined download arrow it replaces.
        state == DownloadState.COMPLETED -> Icons.Rounded.DownloadDone
        state == DownloadState.FAILED -> Icons.Rounded.ErrorOutline
        state == DownloadState.PAUSED -> Icons.Rounded.Download
        // A remuxing download cannot pause, so it offers cancel. Showing a pause icon that
        // silently threw the transfer away would be worse than showing what it really does.
        status.isRemuxed -> Icons.Rounded.Close
        // Queued and downloading both offer a pause, since a queued item is one the user may
        // well want to take back out of the queue.
        else -> Icons.Rounded.Pause
    }

    val description = stringResource(
        when {
            status == null -> R.string.download_action_start
            status.unavailable -> R.string.download_action_unavailable
            state == DownloadState.COMPLETED -> R.string.download_action_delete
            state == DownloadState.FAILED -> R.string.download_action_retry
            state == DownloadState.PAUSED -> R.string.download_action_resume
            status.isRemuxed -> R.string.download_action_cancel
            else -> R.string.download_action_pause
        },
    )

    val tint = when {
        status == null -> tokens.colors.textSecondary
        status.unavailable -> tokens.colors.textMuted
        state == DownloadState.COMPLETED -> tokens.colors.accent
        state == DownloadState.FAILED -> tokens.colors.textSecondary
        else -> tokens.colors.textPrimary
    }

    // Read outside drawBehind: that lambda runs in a draw scope with no composition, so it
    // cannot reach MaterialTheme itself.
    val ringColor = tokens.colors.accent

    Box(
        modifier = modifier
            .size(size)
            .adaptiveFocus(interaction, CircleShape, scale = false)
            .clip(CircleShape)
            .background(tokens.colors.background.copy(alpha = 0.45f))
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onLongClick = onDelete.takeIf { status != null },
            ) {
                when {
                    // Nothing to act on: the file is real but its volume is absent, and
                    // deleting the record would lose a download that is merely offline.
                    status?.unavailable == true -> Unit
                    status == null -> onDownload()
                    state == DownloadState.COMPLETED -> onDelete()
                    // A failure re-resolves and retries rather than restarting: the bytes
                    // already on disk are still good.
                    state == DownloadState.FAILED -> onResume()
                    state == DownloadState.PAUSED -> onResume()
                    // Cancel, not pause: there is nothing to resume from.
                    status.isRemuxed -> onDelete()
                    else -> onPause()
                }
            }
            // Drawn behind the icon and outside the clip, so the ring is not trimmed by the
            // circle it traces.
            .drawBehind {
                if (fraction <= 0f || fraction >= 1f) return@drawBehind

                val stroke = RING_STROKE.toPx()
                val inset = stroke / 2f
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = Size(this.size.width - stroke, this.size.height - stroke),
                    style = Stroke(width = stroke),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(ICON_SIZE),
        )
    }
}

private val ICON_SIZE = 20.dp
private val RING_STROKE = 2.5.dp
private const val PROGRESS_TWEEN_MS = 400
