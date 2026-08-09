package space.nicart.watchbox.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.ui.components.NavOverlayPadding
import space.nicart.watchbox.ui.components.WbBackButton
import space.nicart.watchbox.ui.components.WbChip
import space.nicart.watchbox.ui.components.WbEmptyState
import space.nicart.watchbox.ui.components.WbLoading
import space.nicart.watchbox.ui.components.WbPosterCard
import space.nicart.watchbox.ui.components.WbScreenHeader
import space.nicart.watchbox.ui.components.WbSearchField
import space.nicart.watchbox.ui.components.sectionHorizontalPadding

/**
 * Browse tab: the list of installed sources, plus a way into the extension
 * manager. This is the entry point when the library is still empty.
 */
@Composable
fun SourceListScreen(
    viewModel: SourceListViewModel,
    onOpenSource: (SourceEntry) -> Unit,
    onOpenExtensions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val tokens = MaterialTheme.wb

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val padding = sectionHorizontalPadding(maxWidth)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = padding,
                end = padding,
                bottom = 18.dp + NavOverlayPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                Box(modifier = Modifier.statusBarsPadding().padding(top = 10.dp)) {
                    WbScreenHeader(title = stringResource(R.string.title_browse))
                }
            }

            item(key = "extensions-entry") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(tokens.colors.surfaceCard)
                        .clickable(onClick = onOpenExtensions)
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Extension,
                        contentDescription = null,
                        tint = tokens.colors.accent,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = stringResource(R.string.title_extensions),
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = tokens.colors.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (sources.isEmpty()) {
                item(key = "empty") {
                    WbEmptyState(
                        title = stringResource(R.string.empty_no_sources_title),
                        body = stringResource(R.string.empty_no_sources_body),
                        actionLabel = stringResource(R.string.action_browse_extensions),
                        onAction = onOpenExtensions,
                    )
                }
            } else {
                item(key = "sources-label") {
                    Text(
                        text = stringResource(R.string.title_sources).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.textMuted,
                        modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                    )
                }

                items(items = sources, key = { it.id }) { source ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(tokens.colors.surfaceCard)
                            .clickable { onOpenSource(source) }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = source.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = tokens.colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (source.lang.isNotBlank()) {
                                Text(
                                    text = source.lang.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tokens.colors.textMuted,
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = tokens.colors.textMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Paged grid for one source, with Popular/Latest chips.
 *
 * Appends the next page a little before the end of the list so scrolling stays
 * continuous instead of stalling at the boundary.
 */
@Composable
fun BrowseScreen(
    sourceName: String,
    viewModel: BrowseViewModel,
    onBack: () -> Unit,
    onOpenAnime: (AnimeCard) -> Unit,
    supportsLatest: Boolean,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val tokens = MaterialTheme.wb

    val shouldAppend by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 6
        }
    }
    LaunchedEffect(shouldAppend) {
        if (shouldAppend) viewModel.loadMore()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val padding = sectionHorizontalPadding(maxWidth)
        val columns = when {
            maxWidth >= 1400.dp -> 7
            maxWidth >= 1200.dp -> 6
            maxWidth >= 1000.dp -> 5
            maxWidth >= 840.dp -> 4
            else -> 3
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = padding)
                    .padding(top = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WbBackButton(onClick = onBack)
                    Spacer(Modifier.size(8.dp))
                    WbScreenHeader(title = sourceName, modifier = Modifier.weight(1f))

                    // Only offered when the source actually declares filters, so
                    // the button never opens an empty panel.
                    if (state.hasFilters) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (state.filtersActive) {
                                        tokens.colors.accent
                                    } else {
                                        tokens.colors.surface
                                    },
                                )
                                .clickable { viewModel.setFilterPanelOpen(true) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FilterList,
                                contentDescription = stringResource(R.string.source_filters),
                                tint = if (state.filtersActive) {
                                    tokens.colors.onAccent
                                } else {
                                    tokens.colors.textSecondary
                                },
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                WbSearchField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = stringResource(R.string.source_search_hint),
                    onSubmit = viewModel::submitQuery,
                )

                // Popular/Latest are hidden while searching: neither applies to a
                // query, and leaving them selectable implies they filter results.
                if (supportsLatest && state.mode != BrowseMode.SEARCH) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WbChip(
                            label = stringResource(R.string.source_popular),
                            selected = state.mode == BrowseMode.POPULAR,
                            onClick = { viewModel.setMode(BrowseMode.POPULAR) },
                        )
                        WbChip(
                            label = stringResource(R.string.source_latest),
                            selected = state.mode == BrowseMode.LATEST,
                            onClick = { viewModel.setMode(BrowseMode.LATEST) },
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.isLoading -> WbLoading()

                    state.items.isEmpty() -> WbEmptyState(
                        title = state.errorMessage ?: stringResource(R.string.empty_search_title),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = viewModel::retry,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )

                    else -> LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(columns),
                        contentPadding = PaddingValues(
                            start = padding,
                            end = padding,
                            bottom = 18.dp + NavOverlayPadding,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items = state.items, key = { it.key }) { card ->
                            WbPosterCard(
                                card = card,
                                width = null,
                                onClick = { onOpenAnime(card) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                if (state.isAppending) {
                    CircularProgressIndicator(
                        color = tokens.colors.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = NavOverlayPadding + 16.dp)
                            .size(22.dp),
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
