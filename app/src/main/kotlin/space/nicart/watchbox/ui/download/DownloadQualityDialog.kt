package space.nicart.watchbox.ui.download

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.adaptiveFocus
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.domain.StreamOption

/** What the picker is showing. */
sealed interface DownloadPickerState {
    data object Hidden : DownloadPickerState

    /** Streams are being resolved, which takes as long as opening the player does. */
    data class Resolving(val episodeUrl: String) : DownloadPickerState

    data class Ready(
        val episodeUrl: String,
        val episodeLabel: String,
        val streams: List<StreamOption>,
    ) : DownloadPickerState

    data class Failed(val message: String) : DownloadPickerState
}

/**
 * Asks which stream to download.
 *
 * Deliberately a prompt rather than a silent preference. Sizes here span three orders of
 * magnitude - one real source label advertises a 66 GB file next to an 858 MB one - so
 * choosing on the user's behalf risks spending most of a phone's free space on a single
 * episode. The label the source supplied is shown verbatim because it carries the size,
 * codec and release that make the choice meaningful.
 *
 * Rows are not deduplicated by resolution. A server routinely lists the same height several
 * times as different releases or mirrors, and collapsing them would hide real alternatives -
 * the same reason the player's own quality panel keeps them apart.
 */
@Composable
fun DownloadQualityDialog(
    state: DownloadPickerState,
    onPick: (StreamOption) -> Unit,
    onDismiss: () -> Unit,
) {
    if (state is DownloadPickerState.Hidden) return

    val tokens = MaterialTheme.wb

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = DIALOG_MAX_WIDTH)
                .clip(RoundedCornerShape(20.dp))
                .background(tokens.colors.surfaceDialog)
                .padding(20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = null,
                    tint = tokens.colors.accent,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = stringResource(R.string.download_pick_quality),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                )
            }

            when (state) {
                is DownloadPickerState.Resolving -> {
                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            color = tokens.colors.accent,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(R.string.download_resolving),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textSecondary,
                        )
                    }
                }

                is DownloadPickerState.Failed -> {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textSecondary,
                    )
                }

                is DownloadPickerState.Ready -> {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = state.episodeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.download_pick_quality_summary),
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                    )
                    Spacer(Modifier.height(12.dp))

                    // Scrolls rather than expanding: a source can return dozens of streams,
                    // and a dialog taller than the screen cannot be dismissed on a remote.
                    LazyColumn(
                        modifier = Modifier.heightIn(max = LIST_MAX_HEIGHT),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.streams, key = { it.url }) { stream ->
                            StreamRow(stream = stream, onClick = { onPick(stream) })
                        }
                    }
                }

                DownloadPickerState.Hidden -> Unit
            }
        }
    }
}

@Composable
private fun StreamRow(stream: StreamOption, onClick: () -> Unit) {
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
    ) {
        Text(
            // Verbatim, because the source's own label is where the size and codec live and
            // reformatting it would drop exactly what makes one row differ from the next.
            text = stream.label,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val DIALOG_MAX_WIDTH = 420.dp
private val LIST_MAX_HEIGHT = 320.dp
