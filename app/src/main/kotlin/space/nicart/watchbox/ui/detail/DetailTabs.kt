package space.nicart.watchbox.ui.detail

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.adaptiveFocus
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.data.remote.TmdbVideo
import space.nicart.watchbox.ui.components.WbAsyncImage
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.rounded.Star
import space.nicart.watchbox.data.remote.ProviderKind
import space.nicart.watchbox.data.remote.TmdbExtras
import space.nicart.watchbox.data.remote.TmdbReview
import androidx.compose.foundation.layout.FlowRow
import space.nicart.watchbox.data.remote.TmdbProvider

/**
 * Which list the detail page is showing beneath the synopsis.
 *
 * A tab strip rather than two stacked sections: trailers are a browsing activity and
 * episodes are the reason the page exists, so stacking them would push the episode list
 * below a rail most people scroll straight past.
 */
enum class DetailTab { EPISODES, VIDEOS }

/**
 * One block of episodes, for titles long enough that a single list is unusable.
 *
 * One Piece runs past a thousand episodes; a flat list there is a scroll with no landmarks.
 * Ranges give the list a coarse index without a search box.
 */
data class EpisodeRange(val label: String, val fromIndex: Int, val toIndex: Int)

/**
 * Splits [count] episodes into blocks of [size], or returns empty when it is not worth it.
 *
 * Deliberately returns nothing at or below the threshold: a "1-50" chip on a 24-episode
 * season is a control that does nothing, and the absence of chips is itself the signal that
 * the whole list is already on screen.
 *
 * Labels are one-based because episode numbering is, even though the indices are not.
 */
fun episodeRanges(count: Int, size: Int = EPISODE_RANGE_SIZE): List<EpisodeRange> {
    if (count <= size) return emptyList()

    return (0 until count step size).map { start ->
        val end = (start + size - 1).coerceAtMost(count - 1)
        EpisodeRange(
            label = "${start + 1}-${end + 1}",
            fromIndex = start,
            toIndex = end,
        )
    }
}

/** How many episodes go in one block, and the threshold for offering blocks at all. */
const val EPISODE_RANGE_SIZE = 50

/**
 * Tab strip for the episode and video lists.
 *
 * Videos are omitted rather than shown empty: most source-only titles have no TMDB match,
 * so an always-present tab would usually lead nowhere.
 */
@Composable
fun DetailTabRow(
    selected: DetailTab,
    showVideos: Boolean,
    videoCount: Int,
    onSelect: (DetailTab) -> Unit,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DetailTabChip(
            label = stringResource(R.string.detail_episodes),
            selected = selected == DetailTab.EPISODES,
            onClick = { onSelect(DetailTab.EPISODES) },
        )

        if (showVideos) {
            DetailTabChip(
                label = stringResource(R.string.detail_videos_count, videoCount),
                selected = selected == DetailTab.VIDEOS,
                onClick = { onSelect(DetailTab.VIDEOS) },
            )
        }
    }
}

@Composable
private fun DetailTabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Box(
        modifier = Modifier
            .adaptiveFocus(interaction, RoundedCornerShape(40.dp), scale = false)
            .clip(RoundedCornerShape(40.dp))
            .background(if (selected) tokens.colors.textPrimary else tokens.colors.surfaceCard)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) tokens.colors.background else tokens.colors.textSecondary,
        )
    }
}

/** Range chips, shown only when [ranges] is non-empty. */
@Composable
fun EpisodeRangeRow(
    ranges: List<EpisodeRange>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    if (ranges.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ranges.forEachIndexed { index, range ->
            DetailTabChip(
                label = range.label,
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
            )
        }
    }
}

/**
 * Trailer rail.
 *
 * Every TMDB video is a YouTube link and the payload carries no direct stream, so tapping
 * one hands off to whatever plays YouTube on the device. Media3 cannot play a watch page,
 * and the app carries no WebView.
 *
 * Thumbnails come from YouTube's own image host rather than TMDB, which publishes none for
 * videos - so they cost no API key and no extra request.
 */
@Composable
fun VideoRail(
    videos: List<TmdbVideo>,
    onOpen: (TmdbVideo) -> Unit,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(videos.size) { index ->
            VideoCard(video = videos[index], onClick = { onOpen(videos[index]) })
        }
    }
}

@Composable
private fun VideoCard(video: TmdbVideo, onClick: () -> Unit) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Column(
        modifier = Modifier
            .width(VIDEO_CARD_WIDTH)
            .adaptiveFocus(interaction, RoundedCornerShape(12.dp), scale = false)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.colors.surfaceCard),
        ) {
            WbAsyncImage(
                url = video.thumbnailUrl,
                contentDescription = video.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            // A scrim under the play badge, so a bright frame does not swallow it.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.4f to tokens.colors.background.copy(alpha = 0f),
                            1f to tokens.colors.background.copy(alpha = 0.55f),
                        ),
                    ),
            )

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(tokens.colors.textPrimary.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = tokens.colors.background,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = video.name,
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = video.type,
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
            maxLines = 1,
        )
    }
}

