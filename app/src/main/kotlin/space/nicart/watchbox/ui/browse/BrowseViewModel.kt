package space.nicart.watchbox.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
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

/** Which listing a source is being browsed by. */
enum class BrowseMode { POPULAR, LATEST }

data class BrowseUiState(
    val items: List<AnimeCard> = emptyList(),
    val mode: BrowseMode = BrowseMode.POPULAR,
    val page: Int = 1,
    val isLoading: Boolean = false,
    val isAppending: Boolean = false,
    val hasMore: Boolean = true,
    val errorMessage: String? = null,
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

    init {
        load(BrowseMode.POPULAR, reset = true)
    }

    fun setMode(mode: BrowseMode) {
        if (mode == _uiState.value.mode) return
        load(mode, reset = true)
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
