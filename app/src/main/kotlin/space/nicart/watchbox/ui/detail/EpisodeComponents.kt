package space.nicart.watchbox.ui.detail

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.domain.CastMember
import space.nicart.watchbox.domain.EpisodeItem
import space.nicart.watchbox.domain.SeasonSummary
import space.nicart.watchbox.domain.formatRuntime
import space.nicart.watchbox.ui.components.WbAsyncImage
import space.nicart.watchbox.ui.components.WbSkeletonBlock
import space.nicart.watchbox.ui.components.WbWatchedBadge

/**
 * Episode / season / cast sections.
 * Ported from NuvioMobile `features/details/components/DetailSeriesContent.kt`
 * and `DetailCastSection.kt`.
 */

/** Phone horizontal episode-card metrics (`DetailSeriesContent.kt:865-961`). */
private val EP_CARD_WIDTH = 296.dp
private val EP_CARD_HEIGHT = 184.dp
private val EP_CARD_RADIUS = 14.dp
private val EP_ITEM_SPACING = 12.dp

/** Season poster rail: 100x150dp, 8dp radius, 16dp gap. */
private val SEASON_POSTER_WIDTH = 100.dp
private val SEASON_POSTER_HEIGHT = 150.dp

/**
 * Season selector. Uses the poster rail when TMDB has season art (Nuvio's
 * default `SeasonViewMode.Posters`), otherwise falls back to text chips.
 */
@Composable
fun SeasonSelector(
    seasons: List<SeasonSummary>,
    selected: Int,
    onSelect: (Int) -> Unit,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    if (seasons.size <= 1) return
    val tokens = MaterialTheme.wb
    val hasPosters = seasons.any { it.posterUrl != null }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DetailSectionTitle(
            title = stringResource(R.string.detail_seasons),
            modifier = Modifier.padding(horizontal = horizontalPadding),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items = seasons, key = { it.season }) { season ->
                val isSelected = season.season == selected
                if (hasPosters) {
                    Column(
                        modifier = Modifier.width(SEASON_POSTER_WIDTH),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(SEASON_POSTER_WIDTH)
                                .height(SEASON_POSTER_HEIGHT)
                                .clip(RoundedCornerShape(8.dp))
                                .background(tokens.colors.surface)
                                .clickable { onSelect(season.season) },
                        ) {
                            WbAsyncImage(
                                url = season.posterUrl,
                                contentDescription = season.label,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                                fallbackLabel = season.label,
                            )
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(tokens.colors.accent.copy(alpha = 0.22f)),
                                )
                            }
                        }
                        Text(
                            text = season.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSelected) {
                                tokens.colors.textPrimary
                            } else {
                                tokens.colors.textMuted
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) tokens.colors.accent else tokens.colors.surfaceCard,
                            )
                            .clickable { onSelect(season.season) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = season.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSelected) {
                                tokens.colors.onAccent
                            } else {
                                tokens.colors.textSecondary
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Horizontal episode rail (Nuvio's default `MetaEpisodeCardStyle.Horizontal`).
 * Auto-scrolls to the episode the user would resume.
 */
@Composable
fun EpisodeRow(
    episodes: List<EpisodeItem>,
    watchedEpisodes: Set<Int>,
    currentEpisode: Int?,
    isLoading: Boolean,
    horizontalPadding: Dp,
    onPlay: (EpisodeItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(currentEpisode, episodes.size) {
        val index = episodes.indexOfFirst { it.episode == currentEpisode }
        if (index > 0) listState.animateScrollToItem(index)
    }

    if (isLoading) {
        Row(
            modifier = modifier.padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(EP_ITEM_SPACING),
        ) {
            repeat(2) {
                WbSkeletonBlock(
                    modifier = Modifier.width(EP_CARD_WIDTH).height(EP_CARD_HEIGHT),
                    cornerRadius = EP_CARD_RADIUS,
                )
            }
        }
        return
    }

    LazyRow(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(EP_ITEM_SPACING),
    ) {
        items(items = episodes, key = { "${it.season}-${it.episode}" }) { episode ->
            EpisodeCard(
                episode = episode,
                watched = episode.episode in watchedEpisodes,
                onClick = { onPlay(episode) },
            )
        }
    }
}

/**
 * One episode card: still image, 6-stop bottom scrim, code badge, ExtraBold
 * title, meta row and synopsis (`DetailSeriesContent.kt:673-840`).
 */
@Composable
private fun EpisodeCard(
    episode: EpisodeItem,
    watched: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.wb

    Box(
        modifier = Modifier
            .width(EP_CARD_WIDTH)
            .height(EP_CARD_HEIGHT)
            .clip(RoundedCornerShape(EP_CARD_RADIUS))
            .background(tokens.colors.surfaceCard.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
    ) {
        WbAsyncImage(
            url = episode.stillUrl,
            contentDescription = episode.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            fallbackLabel = episode.title,
        )

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

        WbWatchedBadge(
            visible = watched,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(alpha = 0.42f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = episode.code,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.9f),
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                text = episode.title,
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

/** Cast rail: 80dp circular avatars in 92dp columns, 16dp gaps. */
@Composable
fun CastRow(
    cast: List<CastMember>,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    if (cast.isEmpty()) return
    val tokens = MaterialTheme.wb

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DetailSectionTitle(
            title = stringResource(R.string.detail_cast),
            modifier = Modifier.padding(horizontal = horizontalPadding),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items = cast, key = { it.name + it.character }) { member ->
                Column(
                    modifier = Modifier.width(92.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(tokens.colors.surface),
                    ) {
                        WbAsyncImage(
                            url = member.photoUrl,
                            contentDescription = member.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Text(
                        text = member.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    if (member.character.isNotBlank()) {
                        Text(
                            text = member.character,
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
