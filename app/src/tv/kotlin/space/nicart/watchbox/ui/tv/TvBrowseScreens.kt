package space.nicart.watchbox.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.LocalPosterScale
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.tvFocusable
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.domain.AnimeCard
import androidx.compose.material.icons.rounded.FilterList
import space.nicart.watchbox.core.ui.adaptiveFocus
import space.nicart.watchbox.ui.browse.BrowseUiState
import space.nicart.watchbox.ui.browse.SourceFilterPanel
import space.nicart.watchbox.ui.components.WbChip
import space.nicart.watchbox.ui.components.WbSearchField
import space.nicart.watchbox.ui.browse.BrowseMode
import space.nicart.watchbox.ui.browse.BrowseViewModel
import space.nicart.watchbox.ui.browse.SourceEntry
import space.nicart.watchbox.ui.browse.SourceListViewModel
import space.nicart.watchbox.ui.components.WbAsyncImage
import space.nicart.watchbox.ui.components.WbEmptyState
import space.nicart.watchbox.ui.components.WbLoading
import space.nicart.watchbox.ui.extensions.ExtensionIconSlot

/**
 * TV source list: a grid of installed sources led by their extension icons.
 *
 * Wider tiles and fewer columns than the phone grid. A D-pad crosses one tile per
 * press, so a dense grid that suits a thumb becomes a long traverse with a remote.
 */
@Composable
fun TvSourceListScreen(
    viewModel: SourceListViewModel,
    updateCount: Int,
    onOpenSource: (SourceEntry) -> Unit,
    onOpenExtensions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val tokens = MaterialTheme.wb

    LazyVerticalGrid(
        columns = GridCells.Fixed(
            // Fewer, larger tiles as the poster scale rises.
            (TILE_COLUMNS / LocalPosterScale.current).toInt().coerceAtLeast(2),
        ),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = TV_CONTENT_START, end = 48.dp, top = 40.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = stringResource(R.string.title_browse),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = tokens.colors.textPrimary,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }

        item(key = "extensions", span = { GridItemSpan(maxLineSpan) }) {
            TvExtensionsEntry(updateCount = updateCount, onClick = onOpenExtensions)
        }

        if (sources.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                WbEmptyState(
                    title = stringResource(R.string.empty_no_sources_title),
                    body = stringResource(R.string.empty_no_sources_body),
                    actionLabel = stringResource(R.string.action_browse_extensions),
                    onAction = onOpenExtensions,
                )
            }
        } else {
            items(items = sources, key = { it.id }) { source ->
                TvSourceTile(source = source, onClick = { onOpenSource(source) })
            }
        }
    }
}

@Composable
private fun TvExtensionsEntry(updateCount: Int, onClick: () -> Unit) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tokens.colors.surfaceCard)
            .tvFocusable(interaction, RoundedCornerShape(16.dp), focusedScale = 1.02f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Extension,
            contentDescription = null,
            tint = tokens.colors.accent,
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = stringResource(R.string.title_extensions),
            style = MaterialTheme.typography.titleLarge,
            color = tokens.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (updateCount > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.colors.accent)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = updateCount.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = tokens.colors.onAccent,
                )
            }
        }
    }
}

@Composable
private fun TvSourceTile(source: SourceEntry, onClick: () -> Unit) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tokens.colors.surfaceCard)
            .tvFocusable(interaction, RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 20.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ExtensionIconSlot(
            drawable = source.icon,
            iconUrl = null,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tokens.colors.surface),
        )
        Text(
            text = source.name,
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (source.lang.isNotBlank()) {
            Text(
                text = source.lang.uppercase(),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
        }
    }
}

/**
 * TV browse for one source: a paged poster grid.
 *
 * Paging is driven by focus position rather than scroll offset. With a D-pad the list
 * only scrolls because focus moved, so watching focus is both more direct and fires
 * earlier - the next page is already loading as the user approaches the end.
 */
