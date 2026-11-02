package space.nicart.watchbox.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.adaptiveFocus
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.wb

internal val EPISODE_NUMBER_MIN_SIZE = 56.dp
internal val EPISODE_NUMBER_GAP = 8.dp

/** Includes the gaps, so a narrow phone never squeezes below a usable touch target. */
internal fun numberedEpisodeColumns(width: Dp): Int =
    ((width + EPISODE_NUMBER_GAP) / (EPISODE_NUMBER_MIN_SIZE + EPISODE_NUMBER_GAP))
        .toInt().coerceAtLeast(1)

/** A number-only square, shared by the short-drama detail page and player picker. */
@Composable
internal fun NumberedEpisodeButton(
    number: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    watched: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()
    val shape = RoundedCornerShape(12.dp)
    val episodeLabel = stringResource(R.string.short_reels_episode_number, number)
    val watchedLabel = stringResource(R.string.short_reels_episode_watched)
    val downloadLabel = stringResource(R.string.short_reels_episode_download_options)

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .adaptiveFocus(interaction, shape, scale = false)
            .clip(shape)
            .background(if (isCurrent) tokens.colors.accent else tokens.colors.surfaceCard)
            .border(
                width = 1.dp,
                color = if (watched) tokens.colors.accent.copy(alpha = 0.6f)
                    else tokens.colors.textMuted.copy(alpha = 0.2f),
                shape = shape,
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onLongClick = onLongClick,
                onLongClickLabel = (onLongClickLabel ?: downloadLabel).takeIf { onLongClick != null },
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = episodeLabel
                selected = isCurrent
                if (watched) stateDescription = watchedLabel
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = when {
                isCurrent -> tokens.colors.onAccent
                watched -> tokens.colors.textMuted
                else -> tokens.colors.textPrimary
            },
            maxLines = 1,
        )
    }
}
