package space.nicart.watchbox.ui.download

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.wb
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import space.nicart.watchbox.core.ui.adaptiveFocus
import space.nicart.watchbox.core.ui.rememberFocusInteraction

/**
 * Confirms deleting a download.
 *
 * Asked because the action is destructive, immediate and expensive to undo: the file is
 * gigabytes, getting it back means downloading it again, and the button that triggers this
 * sits where "pause" sat a moment earlier - the control changes meaning as the download
 * finishes, so a press aimed at one lands on the other.
 *
 * The size is named in the prompt rather than left implicit. "Delete this download?" and
 * "Delete this download and free 3.4 GB?" are different questions, and the second is the one
 * being asked.
 */
@Composable
fun DownloadDeleteDialog(
    /** What is being deleted, or null when nothing is. */
    target: DownloadDeleteTarget?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (target == null) return

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
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = tokens.colors.textPrimary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = stringResource(
                        if (target.unfinished) {
                            R.string.download_cancel_title
                        } else {
                            R.string.download_delete_title
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = when {
                    // Partial bytes are discarded, which is worth saying: the alternative is
                    // pausing, and the difference between the two is exactly whether what has
                    // already downloaded is kept.
                    target.unfinished && target.sizeBytes > 0L -> stringResource(
                        R.string.download_cancel_body_sized,
                        target.label,
                        formatBytes(target.sizeBytes),
                    )

                    target.unfinished -> stringResource(
                        R.string.download_cancel_body,
                        target.label,
                    )

                    target.sizeBytes > 0L -> stringResource(
                        R.string.download_delete_body_sized,
                        target.label,
                        formatBytes(target.sizeBytes),
                    )

                    // Nothing measurable was written, where naming a size would read as a bug
                    // rather than as an answer.
                    else -> stringResource(R.string.download_delete_body, target.label)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                DialogButton(
                    label = stringResource(
                        if (target.unfinished) {
                            R.string.download_cancel_keep
                        } else {
                            R.string.download_delete_cancel
                        },
                    ),
                    emphasised = false,
                    onClick = onDismiss,
                )
                DialogButton(
                    label = stringResource(
                        if (target.unfinished) {
                            R.string.download_cancel_confirm
                        } else {
                            R.string.download_delete_confirm
                        },
                    ),
                    emphasised = true,
                    onClick = onConfirm,
                )
            }
        }
    }
}

/**
 * What a pending deletion is about.
 *
 * Carries the label and size so the prompt can name them, rather than the dialog reaching back
 * into a registry to look up something the caller already had.
 */
data class DownloadDeleteTarget(
    val key: String,
    val label: String,
    val sizeBytes: Long,
    /**
     * True when the download has not finished.
     *
     * Changes the question rather than only the wording: abandoning a transfer in progress and
     * deleting a file you already have are different decisions, and "Delete download?" asked of
     * something still downloading reads as though it were already complete.
     */
    val unfinished: Boolean = false,
)

@Composable
private fun DialogButton(
    label: String,
    emphasised: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Row(
        modifier = Modifier
            // Before clip, or the focus stroke is trimmed at the button's edge.
            .adaptiveFocus(interaction, RoundedCornerShape(12.dp), scale = false)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (emphasised) tokens.colors.textPrimary else tokens.colors.surface,
            )
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (emphasised) tokens.colors.background else tokens.colors.textPrimary,
        )
    }
}

private val DIALOG_MAX_WIDTH = 400.dp