@Composable
fun TvSourceBrowseScreen(
    sourceName: String,
    viewModel: BrowseViewModel,
    artworkViewModel: TvArtworkViewModel,
    onBack: () -> Unit,
    onOpenAnime: (AnimeCard) -> Unit,
    supportsLatest: Boolean,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val artwork by artworkViewModel.artwork.collectAsStateWithLifecycle()

    // Re-requested as the page grows: each key covers one page, so appending a page
    // enriches only the new cards rather than re-walking the whole grid.
    LaunchedEffect(state.items.size) {
        artworkViewModel.onRowVisible("browse-$sourceName-${state.page}", state.items)
    }
    val gridState = rememberLazyGridState()
    val tokens = MaterialTheme.wb

    val shouldAppend by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            total > 0 && last >= total - POSTER_COLUMNS * 2
        }
    }
    LaunchedEffect(shouldAppend) {
        if (shouldAppend) viewModel.loadMore()
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading -> WbLoading()

            state.items.isEmpty() -> WbEmptyState(
                title = state.errorMessage ?: stringResource(R.string.empty_search_title),
                actionLabel = stringResource(R.string.action_retry),
                onAction = viewModel::retry,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(
                    (POSTER_COLUMNS / LocalPosterScale.current).toInt().coerceAtLeast(2),
                ),
                contentPadding = PaddingValues(
                    start = TV_CONTENT_START,
                    end = 48.dp,
                    top = 40.dp,
                    bottom = 48.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    TvBrowseHeader(
                        sourceName = sourceName,
                        state = state,
                        supportsLatest = supportsLatest,
                        onQueryChange = viewModel::onQueryChange,
                        onSubmitQuery = viewModel::submitQuery,
                        onSetMode = viewModel::setMode,
                        onOpenFilters = { viewModel.setFilterPanelOpen(true) },
                    )
                }

                items(items = state.items, key = { it.key }) { card ->
                    TvGridPoster(
                        card = artwork[card.key] ?: card,
                        onClick = { onOpenAnime(card) },
                    )
                }
            }
        }

        SourceFilterPanel(
            entries = state.filters,
            visible = state.filterPanelOpen,
            onChange = viewModel::onFilterChange,
            onApply = viewModel::applyFilters,
            onReset = viewModel::resetFilters,
            onDismiss = { viewModel.setFilterPanelOpen(false) },
        )
    }
}

/**
 * Browse header: title, search field, filters, and the Popular/Latest choice.
 *
 * The same controls as the phone screen, which previously had no equivalent here - the
 * TV browse screen could only ever show a source's popular list, with no way to search
 * it or apply its filters.
 */
@Composable
private fun TvBrowseHeader(
    sourceName: String,
    state: BrowseUiState,
    supportsLatest: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmitQuery: () -> Unit,
    onSetMode: (BrowseMode) -> Unit,
    onOpenFilters: () -> Unit,
) {
    val tokens = MaterialTheme.wb
    val filterInteraction = rememberFocusInteraction()

    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = sourceName,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = tokens.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )

            // Only offered when the source declares filters, so the button never opens
            // an empty panel.
            if (state.hasFilters) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (state.filtersActive) {
                                tokens.colors.accent
                            } else {
                                tokens.colors.surface
                            },
                        )
                        .adaptiveFocus(filterInteraction, RoundedCornerShape(12.dp), scale = false)
                        .clickable(
                            interactionSource = filterInteraction,
                            indication = null,
                            onClick = onOpenFilters,
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FilterList,
                        contentDescription = null,
                        tint = if (state.filtersActive) {
                            tokens.colors.onAccent
                        } else {
                            tokens.colors.textSecondary
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.source_filters),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (state.filtersActive) {
                            tokens.colors.onAccent
                        } else {
                            tokens.colors.textSecondary
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        WbSearchField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.source_search_hint),
            onSubmit = onSubmitQuery,
        )

        // Hidden while searching: neither mode applies to a query, and leaving them
        // selectable implies they filter the results.
        if (supportsLatest && state.mode != BrowseMode.SEARCH) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WbChip(
                    label = stringResource(R.string.source_popular),
                    selected = state.mode == BrowseMode.POPULAR,
                    onClick = { onSetMode(BrowseMode.POPULAR) },
                )
                WbChip(
                    label = stringResource(R.string.source_latest),
                    selected = state.mode == BrowseMode.LATEST,
                    onClick = { onSetMode(BrowseMode.LATEST) },
                )
            }
        }
    }
}

@Composable
private fun TvGridPoster(card: AnimeCard, onClick: () -> Unit) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(POSTER_ASPECT)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.colors.surfaceCard)
                .tvFocusable(interaction, RoundedCornerShape(12.dp))
                .focusable(interactionSource = interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        ) {
            WbAsyncImage(
                // TMDB's portrait poster first, as on the home screen: a source's own
                // artwork varies wildly in crop and quality.
                url = card.tmdbPosterUrl ?: card.posterUrl,
                contentDescription = card.title,
                contentScale = ContentScale.Crop,
                fallbackLabel = card.title,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = card.title,
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Fewer columns than the phone grid: each D-pad press crosses one tile. */
private const val TILE_COLUMNS = 5
/** Portrait posters, so the same count as the home screen's Latest grid. */
private const val POSTER_COLUMNS = 6

/** 16:9, matching the backdrop it displays. */
private const val CARD_ASPECT = 1.777f

/** 2:3, matching the portrait posters on the home screen. */
private const val POSTER_ASPECT = 0.667f
