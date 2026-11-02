package space.nicart.watchbox.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.download.ShortReelsDownloadState

@Composable
internal fun ShortReelsDownloadBar(
    episodeCount: Int,
    state: ShortReelsDownloadState,
    onDownloadAll: () -> Unit,
    onStopAdding: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onDownloadAll,
                enabled = !state.isRunning && episodeCount > 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = tokens.colors.accent,
                    contentColor = tokens.colors.onAccent,
                ),
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.download_short_reels_series, episodeCount))
            }
            if (state.isRunning) {
                TextButton(onClick = onStopAdding) {
                    Text(stringResource(R.string.download_short_reels_stop_adding))
                }
            }
        }
        Text(
            text = stringResource(R.string.download_short_reels_subtitles),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textSecondary,
        )
        if (state.isRunning) {
            LinearProgressIndicator(
                progress = { if (state.total > 0) state.processed.toFloat() / state.total else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = tokens.colors.accent,
            )
            Text(
                text = stringResource(R.string.download_short_reels_preparing, state.processed, state.total),
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textSecondary,
            )
        } else if (state.total > 0) {
            Text(
                text = stringResource(
                    R.string.download_short_reels_summary, state.queued, state.skipped, state.failed,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textSecondary,
            )
            if (state.cancelled > 0) {
                Text(
                    text = stringResource(R.string.download_short_reels_stopped),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }
        state.lastError?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.danger)
        }
    }
}
