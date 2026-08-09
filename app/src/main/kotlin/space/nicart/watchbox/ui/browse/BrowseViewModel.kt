package space.nicart.watchbox.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.domain.AnimeRepository
import space.nicart.watchbox.extension.ExtensionManager

/** One installed, browsable source. */
data class SourceEntry(
    val id: Long,
    val name: String,
    val lang: String,
    val supportsLatest: Boolean,
)

/**
 * Which listing a source is being browsed by.
 *
 * SEARCH is a mode rather than a separate screen because a query and a filter set
 * are the same request to the source - `getSearchAnime` takes both - and pagination
 * has to work identically for all three.
 */
enum class BrowseMode { POPULAR, LATEST, SEARCH }

data class BrowseUiState(
    val items: List<AnimeCard> = emptyList(),
    val mode: BrowseMode = BrowseMode.POPULAR,
    val page: Int = 1,
    val isLoading: Boolean = false,
    val isAppending: Boolean = false,
    val hasMore: Boolean = true,
    val errorMessage: String? = null,
    val query: String = "",
    /** Flattened for display; the live list stays in the ViewModel. */
    val filters: List<FilterEntry> = emptyList(),
    val filterPanelOpen: Boolean = false,
    val hasFilters: Boolean = false,
    val filtersActive: Boolean = false,
)

/** Backs the source list on the Browse tab. */
class SourceListViewModel(extensions: ExtensionManager) : ViewModel() {

    val sources: StateFlow<List<SourceEntry>> = extensions.installed
        .map { _ ->
            extensions.catalogueSources().map { source ->
                SourceEntry(
                    id = source.id,
                    name = source.name,
                    lang = source.lang,
                    supportsLatest = runCatching { source.supportsLatest }.getOrDefault(false),
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    companion object {
        fun factory(extensions: ExtensionManager): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SourceListViewModel(extensions) as T
            }
    }
}

/**
 * Paged browse for one source.
 *
 * Pages are appended rather than replaced, and [hasMore] is inferred from an
 * empty page: the ABI's `AnimesPage.hasNextPage` is unreliable across sources, so
 * running dry is the only dependable signal.
 */
class BrowseViewModel(
    private val repository: AnimeRepository,
    private val sourceId: Long,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowseUiState())
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var searchDebounce: Job? = null

    /**
     * The live filter list handed over by the source.
     *
     * Mutated in place by the UI - the ABI requires the very same objects to be
     * passed back to `getSearchAnime`, so this cannot be a snapshot.
     */
    private var filters: AnimeFilterList = AnimeFilterList()

    init {
        loadFilters()
        load(BrowseMode.POPULAR, reset = true)
    }

    private fun loadFilters() {
        filters = repository.filterList(sourceId)
        _uiState.value = _uiState.value.copy(
            filters = filters.flattenForDisplay(),
            hasFilters = filters.isNotEmpty(),
        )
    }

    fun setMode(mode: BrowseMode) {
        if (mode == _uiState.value.mode) return
        load(mode, reset = true)
    }

    /**
     * Debounced per-source search.
     *
     * Clearing the box returns to the popular listing rather than showing an empty
     * result, so the screen is never left blank by deleting a query.
     */
    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        searchDebounce?.cancel()

        if (query.isBlank()) {
            load(BrowseMode.POPULAR, reset = true)
            return
        }

        searchDebounce = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            load(BrowseMode.SEARCH, reset = true)
        }
    }

    fun submitQuery() {
        searchDebounce?.cancel()
        if (_uiState.value.query.isBlank()) return
        load(BrowseMode.SEARCH, reset = true)
    }

    fun setFilterPanelOpen(open: Boolean) {
        _uiState.value = _uiState.value.copy(filterPanelOpen = open)
    }

    /** Writes a filter value through to the source's own filter object. */
    fun onFilterChange(path: List<Int>, value: Any?) {
        filters.applyFilterChange(path, value)
        // Re-flatten so the UI observes the mutation; identity is unchanged.
        _uiState.value = _uiState.value.copy(
            filters = filters.flattenForDisplay(),
            filtersActive = filters.hasActiveFilters(repository.filterList(sourceId)),
        )
    }

    /** Applies the current filter set, which is always a search request. */
    fun applyFilters() {
        _uiState.value = _uiState.value.copy(filterPanelOpen = false)
        load(BrowseMode.SEARCH, reset = true)
    }

    fun resetFilters() {
        // A fresh list from the source is the only reliable way back to defaults;
        // the previous objects have been mutated and cannot be restored.
        loadFilters()
        _uiState.value = _uiState.value.copy(filtersActive = false)
        load(
            if (_uiState.value.query.isBlank()) BrowseMode.POPULAR else BrowseMode.SEARCH,
            reset = true,
        )
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isAppending || !state.hasMore) return
        load(state.mode, reset = false)
    }

    fun retry() = load(_uiState.value.mode, reset = true)

    private fun load(mode: BrowseMode, reset: Boolean) {
        loadJob?.cancel()

        val nextPage = if (reset) 1 else _uiState.value.page + 1

        _uiState.value = _uiState.value.copy(
            mode = mode,
            isLoading = reset,
            isAppending = !reset,
            errorMessage = null,
            items = if (reset) emptyList() else _uiState.value.items,
        )

        loadJob = viewModelScope.launch {
            val result = when (mode) {
                BrowseMode.POPULAR -> repository.popular(sourceId, nextPage)
                BrowseMode.LATEST -> repository.latest(sourceId, nextPage)
                BrowseMode.SEARCH -> repository.search(
                    sourceId = sourceId,
                    query = _uiState.value.query,
                    page = nextPage,
                    filters = filters,
                )
            }

            result
                .onSuccess { page ->
                    _uiState.value = _uiState.value.copy(
                        items = if (reset) page else _uiState.value.items + page,
                        page = nextPage,
                        isLoading = false,
                        isAppending = false,
                        hasMore = page.isNotEmpty(),
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isAppending = false,
                        // Keep whatever is already on screen; only report the failure.
                        errorMessage = error.message ?: "This source could not be reached.",
                        hasMore = false,
                    )
                }
        }
    }

    companion object {
        fun factory(
            repository: AnimeRepository,
            sourceId: Long,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BrowseViewModel(repository, sourceId) as T
        }
    }
}

/**
 * Debounce before a per-source search fires.
 *
 * Shorter than the global search's 450ms because only one source is queried here,
 * so a wasted request costs far less.
 */
private const val SEARCH_DEBOUNCE_MS = 350L
