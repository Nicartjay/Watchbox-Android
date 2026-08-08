package space.nicart.watchbox.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.data.local.WatchHistoryEntry
import space.nicart.watchbox.data.local.WatchlistEntry
import space.nicart.watchbox.domain.HomeContent
import space.nicart.watchbox.domain.MediaCard
import space.nicart.watchbox.domain.MediaRepository

/** Home screen state. */
data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val content: HomeContent? = null,
    val errorMessage: String? = null,
)

/** Continue Watching + My List, derived from persisted state. */
data class HomePersonalState(
    val continueWatching: List<WatchHistoryEntry> = emptyList(),
    val myList: List<WatchlistEntry> = emptyList(),
)

class HomeViewModel(
    private val repository: MediaRepository,
    private val store: WatchBoxStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * Finished titles are dropped from Continue Watching, which the web app does
     * not do — there, a completed episode lingers at 100%.
     */
    val personal: StateFlow<HomePersonalState> = combine(
        store.history,
        store.watchlist,
    ) { history, watchlist ->
        HomePersonalState(
            continueWatching = history
                .filterNot { it.isFinished }
                .filter { it.progress > 0.005f }
                .sortedByDescending { it.updatedAt },
            myList = watchlist.sortedByDescending { it.addedAt },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomePersonalState(),
    )

    init {
        load()
    }

    fun load(refresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = !refresh && _uiState.value.content == null,
                isRefreshing = refresh,
                errorMessage = null,
            )

            repository.home()
                .onSuccess { content ->
                    _uiState.value = HomeUiState(
                        isLoading = false,
                        isRefreshing = false,
                        content = content,
                        errorMessage = null,
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = error.message ?: "Could not load home feed.",
                    )
                }
        }
    }

    fun removeFromHistory(key: String) {
        viewModelScope.launch { store.removeHistory(key) }
    }

    fun removeFromWatchlist(entry: WatchlistEntry) {
        viewModelScope.launch { store.toggleWatchlist(entry) }
    }

    /** Cards for a row, used by the "view all" grid. */
    fun rowItems(rowId: String): List<MediaCard> =
        _uiState.value.content?.rows?.firstOrNull { it.id == rowId }?.items.orEmpty()

    companion object {
        fun factory(
            repository: MediaRepository,
            store: WatchBoxStore,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(repository, store) as T
        }
    }
}
