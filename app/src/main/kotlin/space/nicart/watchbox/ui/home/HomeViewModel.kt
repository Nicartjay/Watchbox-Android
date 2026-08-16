package space.nicart.watchbox.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.data.local.WatchHistoryEntry
import space.nicart.watchbox.data.local.WatchlistEntry
import space.nicart.watchbox.domain.AnimeRepository
import space.nicart.watchbox.domain.friendlyMessage
import space.nicart.watchbox.domain.HomeFeed
import space.nicart.watchbox.extension.ExtensionManager

data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val feed: HomeFeed? = null,
    val errorMessage: String? = null,
    /** True when nothing is installed yet, which needs an onboarding prompt. */
    val hasNoSources: Boolean = false,
    /**
     * True when not even a repository is configured.
     *
     * Distinct from [hasNoSources]: with no repository the extension list is empty, so
     * sending the user there is a dead end. They need to add a repository first.
     */
    val hasNoRepos: Boolean = false,
)

/** Continue Watching + My List, derived from persisted state. */
data class HomePersonalState(
    val continueWatching: List<WatchHistoryEntry> = emptyList(),
    val myList: List<WatchlistEntry> = emptyList(),
)

class HomeViewModel(
    private val repository: AnimeRepository,
    private val extensions: ExtensionManager,
    private val store: WatchBoxStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * Finished titles are dropped from Continue Watching — a completed episode
     * sitting at 100% is noise, not a resume candidate.
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
        // The feed depends on which extensions are loaded, so rebuild whenever
        // that set changes rather than only on first composition.
        viewModelScope.launch {
            extensions.installed.collect { load() }
        }

        // Repositories are watched separately, because adding one changes neither the
        // installed set nor the feed - only which dead end the empty state should describe.
        //
        // Without this the screen kept saying "add a repository" after one had been added:
        // `hasRepos` was read once inside load(), and nothing re-ran it, so the prompt still
        // pointed at Settings instead of the extension list.
        viewModelScope.launch {
            store.settings
                .map { it.repos.isNotEmpty() }
                .distinctUntilChanged()
                .collect { hasRepos ->
                    if (_uiState.value.hasNoSources) {
                        _uiState.value = _uiState.value.copy(hasNoRepos = !hasRepos)
                    }
                }
        }

        // The artwork language rebuilds the feed, not just the artwork client's cache.
        //
        // Clearing that cache is necessary but not sufficient: the cards already in state
        // hold the poster and logo URLs resolved under the previous language, and nothing
        // re-resolves them. So the setting appeared to do nothing until the feed happened to
        // reload for some other reason.
        //
        // `drop(1)` skips the value present at startup, which the initial load already used.
        viewModelScope.launch {
            store.settings
                .map { it.artworkLanguage }
                .distinctUntilChanged()
                .drop(1)
                .collect { load(refresh = true) }
        }
    }

    fun load(refresh: Boolean = false) {
        viewModelScope.launch {
            val hasRepos = store.currentSettings().repos.isNotEmpty()

            if (!repository.hasSources()) {
                _uiState.value = HomeUiState(
                    isLoading = false,
                    hasNoSources = true,
                    hasNoRepos = !hasRepos,
                    feed = null,
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isLoading = !refresh && _uiState.value.feed == null,
                isRefreshing = refresh,
                errorMessage = null,
                hasNoSources = false,
            )

            repository.homeFeed()
                .onSuccess { feed ->
                    _uiState.value = HomeUiState(
                        isLoading = false,
                        isRefreshing = false,
                        feed = feed,
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = error.friendlyMessage(),
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

    companion object {
        fun factory(
            repository: AnimeRepository,
            extensions: ExtensionManager,
            store: WatchBoxStore,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(repository, extensions, store) as T
        }
    }
}
