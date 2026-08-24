package space.nicart.watchbox.ui.library

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.nicart.watchbox.core.ui.tvInitialFocus
import space.nicart.watchbox.core.ui.LocalLayoutMetrics
import space.nicart.watchbox.core.ui.gridColumnsScaled
import space.nicart.watchbox.core.ui.LocalPosterScale
import space.nicart.watchbox.R
import space.nicart.watchbox.data.local.WatchHistoryEntry
import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.ui.components.NavOverlayPadding
import space.nicart.watchbox.ui.components.WbChip
import space.nicart.watchbox.ui.components.WbEmptyState
import space.nicart.watchbox.ui.components.WbPosterCard
import space.nicart.watchbox.ui.components.WbScreenHeader
import space.nicart.watchbox.ui.components.sectionHorizontalPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import space.nicart.watchbox.data.local.DownloadEntry
import space.nicart.watchbox.ui.download.EpisodeDownloadStatus
import androidx.media3.common.util.UnstableApi
import space.nicart.watchbox.ui.download.DownloadDeleteDialog
import space.nicart.watchbox.ui.download.DownloadDeleteTarget

/** Library tabs. */
private enum class LibraryTab(val label: String) {
    MY_LIST("My List"),
    CONTINUE("Continue"),
    HISTORY("History"),

    /**
     * Last, and rendered as rows rather than posters.
     *
     * The other three are per-title and share the grid; a download is per-episode and needs
     * a state, a size and a control that a poster tile has no room for.
     */
    DOWNLOADS("Downloads"),
}

/**
 * Library: saved titles, in-progress titles and full history, as a poster grid
 * with chip tabs. Same grid metrics as search.
 */
@UnstableApi
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenAnime: (AnimeCard) -> Unit,
    onResume: (WatchHistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
    /** Opens a finished download. Null leaves its rows un-tappable. */
    onPlayDownload: ((DownloadEntry) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(LibraryTab.MY_LIST) }

    // Held here rather than inside the row: a dialog owned by a list item is destroyed the
    // moment that item scrolls out of view, taking the confirmation with it.
    var pendingDelete by remember { mutableStateOf<DownloadDeleteTarget?>(null) }

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
                WbScreenHeader(title = stringResource(R.string.title_library))
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryTab.entries.forEach { entry ->
                        WbChip(
                            label = entry.label,
                            selected = entry == tab,
                            onClick = { tab = entry },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            if (tab == LibraryTab.DOWNLOADS) {
                DownloadsTab(
                    state = state,
                    padding = padding,
                    onPlay = onPlayDownload,
                    onPause = viewModel::pauseDownload,
                    onResume = viewModel::resumeDownload,
                    onDelete = { target -> pendingDelete = target },
                )

                DownloadDeleteDialog(
                    target = pendingDelete,
                    onConfirm = {
                        pendingDelete?.let { viewModel.deleteDownload(it.key) }
                        pendingDelete = null
                    },
                    onDismiss = { pendingDelete = null },
                )
                return@Column
            }

            val cards: List<Pair<AnimeCard, WatchHistoryEntry?>> = when (tab) {
                LibraryTab.MY_LIST -> state.myList.map { it.toCard() to null }
                LibraryTab.CONTINUE -> state.continueWatching.map { it.toCard() to it }
                LibraryTab.HISTORY -> state.history.map { it.toCard() to it }
                // Handled above; the grid cannot render these.
                LibraryTab.DOWNLOADS -> emptyList()
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (cards.isEmpty()) {
                    WbEmptyState(
                        title = stringResource(R.string.empty_library_title),
                        body = stringResource(R.string.empty_library_body),
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                } else {
                    LazyVerticalGrid(
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
                        items(items = cards, key = { it.first.key }) { (card, entry) ->
                            WbPosterCard(
                                card = card,
                                width = null,
                                progress = entry?.progress ?: 0f,
                                watched = entry?.isFinished == true,
                                subtitle = card.sourceName,
                                onClick = {
                                    if (entry != null && tab == LibraryTab.CONTINUE) {
                                        onResume(entry)
                                    } else {
                                        onOpenAnime(card)
                                    }
                                },
                                onLongClick = {
                                    when (tab) {
                                        LibraryTab.MY_LIST ->
                                            viewModel.removeFromWatchlist(card.key)

                                        else -> entry?.let { viewModel.removeFromHistory(it.key) }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Downloads tab.
 *
 * Its own list rather than a branch inside the grid, because the two disagree on almost
 * everything: this is keyed per episode, scrolls vertically as rows, and every item carries a
 * control. Sharing the grid would have meant widening its item type to a union and then
 * branching inside the card anyway.
 */
@Composable
private fun DownloadsTab(
    state: LibraryUiState,
    padding: androidx.compose.ui.unit.Dp,
    onPlay: ((DownloadEntry) -> Unit)?,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onDelete: (DownloadDeleteTarget) -> Unit,
) {
    if (state.downloads.isEmpty()) {
        WbEmptyState(
            title = stringResource(R.string.empty_downloads_title),
            body = stringResource(R.string.empty_downloads_body),
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = padding,
            end = padding,
            bottom = 18.dp + NavOverlayPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = state.downloads, key = { it.key }) { entry ->
            val live = state.downloadProgress[entry.key]
            DownloadRow(
                entry = entry,
                status = EpisodeDownloadStatus(
                    state = entry.state,
                    fraction = ((live?.percent ?: 0f) / 100f).coerceIn(0f, 1f),
                    // The live figure while running, the stored one once finished: a
                    // completed download has no listener reporting on it.
                    sizeBytes = live?.bytesDownloaded?.takeIf { it > 0 } ?: entry.sizeBytes,
                    totalBytes = live?.totalBytes ?: 0L,
                    unavailable = entry.volumeId.isNotBlank() &&
                        entry.volumeId !in state.mountedVolumes,
                ),
                onPlay = { onPlay?.invoke(entry) },
                onPause = { onPause(entry.key) },
                onResume = { onResume(entry.key) },
                onDelete = {
                    onDelete(
                        DownloadDeleteTarget(
                            key = entry.key,
                            label = entry.episodeName.ifBlank { entry.title },
                            sizeBytes = live?.bytesDownloaded?.takeIf { it > 0 }
                                ?: entry.sizeBytes,
                            unfinished = !entry.isComplete,
                        ),
                    )
                },
            )
        }
    }
}
