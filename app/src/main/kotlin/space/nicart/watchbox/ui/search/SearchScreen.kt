package space.nicart.watchbox.ui.search

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.LocalLayoutMetrics
import space.nicart.watchbox.core.ui.gridColumnsScaled
import space.nicart.watchbox.core.ui.LocalPosterScale
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.ui.components.NavOverlayPadding
import space.nicart.watchbox.ui.components.WbShelfSection
import space.nicart.watchbox.ui.components.WbEmptyState
import space.nicart.watchbox.ui.components.WbLoading
import space.nicart.watchbox.ui.components.WbPosterCard
import space.nicart.watchbox.ui.components.WbChip
import space.nicart.watchbox.ui.components.WbScreenHeader
import space.nicart.watchbox.ui.components.WbSearchField
import space.nicart.watchbox.ui.components.sectionHorizontalPadding

/**
 * Search.
 *
 * Layout follows NuvioMobile `features/search/SearchScreen.kt`: a large page
 * header, a rounded input field, then a poster grid. Column count scales with
 * width (3 on phones, up to 7 on wide screens) per `PosterGrid.kt:37-44`.
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenAnime: (AnimeCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tokens = MaterialTheme.wb

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
                WbScreenHeader(title = stringResource(R.string.title_search))
                Spacer(Modifier.height(6.dp))

                WbSearchField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = stringResource(R.string.empty_search_hint),
                    onSubmit = viewModel::submit,
                )

                // Scope picker. Shown only with more than one source installed,
                // since narrowing to the single source you already have is a no-op.
                if (state.sources.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        item(key = "all") {
                            WbChip(
                                label = stringResource(R.string.search_all_sources),
                                selected = state.selectedSourceId == null,
                                onClick = { viewModel.onSelectSource(null) },
                            )
                        }
                        items(items = state.sources, key = { it.id }) { source ->
                            WbChip(
                                label = source.name,
                                selected = state.selectedSourceId == source.id,
                                onClick = { viewModel.onSelectSource(source.id) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.isLoading -> WbLoading()

                    state.query.isBlank() && state.recentSearches.isNotEmpty() -> {
                        RecentSearches(
                            terms = state.recentSearches,
                            onSelect = {
                                viewModel.onQueryChange(it)
                                viewModel.submit()
                            },
                            onClear = viewModel::clearRecent,
                            modifier = Modifier.padding(horizontal = padding),
                        )
                    }

                    state.hasNoSources -> WbEmptyState(
                        title = stringResource(R.string.empty_no_sources_title),
                        body = stringResource(R.string.empty_no_sources_body),
                        modifier = Modifier.align(Alignment.TopCenter),
                    )

                    state.hasSearched && state.results.isEmpty() -> WbEmptyState(
                        title = stringResource(R.string.empty_search_title),
                        body = "Try a different title.",
                        modifier = Modifier.align(Alignment.TopCenter),
                    )

                    // One rail per source: a merged list would bury good matches
                    // behind whichever source happened to return most rows.
                    else -> LazyColumn(
                        contentPadding = PaddingValues(bottom = 18.dp + NavOverlayPadding),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items = state.results, key = { it.sourceId }) { row ->
                            WbShelfSection(
                                title = row.title,
                                items = row.items,
                                key = { it.key },
                                horizontalPadding = padding,
                            ) { card ->
                                WbPosterCard(card = card, onClick = { onOpenAnime(card) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSearches(
    terms: List<String>,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Recent",
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
            )
            Text(
                text = "Clear",
                style = MaterialTheme.typography.labelLarge,
                color = tokens.colors.textMuted,
                modifier = Modifier.clickable(onClick = onClear),
            )
        }

        terms.forEach { term ->
            Text(
                text = term,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(term) }
                    .padding(vertical = 10.dp),
            )
        }
    }
}
