package space.nicart.watchbox.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.WbTokens
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.domain.MediaCard

/**
 * Poster rails and cards.
 *
 * Metrics are taken from NuvioMobile `core/ui/ShelfComponents.kt` and
 * `PosterCardStyleRepository.kt`:
 *   width 126dp, height 189dp (3:2), corner radius 12dp, item spacing 10dp,
 *   header-to-row gap 10dp.
 */
object PosterMetrics {
    val Width: Dp = 126.dp
    val Height: Dp = 189.dp
    val CornerRadius: Dp = 12.dp
    val ItemSpacing: Dp = 10.dp
    val HeaderGap: Dp = 10.dp

    /** `ShelfComponents.kt:311-316` */
    const val PORTRAIT_ASPECT = 0.675f
    const val LANDSCAPE_ASPECT = 1.77f

    /** `PosterCardDimensions.kt:7-13` — landscape cards are widened by 180/110. */
    val LandscapeWidth: Dp = Width * (180f / 110f)
}

/** Section horizontal padding by width (`HomeSectionLayout.kt:6-12`). */
fun sectionHorizontalPadding(maxWidth: Dp): Dp = when {
    maxWidth >= 1440.dp -> 32.dp
    maxWidth >= 1024.dp -> 28.dp
    maxWidth >= 768.dp -> 24.dp
    else -> 16.dp
}

/**
 * A titled horizontal rail (`NuvioShelfSection`, `ShelfComponents.kt:62-119`).
 *
 * Row content padding equals the section padding so items bleed to the screen
 * edge while scrolling.
 */
@Composable
fun <T> WbShelfSection(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
    itemSpacing: Dp = PosterMetrics.ItemSpacing,
    onViewAll: (() -> Unit)? = null,
    itemContent: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PosterMetrics.HeaderGap),
    ) {
        WbShelfHeader(
            title = title,
            onViewAll = onViewAll,
            modifier = Modifier.padding(horizontal = horizontalPadding),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            items(items = items, key = key) { item -> itemContent(item) }
        }
    }
}

/** Section header + optional "view all" pill (`ShelfComponents.kt:242-309`). */
@Composable
fun WbShelfHeader(
    title: String,
    modifier: Modifier = Modifier,
    onViewAll: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.wb

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        // Rendered transparent when absent so the row height never shifts.
        Box(
            modifier = Modifier
                .size(32.dp)
                .alpha(if (onViewAll == null) 0f else 1f)
                .clip(RoundedCornerShape(16.dp))
                .background(tokens.colors.surface)
                .then(
                    if (onViewAll != null) {
                        Modifier.combinedClickable(onClick = onViewAll)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = stringResource(R.string.action_view_all),
                tint = tokens.colors.textMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * A poster card (`NuvioPosterCard`, `ShelfComponents.kt:122-239`).
 *
 * Column of image + title + detail line, 6dp gaps. The watched tick sits at the
 * top-end of the artwork.
 */
@Composable
fun WbPosterCard(
    card: MediaCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    width: Dp? = PosterMetrics.Width,
    aspectRatio: Float = PosterMetrics.PORTRAIT_ASPECT,
    watched: Boolean = false,
    progress: Float = 0f,
    showLabels: Boolean = true,
) {
    val tokens = MaterialTheme.wb

    Column(
        // A null width means "fill whatever the parent gives us", which is what
        // the search/library grids need.
        modifier = if (width != null) modifier.width(width) else modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(PosterMetrics.CornerRadius))
                .background(tokens.colors.surface)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            WbAsyncImage(
                url = card.posterUrl,
                contentDescription = card.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                fallbackLabel = card.title,
            )

            if (card.isUpcoming) {
                WbCornerBadge(
                    text = "SOON",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                )
            }

            WbWatchedBadge(
                visible = watched,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            )

            if (progress > 0.01f && progress < 0.99f) {
                WbProgressBar(
                    progress = progress,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                )
            }
        }

        if (showLabels) {
            Text(
                text = card.title,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            card.detailLine.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Watched tick: 20dp accent circle, 12dp check (`PosterActionSheet.kt:25-72`). */
@Composable
fun WbWatchedBadge(visible: Boolean, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.wb
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(WbTokens.Icon.md)
                .clip(CircleShape)
                .background(tokens.colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = tokens.colors.onAccent,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** Small pill used for "SOON" / quality corners. */
@Composable
fun WbCornerBadge(text: String, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.wb
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(tokens.colors.accent),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.onAccent,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

/** 4dp progress strip; track white@30%, fill accent (`ProgressBar.kt`). */
@Composable
fun WbProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    val tokens = MaterialTheme.wb
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(WbTokens.Radius.full))
            .background(tokens.colors.playerTimelineTrack),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height)
                .clip(RoundedCornerShape(WbTokens.Radius.full))
                .background(tokens.colors.playerTimelineFill),
        )
    }
}

/**
 * Shimmer placeholder brush (`HomeSkeletonLoading.kt:40-61`):
 * linear gradient [surface, surface@50%, surface] swept 0 -> 1000px over 1200ms.
 */
@Composable
fun rememberShimmerBrush(): Brush {
    val tokens = MaterialTheme.wb
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerOffset",
    )

    return Brush.linearGradient(
        colors = listOf(
            tokens.colors.surface,
            tokens.colors.surface.copy(alpha = 0.5f),
            tokens.colors.surface,
        ),
        start = androidx.compose.ui.geometry.Offset(offset - 500f, 0f),
        end = androidx.compose.ui.geometry.Offset(offset, 0f),
    )
}

/** A single shimmering block. */
@Composable
fun WbSkeletonBlock(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 6.dp,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(rememberShimmerBrush()),
    )
}

/** Placeholder rail: 140x18 title block then four poster blocks. */
@Composable
fun WbSkeletonRow(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PosterMetrics.HeaderGap),
    ) {
        WbSkeletonBlock(
            modifier = Modifier
                .padding(horizontal = horizontalPadding)
                .width(140.dp)
                .height(18.dp),
        )
        Row(
            modifier = Modifier.padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(PosterMetrics.ItemSpacing),
        ) {
            repeat(4) {
                WbSkeletonBlock(
                    modifier = Modifier
                        .width(PosterMetrics.Width)
                        .height(PosterMetrics.Height),
                    cornerRadius = PosterMetrics.CornerRadius,
                )
            }
        }
    }
}
