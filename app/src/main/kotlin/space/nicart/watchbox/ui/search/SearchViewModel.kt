package space.nicart.watchbox.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.domain.AnimeRepository
import space.nicart.watchbox.domain.AnimeRow

data class SearchUiState(
    val query: String = "",
    /** Results grouped per source; relevance is not comparable across sources. */
    val results: List<AnimeRow> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val hasNoSources: Boolean = false,
)

class SearchViewModel(
    private val repository: AnimeRepository,
    private val store: WatchBoxStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            store.recentSearches.collect { terms ->
                _uiState.value = _uiState.value.copy(recentSearches = terms)
            }
        }
        _uiState.value = _uiState.value.copy(hasNoSources = !repository.hasSources())
    }

    /**
     * Debounced live search.
     *
     * The delay is longer than a typical single-API search because every
     * keystroke fans out to every installed source, and each one is a separate
     * scrape.
     */
    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        searchJob?.cancel()

        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(
                results = emptyList(),
                isLoading = false,
                hasSearched = false,
            )
            return
        }

        searchJob = viewModelScope.launch {
            delay(450)
            runSearch(query)
        }
    }

    fun submit() {
        val query = _uiState.value.query
        if (query.isBlank()) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            store.addRecentSearch(query)
            runSearch(query)
        }
    }

    private suspend fun runSearch(query: String) {
        _uiState.value = _uiState.value.copy(isLoading = true)
        val rows = repository.searchAll(query)
        _uiState.value = _uiState.value.copy(
            results = rows,
            isLoading = false,
            hasSearched = true,
        )
    }

    fun clearRecent() {
        viewModelScope.launch { store.clearRecentSearches() }
    }

    companion object {
        fun factory(
            repository: AnimeRepository,
            store: WatchBoxStore,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SearchViewModel(repository, store) as T
        }
    }
}
