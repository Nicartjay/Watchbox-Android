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
import space.nicart.watchbox.domain.friendlyMessage
import space.nicart.watchbox.extension.ExtensionManager

data class SearchUiState(
    val query: String = "",
    /** Results grouped per source; relevance is not comparable across sources. */
    val results: List<AnimeRow> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val hasNoSources: Boolean = false,
    val errorMessage: String? = null,
    /** Every installed source, for the scope picker. */
    val sources: List<SearchSource> = emptyList(),
    /** Null means search every source. */
    val selectedSourceId: Long? = null,
)

/** One source the search can be narrowed to. */
data class SearchSource(val id: Long, val name: String)

/**
 * The source a search should actually query directly.
 *
 * A single installed source is not a multi-source search just because the "All" scope is
 * represented by null. Treating it as one sent the phone through [AnimeRepository.searchAll],
 * while TV explicitly selected Rentaro and used [AnimeRepository.search]. Besides doing
 * unnecessary fan-out bookkeeping, the all-source path has a per-source timeout intended to
 * protect a group from one slow extension. With Rentaro as the only source that timeout merely
 * turned a slow response into an empty result, which is why the same extension worked on TV but
 * appeared not to search on a phone.
 */
internal fun SearchUiState.effectiveSourceId(): Long? =
    selectedSourceId ?: sources.singleOrNull()?.id

class SearchViewModel(
    private val repository: AnimeRepository,
    private val store: WatchBoxStore,
    private val extensions: ExtensionManager,
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

        // Rebuilt when extensions change so installing one updates the picker
        // without leaving the screen.
        viewModelScope.launch {
            extensions.installed.collect {
                val sources = extensions.catalogueSources()
                    .map { source -> SearchSource(source.id, source.name) }

                _uiState.value = _uiState.value.copy(
                    sources = sources,
                    hasNoSources = sources.isEmpty(),
                    // Drop a selection whose source has been uninstalled, so the
                    // screen cannot be stuck searching something that is gone.
                    selectedSourceId = _uiState.value.selectedSourceId
                        ?.takeIf { id -> sources.any { it.id == id } },
                )
            }
        }
    }

    /**
     * Narrows the search to one source, or widens it to all when null.
     *
     * Re-runs immediately with the existing query rather than waiting for another
     * keystroke, since changing scope is itself the request.
     */
    fun onSelectSource(sourceId: Long?) {
        if (sourceId == _uiState.value.selectedSourceId) return
        _uiState.value = _uiState.value.copy(selectedSourceId = sourceId)

        val query = _uiState.value.query
        if (query.isBlank()) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(query) }
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
                errorMessage = null,
            )
            return
        }

        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
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
        val sourceId = _uiState.value.effectiveSourceId()
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        var errorMessage: String? = null
        val rows = if (sourceId == null) {
            repository.searchAll(query)
        } else {
            // Wrapped in the same row shape so the results list renders
            // identically whether one source or all were queried.
            repository.search(sourceId, query).fold(
                onSuccess = { items ->
                    if (items.isEmpty()) return@fold emptyList()
                    val name = _uiState.value.sources
                        .firstOrNull { it.id == sourceId }?.name ?: ""
                    listOf(
                        AnimeRow(
                            sourceId = sourceId,
                            sourceName = name,
                            title = name,
                            items = items,
                        ),
                    )
                },
                onFailure = { error ->
                    errorMessage = error.friendlyMessage()
                    emptyList()
                },
            )
        }

        // Extension calls are blocking and some compatibility wrappers catch cancellation.
        // A result can therefore arrive after the query or source changed. Never let that stale
        // response replace the newer request's screen.
        val current = _uiState.value
        if (current.query != query || current.effectiveSourceId() != sourceId) return

        _uiState.value = _uiState.value.copy(
            results = rows,
            isLoading = false,
            hasSearched = true,
            errorMessage = errorMessage,
        )
    }

    fun retry() {
        val query = _uiState.value.query
        if (query.isBlank()) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(query) }
    }

    fun clearRecent() {
        viewModelScope.launch { store.clearRecentSearches() }
    }

    companion object {
        fun factory(
            repository: AnimeRepository,
            store: WatchBoxStore,
            extensions: ExtensionManager,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SearchViewModel(repository, store, extensions) as T
        }
    }
}

/**
 * How long typing has to stop before a search runs.
 *
 * Long, and deliberately so: this fans out to every installed source at once, so each
 * keystroke that slips through is one request per extension. At the previous 450ms a
 * normal typing speed fired several rounds of that before the query was finished, and the
 * earlier rounds were all thrown away.
 *
 * Submitting from the keyboard bypasses this entirely, so anyone who wants results sooner
 * is one key away from them.
 */
private const val SEARCH_DEBOUNCE_MS = 1_500L
