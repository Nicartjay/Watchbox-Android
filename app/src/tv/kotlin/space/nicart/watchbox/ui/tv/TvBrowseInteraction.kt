package space.nicart.watchbox.ui.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import space.nicart.watchbox.ui.browse.BrowseUiState

/** Typing is local to TV Browse; only an explicit keyboard submission reaches the ViewModel. */
internal class TvBrowseSearchInput(
    initialQuery: String,
    private val onSubmit: (String) -> Unit,
) {
    var query by mutableStateOf(initialQuery)
        private set

    fun edit(value: String) {
        query = value
    }

    fun submit() {
        onSubmit(query)
    }
}

/**
 * Loading/empty header rows must not count as a browsable page ready to append.
 * Keep this stable across isAppending changes: otherwise a duplicate page would
 * re-arm pagination repeatedly despite adding no cards. loadMore guards in-flight requests.
 */
internal fun BrowseUiState.shouldAppendTvBrowse(lastVisibleIndex: Int, totalItems: Int): Boolean =
    !isLoading && hasMore && items.isNotEmpty() &&
        totalItems > 0 && lastVisibleIndex >= totalItems - TV_POSTER_GRID_COLUMNS * 2
