package space.nicart.watchbox.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.data.local.WatchHistoryEntry
import space.nicart.watchbox.data.local.WatchlistEntry
import space.nicart.watchbox.domain.MediaCard
import space.nicart.watchbox.ui.components.NavOverlayPadding
import space.nicart.watchbox.ui.components.PosterMetrics
import space.nicart.watchbox.ui.components.WbAsyncImage
import space.nicart.watchbox.ui.components.WbEmptyState
import space.nicart.watchbox.ui.components.WbPosterCard
import space.nicart.watchbox.ui.components.WbProgressBar
import space.nicart.watchbox.ui.components.WbShelfHeader
import space.nicart.watchbox.ui.components.WbShelfSection
import space.nicart.watchbox.ui.components.WbSkeletonRow
import space.nicart.watchbox.ui.components.sectionHorizontalPadding
import space.nicart.watchbox.ui.navigation.WbNavBarScrollState

/**
 * Home.
 *
 * Layout follows NuvioMobile `features/home/HomeScreen.kt`: a `LazyColumn` with
 * 12dp item spacing, **no top app bar**, the hero running edge-to-edge under the
 * status bar, then Continue Watching, My List, and the API rows in feed order.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenTitle: (MediaCard) -> Unit,
    onOpenDetailPath: (detailPath: String, title: String) -> Unit,
    onResume: (WatchHistoryEntry) -> Unit,
    onViewAll: (rowId: String, title: String) -> Unit,
    navScrollState: WbNavBarScrollState,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val personal by viewModel.personal.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Drive the nav-pill collapse from this list's scroll delta.
    val nestedScroll = remember(navScrollState) {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                navScrollState.onScroll(available.y)
                return Offset.Zero
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sectionPadding = sectionHorizontalPadding(maxWidth)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScroll),
            contentPadding = PaddingValues(bottom = 18.dp + NavOverlayPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------------------------------------------------------- hero
            item(key = "hero") {
                when {
                    state.isLoading -> HomeHeroSkeleton()
                    !state.content?.hero.isNullOrEmpty() -> HomeHeroSection(
                        items = state.content!!.hero,
                        onOpen = { onOpenDetailPath(it.card.detailPath, it.card.title) },
                    )
                }
            }

            // --------------------------------------------- continue watching
            if (personal.continueWatching.isNotEmpty()) {
                item(key = "continue") {
                    ContinueWatchingSection(
                        entries = personal.continueWatching,
                        horizontalPadding = sectionPadding,
                        onResume = onResume,
                        onRemove = { viewModel.removeFromHistory(it.key) },
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }

            // ------------------------------------------------------- my list
            if (personal.myList.isNotEmpty()) {
                item(key = "mylist") {
                    WbShelfSection(
                        title = stringResource(R.string.section_my_list),
                        items = personal.myList,
                        key = { it.key },
                        horizontalPadding = sectionPadding,
                        modifier = Modifier.padding(bottom = 12.dp),
                    ) { entry ->
                        WbPosterCard(
                            card = entry.toCard(),
                            onClick = { onOpenDetailPath(entry.detailPath, entry.title) },
                            onLongClick = { viewModel.removeFromWatchlist(entry) },
                        )
                    }
                }
            }

            // ---------------------------------------------------- API rows
            if (state.isLoading) {
                items(3, key = { "skeleton-$it" }) {
                    WbSkeletonRow(horizontalPadding = sectionPadding)
                }
            } else {
                state.content?.rows?.forEach { row ->
                    item(key = "row-${row.id}") {
                        WbShelfSection(
                            title = row.title,
                            items = row.items,
                            key = { it.detailPath },
                            horizontalPadding = sectionPadding,
                            onViewAll = { onViewAll(row.id, row.title) },
                            modifier = Modifier.padding(bottom = 12.dp),
                        ) { card ->
                            WbPosterCard(
                                card = card,
                                onClick = { onOpenTitle(card) },
                            )
                        }
                    }
                }
            }

            // ------------------------------------------------------- error
            state.errorMessage?.takeIf { state.content == null }?.let { message ->
                item(key = "error") {
                    WbEmptyState(
                        title = stringResource(R.string.error_generic),
                        body = message,
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = { viewModel.load(refresh = true) },
                    )
                }
            }
        }
    }
}

/**
 * Continue Watching, "Card" style: landscape thumbnails with a progress strip.
 * Phone metrics from `HomeContinueWatchingSection.kt:1167-1245` — 16dp gaps,
 * 16dp radius, 4dp progress bar.
 */
@Composable
private fun ContinueWatchingSection(
    entries: List<WatchHistoryEntry>,
    horizontalPadding: Dp,
    onResume: (WatchHistoryEntry) -> Unit,
    onRemove: (WatchHistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PosterMetrics.HeaderGap),
    ) {
        WbShelfHeader(
            title = stringResource(R.string.section_continue_watching),
            modifier = Modifier.padding(horizontal = horizontalPadding),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items = entries, key = { it.key }) { entry ->
                ContinueWatchingCard(
                    entry = entry,
                    onClick = { onResume(entry) },
                    onLongClick = { onRemove(entry) },
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    entry: WatchHistoryEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val tokens = MaterialTheme.wb
    // Card style: landscape poster width * 1.2 (`HomeContinueWatchingSection.kt:76-83`).
    val cardWidth = PosterMetrics.LandscapeWidth * 1.2f
    val cardHeight = cardWidth / PosterMetrics.LANDSCAPE_ASPECT

    Column(
        modifier = Modifier.width(cardWidth),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .clip(RoundedCornerShape(16.dp))
                .background(tokens.colors.surface)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            WbAsyncImage(
                url = entry.coverUrl,
                contentDescription = entry.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                fallbackLabel = entry.title,
            )

            // Bottom scrim so the episode label stays legible.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(cardHeight * 0.5f)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                        ),
                    ),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (entry.isSeries) {
                    Text(
                        text = "S%02dE%02d".format(entry.season, entry.episode),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }
                WbProgressBar(
                    progress = entry.progress,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun WatchlistEntry.toCard(): MediaCard = MediaCard(
    subjectId = subjectId,
    detailPath = detailPath,
    title = title,
    posterUrl = coverUrl,
    subjectType = subjectType,
    year = null,
    rating = imdbRating.takeIf { it.isNotBlank() },
    genres = genre.split(',').map { it.trim() }.filter { it.isNotEmpty() },
    isUpcoming = false,
    releaseDate = "",
)
