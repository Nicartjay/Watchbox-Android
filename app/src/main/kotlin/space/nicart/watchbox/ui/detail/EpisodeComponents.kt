package space.nicart.watchbox.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import space.nicart.watchbox.core.ui.adaptiveFocus
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import space.nicart.watchbox.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.domain.EpisodeEntry
import space.nicart.watchbox.domain.formatRuntime
import space.nicart.watchbox.ui.components.WbAsyncImage
import space.nicart.watchbox.ui.components.WbSkeletonBlock
import space.nicart.watchbox.ui.components.WbWatchedBadge
import java.text.DateFormat
import java.util.Date
import androidx.compose.runtime.mutableIntStateOf
import space.nicart.watchbox.ui.download.EpisodeDownloadButton
import space.nicart.watchbox.ui.download.EpisodeDownloadStatus

/**
 * Episode list.
 *
 * Two presentations, chosen by whether TMDB stills were found:
 *
 *  * **Horizontal thumbnail rail** — Nuvio's default `MetaEpisodeCardStyle`.
 *    296x184dp cards, 14dp radius, 12dp gaps, a 6-stop bottom scrim, an
 *    episode-code badge and an ExtraBold title.
 *  * **Text rows** — Nuvio's `List` style, used when no stills exist. Rendering
 *    the thumbnail rail without images would just be a row of grey boxes.
 *
 * `getEpisodeList` returns one flat list with no season field, so the season is read
 * out of the episode name. When more than one is found a selector is shown and only
 * the chosen season's episodes are listed - otherwise a multi-season show presents
 * every season's E1 in a single run, which reads as duplicates.
 */
private val CARD_WIDTH = 296.dp
private val CARD_HEIGHT = 184.dp
private val CARD_RADIUS = 14.dp
private val CARD_GAP = 12.dp

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
    /**
     * Per-episode download state, keyed by episode URL.
     *
     * Empty disables the control entirely, which is what the phone's own reviews-and-extras
     * callers want - they render an episode list without a download surface.
     */
    downloadStatus: Map<String, EpisodeDownloadStatus> = emptyMap(),
    onDownload: ((EpisodeEntry) -> Unit)? = null,
    onPauseDownload: ((EpisodeEntry) -> Unit)? = null,
    onResumeDownload: ((EpisodeEntry) -> Unit)? = null,
    onDeleteDownload: ((EpisodeEntry) -> Unit)? = null,
) {
    if (isLoading) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
        ) {
            repeat(2) {
                WbSkeletonBlock(
                    modifier = Modifier.width(CARD_WIDTH).height(CARD_HEIGHT),
                    cornerRadius = CARD_RADIUS,
                )
            }
        }
        return
    }

    val seasons = remember(episodes) {
        episodes.mapNotNull { it.season }.distinct().sorted()
    }

    // Defaults to the season holding the episode in progress, so resuming a show does
    // not open on season 1 when the user is midway through season 3.
    var selectedSeason by remember(episodes, currentUrl) {
        val current = episodes.firstOrNull { it.url == currentUrl }?.season
        mutableStateOf(current ?: seasons.firstOrNull())
    }

    val inSeason = remember(episodes, selectedSeason, seasons) {
        if (seasons.size <= 1 || selectedSeason == null) {
            episodes
        } else {
            episodes.filter { it.season == selectedSeason }
        }
    }

    // Blocks of fifty, computed from the selected season rather than the whole show.
    //
    // This has to happen after the season filter, not before it. Ranges built from every
    // season's episodes combined do not line up with what a season contains: on a show with
    // 20-episode seasons, a "51-100" chip described episodes that were never in the list the
    // filter produced, so selecting it showed nothing.
    val ranges = remember(inSeason.size) { episodeRanges(inSeason.size) }

    // Reset when the season changes: block 3 of season 1 has no meaning in a season with one
    // block, and an index left pointing past the end would be clamped to a block the user
    // did not choose.
    var rangeIndex by remember(selectedSeason, inSeason.size) { mutableIntStateOf(0) }

    val shown = remember(inSeason, ranges, rangeIndex) {
        if (ranges.isEmpty()) {
            inSeason
        } else {
            val range = ranges[rangeIndex.coerceIn(0, ranges.lastIndex)]
            inSeason.subList(range.fromIndex, range.toIndex + 1)
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Only when there is a choice to make: one season needs no selector, and none
        // means the source numbers straight through.
        if (seasons.size > 1) {
            SeasonSelector(
                seasons = seasons,
                selected = selectedSeason ?: seasons.first(),
                onSelect = { selectedSeason = it },
                horizontalPadding = horizontalPadding,
            )
        }

        // Below the season selector, because the blocks describe that season's episodes.
        EpisodeRangeRow(
            ranges = ranges,
            selectedIndex = rangeIndex.coerceIn(0, maxOf(ranges.lastIndex, 0)),
            onSelect = { rangeIndex = it },
            horizontalPadding = horizontalPadding,
        )

        val hasStills = shown.any { it.stillUrl != null }

        if (hasStills) {
            EpisodeThumbnailRow(
                episodes = shown,
                watchedUrls = watchedUrls,
                currentUrl = currentUrl,
                horizontalPadding = horizontalPadding,
                onPlay = onPlay,
                downloadStatus = downloadStatus,
                onDownload = onDownload,
                onPauseDownload = onPauseDownload,
                onResumeDownload = onResumeDownload,
                onDeleteDownload = onDeleteDownload,
            )
        } else {
            EpisodeTextList(
                episodes = shown,
                watchedUrls = watchedUrls,
                currentUrl = currentUrl,
                horizontalPadding = horizontalPadding,
                onPlay = onPlay,
                downloadStatus = downloadStatus,
                onDownload = onDownload,
                onPauseDownload = onPauseDownload,
                onResumeDownload = onResumeDownload,
                onDeleteDownload = onDeleteDownload,
            )
        }
    }
}

