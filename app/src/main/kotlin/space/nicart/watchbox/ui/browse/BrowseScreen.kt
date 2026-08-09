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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.LocalLayoutMetrics
import space.nicart.watchbox.core.ui.gridColumnsScaled
import space.nicart.watchbox.core.ui.LocalPosterScale
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.ui.components.NavOverlayPadding
import space.nicart.watchbox.ui.components.WbBackButton
import space.nicart.watchbox.ui.extensions.ExtensionIconSlot
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
    /** Extensions with a newer build available; shown as a badge. */
    updateCount: Int = 0,
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val tokens = MaterialTheme.wb

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val padding = sectionHorizontalPadding(maxWidth)

        // A grid rather than a list: sources are identified by their extension's
        // icon far faster than by name, and an icon-led row wastes most of its
        // width. Adaptive columns keep the tile size constant across screen sizes
        // instead of stretching a fixed count.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = padding,
                end = padding,
                bottom = 18.dp + NavOverlayPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                Box(modifier = Modifier.statusBarsPadding().padding(top = 10.dp)) {
                    WbScreenHeader(title = stringResource(R.string.title_browse))
                }
            }

            item(key = "extensions-entry", span = { GridItemSpan(maxLineSpan) }) {
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
                    // Populated by the startup repository check, so pending updates
                    // are visible without opening the extension list first.
                    if (updateCount > 0) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(tokens.colors.accent)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = updateCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = tokens.colors.onAccent,
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
                item(key = "sources-label", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.title_sources).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.textMuted,
                        modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                    )
                }

                items(items = sources, key = { it.id }) { source ->
                    SourceTile(source = source, onClick = { onOpenSource(source) })
                }
            }
        }
    }
}

/**
 * One source in the browse grid.
 *
 * The extension icon leads, because that is what identifies a source at a glance.
 * Names get two lines and are centred: source names are short but not uniformly so,
 * and truncating to one line loses the distinguishing half of names sharing a prefix.
 */
@Composable
private fun SourceTile(
    source: SourceEntry,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.wb

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tokens.colors.surfaceCard)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ExtensionIconSlot(
            drawable = source.icon,
            // Installed extensions carry no icon URL; the drawable is the only source.
            iconUrl = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.colors.surface),
        )

        Text(
            text = source.name,
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
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
        // One definition of the column ladder, in LayoutMetrics. Four copies of this
        // `when` had already drifted - Search computed it and never used it.
        val columns = LocalLayoutMetrics.current
            .gridColumnsScaled(LocalPosterScale.current)

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
