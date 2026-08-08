package space.nicart.watchbox.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.data.local.WatchHistoryEntry
import space.nicart.watchbox.data.local.WatchlistEntry
import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.domain.AnimeDetail
import space.nicart.watchbox.domain.AnimeRepository
import space.nicart.watchbox.domain.EpisodeEntry

data class DetailUiState(
    val isLoading: Boolean = true,
    val detail: AnimeDetail? = null,
    val inWatchlist: Boolean = false,
    val suggestions: List<AnimeCard> = emptyList(),
    val suggestionsLoading: Boolean = false,
    val history: WatchHistoryEntry? = null,
    val errorMessage: String? = null,
) {
    /** The episode the play button should open, and where to resume from. */
    val resumeTarget: Pair<EpisodeEntry, Long>?
        get() {
            val entry = history?.takeIf { !it.isFinished && it.positionMs > 5_000L }
                ?: return null
            val episode = detail?.episodes?.firstOrNull { it.url == entry.episodeUrl }
                ?: return null
            return episode to entry.positionMs
        }

    /** First unwatched episode, or the first episode when nothing is watched. */
    val startTarget: EpisodeEntry?
        get() = resumeTarget?.first
            ?: detail?.episodes?.firstOrNull()

    val isResume: Boolean get() = resumeTarget != null

    /** Episode URLs already finished, for the watched tick. */
    val watchedEpisodeUrls: Set<String>
        get() {
            val entry = history ?: return emptySet()
            val episodes = detail?.episodes ?: return emptySet()
            val index = episodes.indexOfFirst { it.url == entry.episodeUrl }
            if (index < 0) return emptySet()
            // Everything before the current episode counts as watched, plus the
            // current one when it was finished.
            val watched = episodes.take(index).map { it.url }.toMutableSet()
            if (entry.isFinished) watched += entry.episodeUrl
            return watched
        }
}

class DetailViewModel(
    private val repository: AnimeRepository,
    private val store: WatchBoxStore,
    private val sourceId: Long,
    private val animeUrl: String,
) : ViewModel() {

    private var suggestionsJob: Job? = null

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    init {
        load()
        observeStoredState()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            repository.detail(sourceId, animeUrl)
                .onSuccess { detail ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        detail = detail,
                        errorMessage = null,
                    )
                    // Fetched after the detail is on screen: tier 2 is a second
                    // network round-trip, and the episode list should not wait on
                    // a section the user may never scroll to.
                    loadSuggestions(detail)
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Could not load this title.",
                    )
                }
        }
    }

    /** Keeps the action row honest as the user watches or saves elsewhere. */
    private fun observeStoredState() {
        val key = "$sourceId::$animeUrl"

        viewModelScope.launch {
            store.watchlist.collect { list ->
                _uiState.value = _uiState.value.copy(
                    inWatchlist = list.any { it.key == key },
                )
            }
        }
        viewModelScope.launch {
            store.history.collect { entries ->
                _uiState.value = _uiState.value.copy(
                    history = entries.firstOrNull { it.key == key },
                )
            }
        }
    }

    fun toggleWatchlist() {
        val detail = _uiState.value.detail ?: return
        viewModelScope.launch {
            store.toggleWatchlist(
                WatchlistEntry(
                    sourceId = detail.sourceId,
                    animeUrl = detail.url,
                    title = detail.title,
                    posterUrl = detail.posterUrl,
                    sourceName = detail.sourceName,
                    addedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** Marks the whole title watched, or clears its history entirely. */
    fun toggleWatched() {
        val detail = _uiState.value.detail ?: return
        val existing = _uiState.value.history

        viewModelScope.launch {
            if (existing != null && existing.isFinished) {
                store.removeHistory(existing.key)
                return@launch
            }

            val last = detail.episodes.lastOrNull() ?: return@launch
            store.saveHistory(
                WatchHistoryEntry(
                    sourceId = detail.sourceId,
                    animeUrl = detail.url,
                    title = detail.title,
                    posterUrl = detail.posterUrl,
                    sourceName = detail.sourceName,
                    episodeUrl = last.url,
                    episodeName = last.displayName,
                    episodeNumber = last.number,
                    progress = 1f,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun loadSuggestions(detail: AnimeDetail) {
        suggestionsJob?.cancel()
        _uiState.value = _uiState.value.copy(suggestionsLoading = true)

        suggestionsJob = viewModelScope.launch {
            val found = repository.suggestions(
                sourceId = detail.sourceId,
                animeUrl = detail.url,
                title = detail.title,
            )
            _uiState.value = _uiState.value.copy(
                suggestions = found,
                suggestionsLoading = false,
            )
        }
    }

    fun retry() = load()

    companion object {
        fun factory(
            repository: AnimeRepository,
            store: WatchBoxStore,
            sourceId: Long,
            animeUrl: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DetailViewModel(repository, store, sourceId, animeUrl) as T
        }
    }
}
