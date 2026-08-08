package space.nicart.watchbox.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.domain.EpisodeEntry
import space.nicart.watchbox.ui.components.WbSkeletonBlock
import space.nicart.watchbox.ui.components.WbWatchedBadge
import java.text.DateFormat
import java.util.Date

/**
 * Episode list.
 *
 * Nuvio's default episode card is a 296x184dp thumbnail tile, but anime
 * extensions expose only `url`, `name`, `number`, `date_upload` and `scanlator`
 * — no stills and no synopses. Rendering that tile would mean a rail of empty
 * grey boxes, so this uses Nuvio's alternative `List` card style, which is
 * designed for text-only rows and keeps the same radii, spacing and type scale.
 *
 * There is also no season concept here: `getEpisodeList` returns one flat list,
 * so no season selector is rendered.
 */
private val ROW_HEIGHT = 72.dp
private val ROW_RADIUS = 16.dp
private val ROW_GAP = 10.dp

@Composable
fun EpisodeList(
    episodes: List<EpisodeEntry>,
    watchedUrls: Set<String>,
    currentUrl: String?,
    isLoading: Boolean,
    horizontalPadding: Dp,
    onPlay: (EpisodeEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isLoading) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
        ) {
            repeat(4) {
                WbSkeletonBlock(
                    modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT),
                    cornerRadius = ROW_RADIUS,
                )
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        episodes.forEach { episode ->
            EpisodeRow(
                episode = episode,
                watched = episode.url in watchedUrls,
                isCurrent = episode.url == currentUrl,
                onClick = { onPlay(episode) },
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: EpisodeEntry,
    watched: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.wb

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clip(RoundedCornerShape(ROW_RADIUS))
            .background(
                if (isCurrent) {
                    tokens.colors.accent.copy(alpha = 0.16f)
                } else {
                    tokens.colors.surfaceCard
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Number chip stands in for the missing thumbnail.
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = episode.numberLabel ?: "–",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isCurrent) tokens.colors.accent else tokens.colors.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = episode.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = if (watched) tokens.colors.textMuted else tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val meta = listOfNotNull(
                episode.dateUpload.takeIf { it > 0 }?.let { formatDate(it) },
                episode.scanlator,
            )
            if (meta.isNotEmpty()) {
                Text(
                    text = meta.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        WbWatchedBadge(visible = watched)
    }
}

/**
 * Sources report upload times in seconds or milliseconds depending on the site,
 * so a plainly-too-small value is treated as seconds.
 */
private fun formatDate(raw: Long): String? {
    val millis = if (raw < 100_000_000_000L) raw * 1000 else raw
    return runCatching {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
    }.getOrNull()
}
