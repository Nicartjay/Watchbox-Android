package space.nicart.watchbox.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import space.nicart.watchbox.domain.AnimeCard
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
 * Keeps the Nuvio layout — hero running edge-to-edge under the status bar, no top
 * app bar, 12dp-spaced rails below — but every row is fed by an installed
 * extension. There is no cross-source trending feed in this ecosystem, so the
 * hero is drawn from the first source's popular list and each source contributes
 * one rail.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenAnime: (AnimeCard) -> Unit,
    onResume: (WatchHistoryEntry) -> Unit,
    onOpenSaved: (WatchlistEntry) -> Unit,
    onBrowseSource: (sourceId: Long, sourceName: String) -> Unit,
    onInstallExtensions: () -> Unit,
    /** Opens Settings, where repositories are managed. */
    onOpenSettings: () -> Unit,
    navScrollState: WbNavBarScrollState,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val personal by viewModel.personal.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Drive the nav-pill collapse from this list's scroll delta.
    val nestedScroll = remember(navScrollState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                navScrollState.onScroll(available.y)
                return Offset.Zero
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sectionPadding = sectionHorizontalPadding(maxWidth)

        // Nothing installed yet: the feed is empty by definition, so prompt
        // rather than showing an error.
        if (state.hasNoSources) {
            // Two different dead ends. With no repository configured the extension
            // list has nothing in it, so sending the user there leaves them stuck -
            // they need to add a repository first, which lives in Settings.
            if (state.hasNoRepos) {
                WbEmptyState(
                    title = stringResource(R.string.empty_no_repos_title),
                    body = stringResource(R.string.empty_no_repos_body),
                    actionLabel = stringResource(R.string.action_add_repository),
                    onAction = onOpenSettings,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                WbEmptyState(
                    title = stringResource(R.string.empty_no_sources_title),
                    body = stringResource(R.string.empty_no_sources_body),
                    actionLabel = stringResource(R.string.action_browse_extensions),
                    onAction = onInstallExtensions,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            return@BoxWithConstraints
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScroll),
            contentPadding = PaddingValues(bottom = 18.dp + NavOverlayPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "hero") {
                val hero = state.feed?.hero
                when {
                    state.isLoading -> HomeHeroSkeleton()
                    !hero.isNullOrEmpty() -> HomeHeroSection(
                        items = hero,
                        onOpen = onOpenAnime,
                    )
                }
            }

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
                            subtitle = entry.sourceName,
                            onClick = { onOpenSaved(entry) },
                            onLongClick = { viewModel.removeFromWatchlist(entry) },
                        )
                    }
                }
            }

            if (state.isLoading) {
                items(3, key = { "skeleton-$it" }) {
                    WbSkeletonRow(horizontalPadding = sectionPadding)
                }
            } else {
                state.feed?.rows?.forEach { row ->
                    item(key = "row-${row.sourceId}-${row.title}") {
                        WbShelfSection(
                            title = row.title,
                            items = row.items,
                            key = { it.key },
                            horizontalPadding = sectionPadding,
                            onViewAll = { onBrowseSource(row.sourceId, row.sourceName) },
                            modifier = Modifier.padding(bottom = 12.dp),
                        ) { card ->
                            WbPosterCard(card = card, onClick = { onOpenAnime(card) })
                        }
                    }
                }
            }

            state.errorMessage?.takeIf { state.feed == null }?.let { message ->
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
 * Continue Watching rail, "Card" style: landscape thumbnails with a progress
 * strip. 16dp gaps and a 16dp radius, per Nuvio's phone metrics.
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
                url = entry.posterUrl,
                contentDescription = entry.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                fallbackLabel = entry.title,
            )

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
                entry.episodeLabel.takeIf { it.isNotBlank() }?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                WbProgressBar(progress = entry.progress, modifier = Modifier.fillMaxWidth())
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

private fun WatchlistEntry.toCard(): AnimeCard = AnimeCard(
    sourceId = sourceId,
    url = animeUrl,
    title = title,
    posterUrl = posterUrl,
    sourceName = sourceName,
)
