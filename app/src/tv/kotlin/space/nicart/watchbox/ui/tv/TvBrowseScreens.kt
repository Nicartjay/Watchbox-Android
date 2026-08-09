package space.nicart.watchbox.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.tvFocusable
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.domain.AnimeCard
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
        columns = GridCells.Fixed(TILE_COLUMNS),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 48.dp),
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
    onBack: () -> Unit,
    onOpenAnime: (AnimeCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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
                columns = GridCells.Fixed(POSTER_COLUMNS),
                contentPadding = PaddingValues(
                    start = 48.dp,
                    end = 48.dp,
                    top = 40.dp,
                    bottom = 48.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    Column(modifier = Modifier.padding(bottom = 12.dp)) {
                        Text(
                            text = sourceName,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = tokens.colors.textPrimary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = when (state.mode) {
                                BrowseMode.POPULAR -> stringResource(R.string.source_popular)
                                BrowseMode.LATEST -> stringResource(R.string.source_latest)
                                BrowseMode.SEARCH -> stringResource(R.string.title_search)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = tokens.colors.textMuted,
                        )
                    }
                }

                items(items = state.items, key = { it.key }) { card ->
                    TvGridPoster(card = card, onClick = { onOpenAnime(card) })
                }
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
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        ) {
            WbAsyncImage(
                url = card.posterUrl,
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
private const val POSTER_COLUMNS = 6
private const val POSTER_ASPECT = 0.675f