/**
 * Season dropdown.
 *
 * A dropdown rather than a row of chips: a long-running show would otherwise need a
 * horizontally scrolling strip of seasons, which on a remote means pressing through
 * every season to reach the last.
 *
 * The menu is anchored to a focusable button so a D-pad can open it, and each entry is
 * a focus target in its own right.
 */
@Composable
private fun SeasonSelector(
    seasons: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    horizontalPadding: Dp,
) {
    val tokens = MaterialTheme.wb
    var expanded by remember { mutableStateOf(false) }
    val interaction = rememberFocusInteraction()

    Box(modifier = Modifier.padding(horizontal = horizontalPadding)) {
        Row(
            modifier = Modifier
                .adaptiveFocus(interaction, RoundedCornerShape(12.dp), scale = false)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.colors.surfaceCard)
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClick = { expanded = true },
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.detail_season, selected),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
            )
            Icon(
                imageVector = Icons.Rounded.ArrowDropDown,
                contentDescription = null,
                tint = tokens.colors.textSecondary,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = tokens.colors.surfaceCard,
        ) {
            seasons.forEach { season ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.detail_season, season),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (season == selected) {
                                tokens.colors.accent
                            } else {
                                tokens.colors.textPrimary
                            },
                        )
                    },
                    onClick = {
                        onSelect(season)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Nuvio's default horizontal thumbnail rail. */
@Composable
private fun EpisodeThumbnailRow(
    episodes: List<EpisodeEntry>,
    watchedUrls: Set<String>,
    currentUrl: String?,
    horizontalPadding: Dp,
    onPlay: (EpisodeEntry) -> Unit,
    modifier: Modifier = Modifier,
    downloadStatus: Map<String, EpisodeDownloadStatus> = emptyMap(),
    onDownload: ((EpisodeEntry) -> Unit)? = null,
    onPauseDownload: ((EpisodeEntry) -> Unit)? = null,
    onResumeDownload: ((EpisodeEntry) -> Unit)? = null,
    onDeleteDownload: ((EpisodeEntry) -> Unit)? = null,
) {
    val listState = rememberLazyListState()

    // Scroll to whatever the user would resume, so a long series does not always
    // open on episode 1.
    LaunchedEffect(currentUrl, episodes.size) {
        val index = episodes.indexOfFirst { it.url == currentUrl }
        if (index > 0) listState.animateScrollToItem(index)
    }

    LazyRow(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
    ) {
        items(items = episodes, key = { it.url }) { episode ->
            EpisodeThumbnailCard(
                episode = episode,
                watched = episode.url in watchedUrls,
                isCurrent = episode.url == currentUrl,
                onClick = { onPlay(episode) },
                downloadStatus = downloadStatus[episode.url],
                onDownload = onDownload?.let { { it(episode) } },
                onPauseDownload = onPauseDownload?.let { { it(episode) } },
                onResumeDownload = onResumeDownload?.let { { it(episode) } },
                onDeleteDownload = onDeleteDownload?.let { { it(episode) } },
            )
        }
    }
}

@Composable
private fun EpisodeThumbnailCard(
    episode: EpisodeEntry,
    watched: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
    downloadStatus: EpisodeDownloadStatus? = null,
    onDownload: (() -> Unit)? = null,
    onPauseDownload: (() -> Unit)? = null,
    onResumeDownload: (() -> Unit)? = null,
    onDeleteDownload: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Box(
        modifier = Modifier
            .width(CARD_WIDTH)
            .height(CARD_HEIGHT)
            .adaptiveFocus(interaction, RoundedCornerShape(CARD_RADIUS), scale = false)
            .clip(RoundedCornerShape(CARD_RADIUS))
            .background(tokens.colors.surfaceCard.copy(alpha = 0.45f))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
    ) {
        WbAsyncImage(
            url = episode.stillUrl,
            contentDescription = episode.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            fallbackLabel = episode.displayName,
        )

        // Nuvio's 6-stop scrim: transparent until 42%, then ramping to 92%.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        0.42f to Color.Transparent,
                        0.56f to Color.Black.copy(alpha = 0.20f),
                        0.70f to Color.Black.copy(alpha = 0.45f),
                        0.84f to Color.Black.copy(alpha = 0.68f),
                        1.00f to Color.Black.copy(alpha = 0.92f),
                    ),
                ),
        )

        if (isCurrent) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(tokens.colors.accent.copy(alpha = 0.12f)),
            )
        }

        WbWatchedBadge(
            visible = watched,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        )

        // Bottom right: the watched badge holds the top right, and the title block runs
        // along the bottom left, so this is the one free corner of the card.
        if (onDownload != null) {
            EpisodeDownloadButton(
                status = downloadStatus,
                onDownload = onDownload,
                onPause = onPauseDownload ?: {},
                onResume = onResumeDownload ?: {},
                onDelete = onDeleteDownload ?: {},
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            episode.code.takeIf { it.isNotBlank() }?.let { code ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.Black.copy(alpha = 0.42f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = code,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Text(
                text = episode.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val meta = listOfNotNull(
                episode.airDate?.takeIf { it.isNotBlank() },
                episode.runtimeMinutes?.takeIf { it > 0 }?.let(::formatRuntime),
                episode.rating.takeIf { it > 0.0 }?.let { "%.1f".format(it) },
            )
            if (meta.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    meta.forEach {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.75f),
                        )
                    }
                }
            }

            if (episode.overview.isNotBlank()) {
                Text(
                    text = episode.overview,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Nuvio's `List` card style, for sources with no TMDB match. */
@Composable
private fun EpisodeTextList(
    episodes: List<EpisodeEntry>,
    watchedUrls: Set<String>,
    currentUrl: String?,
    horizontalPadding: Dp,
    onPlay: (EpisodeEntry) -> Unit,
    modifier: Modifier = Modifier,
    downloadStatus: Map<String, EpisodeDownloadStatus> = emptyMap(),
    onDownload: ((EpisodeEntry) -> Unit)? = null,
    onPauseDownload: ((EpisodeEntry) -> Unit)? = null,
    onResumeDownload: ((EpisodeEntry) -> Unit)? = null,
    onDeleteDownload: ((EpisodeEntry) -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        episodes.forEach { episode ->
            EpisodeTextRow(
                episode = episode,
                watched = episode.url in watchedUrls,
                isCurrent = episode.url == currentUrl,
                onClick = { onPlay(episode) },
                downloadStatus = downloadStatus[episode.url],
                onDownload = onDownload?.let { { it(episode) } },
                onPauseDownload = onPauseDownload?.let { { it(episode) } },
                onResumeDownload = onResumeDownload?.let { { it(episode) } },
                onDeleteDownload = onDeleteDownload?.let { { it(episode) } },
            )
        }
    }
}

@Composable
private fun EpisodeTextRow(
    episode: EpisodeEntry,
    watched: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
    downloadStatus: EpisodeDownloadStatus? = null,
    onDownload: (() -> Unit)? = null,
    onPauseDownload: (() -> Unit)? = null,
    onResumeDownload: (() -> Unit)? = null,
    onDeleteDownload: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

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
            .adaptiveFocus(interaction, RoundedCornerShape(ROW_RADIUS), scale = false)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
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
                episode.dateUpload.takeIf { it > 0 }?.let(::formatUploadDate),
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

        // Ahead of the watched badge, which is a status rather than a control and reads
        // better at the far edge.
        if (onDownload != null) {
            EpisodeDownloadButton(
                status = downloadStatus,
                onDownload = onDownload,
                onPause = onPauseDownload ?: {},
                onResume = onResumeDownload ?: {},
                onDelete = onDeleteDownload ?: {},
            )
        }

        WbWatchedBadge(visible = watched)
    }
}

/**
 * Sources report upload times in seconds or milliseconds depending on the site,
 * so a plainly-too-small value is treated as seconds.
 */
private fun formatUploadDate(raw: Long): String? {
    val millis = if (raw < 100_000_000_000L) raw * 1000 else raw
    return runCatching {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
    }.getOrNull()
}