/** Wide enough for a legible 16:9 thumbnail without dominating the page. */
private val VIDEO_CARD_WIDTH = 240.dp

/**
 * Where a title can legally be watched, in the viewer's own country.
 *
 * Country-specific by necessity: TMDB carries 129 of them and the answers differ
 * substantially - one service in PH against seven in US for the same title - so showing
 * another region's list would misrepresent what is actually available.
 *
 * Kinds are grouped rather than mixed, because they are not interchangeable: a subscription
 * title is watchable now, one under Buy is not.
 */
@Composable
fun ProviderSection(
    extras: TmdbExtras,
    isTablet: Boolean,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb

    // Two columns, split by what the viewer can do rather than by TMDB's five buckets.
    //
    // Watch is anything already available - a subscription or a free ad-supported tier -
    // and Buy is anything that costs money per title, rent or purchase. That is the
    // distinction that changes the decision; "flatrate versus ads" does not.
    val watch = extras.providers.filter {
        it.kind == ProviderKind.STREAM || it.kind == ProviderKind.FREE
    }
    val buy = extras.providers.filter {
        it.kind == ProviderKind.RENT || it.kind == ProviderKind.BUY
    }

    Column(modifier = modifier.fillMaxWidth()) {
        DetailSectionTitle(
            title = stringResource(R.string.detail_where_to_watch),
            isTablet = isTablet,
            modifier = Modifier.padding(horizontal = horizontalPadding),
        )

        Text(
            text = stringResource(R.string.detail_provider_country, extras.providerCountry),
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
            modifier = Modifier
                .padding(horizontal = horizontalPadding)
                .padding(top = 2.dp, bottom = 12.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Both columns are always laid out when either has content, so the two do not
            // reflow into different widths depending on what a title happens to offer.
            ProviderColumn(
                title = stringResource(R.string.provider_column_watch),
                providers = watch,
                modifier = Modifier.weight(1f),
            )
            ProviderColumn(
                title = stringResource(R.string.provider_column_buy),
                providers = buy,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One column of provider logos.
 *
 * Renders its heading even when empty, with a dash in place of the logos: an absent column
 * would leave the other one stretched across the full width, and "nothing here" is itself
 * the answer to whether a title can be streamed.
 */
@Composable
private fun ProviderColumn(
    title: String,
    providers: List<TmdbProvider>,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(tokens.colors.surfaceCard)
            .padding(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textSecondary,
        )

        Spacer(Modifier.height(10.dp))

        if (providers.isEmpty()) {
            Text(
                text = stringResource(R.string.provider_none),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
            )
            return@Column
        }

        // Wraps rather than scrolls: a column is half the screen wide, so a horizontal
        // scroller there would hide logos behind a gesture nobody would think to try.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            providers.take(MAX_PROVIDERS_PER_COLUMN).forEach { provider ->
                Box(
                    modifier = Modifier
                        .size(PROVIDER_LOGO_SIZE)
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.colors.surface),
                ) {
                    WbAsyncImage(
                        url = provider.logoUrl,
                        contentDescription = provider.name,
                        contentScale = ContentScale.Fit,
                        fallbackLabel = provider.name.take(2),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * Viewer reviews from TMDB.
 *
 * Phone only, by deliberate choice - see the call site. Each review is capped rather than
 * expandable: they run to thousands of characters, and a page that grows by a screenful per
 * review buries everything below it.
 */
@Composable
fun ReviewSection(
    reviews: List<TmdbReview>,
    isTablet: Boolean,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb

    Column(modifier = modifier.fillMaxWidth()) {
        DetailSectionTitle(
            title = stringResource(R.string.detail_reviews),
            isTablet = isTablet,
            modifier = Modifier
                .padding(horizontal = horizontalPadding)
                .padding(bottom = 12.dp),
        )

        reviews.take(MAX_REVIEWS).forEach { review ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(tokens.colors.surfaceCard)
                    .padding(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(tokens.colors.surface),
                    ) {
                        WbAsyncImage(
                            url = review.avatarUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            fallbackLabel = review.author.take(1).uppercase(),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    Text(
                        text = review.author,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )

                    // Only when the reviewer actually scored it. TMDB leaves this null for
                    // a text-only review, and rendering "0/10" would invent a verdict.
                    review.rating?.let { rating ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Star,
                                contentDescription = null,
                                tint = tokens.colors.warning,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = "$rating",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = tokens.colors.warning,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = review.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textSecondary,
                    maxLines = MAX_REVIEW_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Wide enough for a recognisable mark at a glance. */
private val PROVIDER_LOGO_SIZE = 44.dp

/** Enough for a column half the screen wide; beyond this the section dominates the page. */
private const val MAX_PROVIDERS_PER_COLUMN = 8

/** Enough to be representative without turning the page into a comment thread. */
private const val MAX_REVIEWS = 3

/** Reviews run to thousands of characters; this keeps each to a readable card. */
private const val MAX_REVIEW_LINES = 6
