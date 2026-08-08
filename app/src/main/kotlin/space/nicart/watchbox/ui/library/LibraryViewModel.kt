package space.nicart.watchbox.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.data.local.WatchHistoryEntry
import space.nicart.watchbox.data.local.WatchlistEntry
import space.nicart.watchbox.domain.AnimeCard

data class LibraryUiState(
    val myList: List<WatchlistEntry> = emptyList(),
    val continueWatching: List<WatchHistoryEntry> = emptyList(),
    val history: List<WatchHistoryEntry> = emptyList(),
)

class LibraryViewModel(private val store: WatchBoxStore) : ViewModel() {

    val uiState: StateFlow<LibraryUiState> = combine(
        store.watchlist,
        store.history,
    ) { watchlist, history ->
        LibraryUiState(
            myList = watchlist.sortedByDescending { it.addedAt },
            continueWatching = history
                .filterNot { it.isFinished }
                .filter { it.progress > 0.005f }
                .sortedByDescending { it.updatedAt },
            history = history.sortedByDescending { it.updatedAt },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
    )

    fun removeFromHistory(key: String) {
        viewModelScope.launch { store.removeHistory(key) }
    }

    fun removeFromWatchlist(key: String) {
        viewModelScope.launch {
            store.watchlist.first()
                .firstOrNull { it.key == key }
                ?.let { store.toggleWatchlist(it) }
        }
    }

    companion object {
        fun factory(store: WatchBoxStore): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LibraryViewModel(store) as T
            }
    }
}

// --------------------------------------------------------------- mappers

internal fun WatchlistEntry.toCard(): AnimeCard = AnimeCard(
    sourceId = sourceId,
    url = animeUrl,
    title = title,
    posterUrl = posterUrl,
    sourceName = sourceName,
)

internal fun WatchHistoryEntry.toCard(): AnimeCard = AnimeCard(
    sourceId = sourceId,
    url = animeUrl,
    title = title,
    posterUrl = posterUrl,
    sourceName = sourceName,
)
