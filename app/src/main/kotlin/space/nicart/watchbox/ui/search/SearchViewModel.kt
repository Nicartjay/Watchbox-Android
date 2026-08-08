package space.nicart.watchbox.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.data.model.SubjectType
import space.nicart.watchbox.domain.MediaCard
import space.nicart.watchbox.domain.MediaRepository

/** Content-type filter chips. */
enum class SearchFilter(val label: String, val subjectType: Int?) {
    ALL("All", null),
    MOVIES("Movies", SubjectType.MOVIE),
    SERIES("Series", SubjectType.TV),
}

data class SearchUiState(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.ALL,
    val results: List<MediaCard> = emptyList(),
    val trending: List<MediaCard> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val hasMore: Boolean = false,
    val errorMessage: String? = null,
)

class SearchViewModel(
    private val repository: MediaRepository,
    private val store: WatchBoxStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var page = 1

    init {
        viewModelScope.launch {
            store.recentSearches.collect { terms ->
                _uiState.value = _uiState.value.copy(recentSearches = terms)
            }
        }
        viewModelScope.launch {
            val trending = repository.trending()
            _uiState.value = _uiState.value.copy(trending = trending)
        }
    }

    /** Debounced live search (300ms), so typing doesn't spam the API. */
    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        searchJob?.cancel()

        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(
                results = emptyList(),
                isLoading = false,
                errorMessage = null,
            )
            return
        }

        searchJob = viewModelScope.launch {
            delay(300)
            runSearch(query, reset = true)
        }
    }

    fun submit() {
        val query = _uiState.value.query
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            store.addRecentSearch(query)
            runSearch(query, reset = true)
        }
    }

    fun setFilter(filter: SearchFilter) {
        if (filter == _uiState.value.filter) return
        _uiState.value = _uiState.value.copy(filter = filter)
        val query = _uiState.value.query
        if (query.isNotBlank()) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch { runSearch(query, reset = true) }
        }
    }

    fun loadMore() {
        val query = _uiState.value.query
        if (query.isBlank() || _uiState.value.isLoading || !_uiState.value.hasMore) return
        searchJob = viewModelScope.launch { runSearch(query, reset = false) }
    }

    private suspend fun runSearch(query: String, reset: Boolean) {
        if (reset) page = 1 else page++

        _uiState.value = _uiState.value.copy(
            isLoading = reset,
            errorMessage = null,
        )

        repository.search(query, page)
            .onSuccess { results ->
                val filtered = _uiState.value.filter.subjectType
                    ?.let { type -> results.filter { it.subjectType == type } }
                    ?: results

                _uiState.value = _uiState.value.copy(
                    results = if (reset) filtered else _uiState.value.results + filtered,
                    isLoading = false,
                    hasMore = results.isNotEmpty(),
                    errorMessage = null,
                )
            }
            .onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = error.message,
                )
            }
    }

    fun clearRecent() {
        viewModelScope.launch { store.clearRecentSearches() }
    }

    companion object {
        fun factory(
            repository: MediaRepository,
            store: WatchBoxStore,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SearchViewModel(repository, store) as T
        }
    }
}
