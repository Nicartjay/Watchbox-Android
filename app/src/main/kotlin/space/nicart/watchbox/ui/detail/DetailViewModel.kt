package space.nicart.watchbox.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.data.local.WatchHistoryEntry
import space.nicart.watchbox.data.local.WatchlistEntry
import space.nicart.watchbox.domain.EpisodeItem
import space.nicart.watchbox.domain.MediaDetail
import space.nicart.watchbox.domain.MediaRepository

data class DetailUiState(
    val isLoading: Boolean = true,
    val detail: MediaDetail? = null,
    val selectedSeason: Int = 1,
    val episodes: List<EpisodeItem> = emptyList(),
    val episodesLoading: Boolean = false,
    val inWatchlist: Boolean = false,
    val history: WatchHistoryEntry? = null,
    val errorMessage: String? = null,
) {
    /** Where the Play button should resume from. */
    val resumeTarget: Triple<Int, Int, Long>?
        get() = history
            ?.takeIf { !it.isFinished && it.positionMs > 5_000L }
            ?.let { Triple(it.season, it.episode, it.positionMs) }

    /** True when the Play button should read "Resume". */
    val isResume: Boolean get() = resumeTarget != null
}

class DetailViewModel(
    private val repository: MediaRepository,
    private val store: WatchBoxStore,
    private val detailPath: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private var episodeJob: Job? = null

    init {
        load()
        observeStoredState()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            repository.detail(detailPath)
                .onSuccess { detail ->
                    val initialSeason = detail.seasons.firstOrNull()?.season ?: 1
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        detail = detail,
                        selectedSeason = initialSeason,
                        errorMessage = null,
                    )
                    if (detail.isSeries) selectSeason(initialSeason)
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Could not load this title.",
                    )
                }
        }
    }

    /** Keep watchlist + resume state live so the action row always reflects reality. */
    private fun observeStoredState() {
        viewModelScope.launch {
            store.watchlist.collect { list ->
                val detail = _uiState.value.detail
                val key = detail?.subjectId?.ifBlank { detailPath } ?: detailPath
                _uiState.value = _uiState.value.copy(
                    inWatchlist = list.any { it.key == key },
                )
            }
        }
        viewModelScope.launch {
            store.history.collect { entries ->
                val detail = _uiState.value.detail
                val key = detail?.subjectId?.ifBlank { detailPath } ?: detailPath
                _uiState.value = _uiState.value.copy(
                    history = entries.firstOrNull { it.key == key },
                )
            }
        }
    }

    fun selectSeason(season: Int) {
        val detail = _uiState.value.detail ?: return
        episodeJob?.cancel()

        _uiState.value = _uiState.value.copy(
            selectedSeason = season,
            episodesLoading = true,
            episodes = emptyList(),
        )

        episodeJob = viewModelScope.launch {
            val apiCount = detail.seasons
                .firstOrNull { it.season == season }
                ?.episodeCount
                ?: 1
            val episodes = repository.episodes(detail.tmdbId, season, apiCount)
            _uiState.value = _uiState.value.copy(
                episodes = episodes,
                episodesLoading = false,
            )
        }
    }

    fun toggleWatchlist() {
        val detail = _uiState.value.detail ?: return
        viewModelScope.launch {
            store.toggleWatchlist(
                WatchlistEntry(
                    subjectId = detail.subjectId,
                    detailPath = detail.detailPath,
                    title = detail.title,
                    coverUrl = detail.posterUrl,
                    subjectType = detail.subjectType,
                    genre = detail.genres.joinToString(","),
                    imdbRating = detail.imdbRating.orEmpty(),
                    addedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** Mark the whole title watched (or clear it). */
    fun toggleWatched() {
        val detail = _uiState.value.detail ?: return
        viewModelScope.launch {
            val existing = _uiState.value.history
            if (existing != null && existing.isFinished) {
                store.removeHistory(existing.key)
            } else {
                val lastEpisode = _uiState.value.episodes.maxOfOrNull { it.episode } ?: 1
                store.saveHistory(
                    WatchHistoryEntry(
                        subjectId = detail.subjectId,
                        detailPath = detail.detailPath,
                        title = detail.title,
                        coverUrl = detail.posterUrl,
                        subjectType = detail.subjectType,
                        season = _uiState.value.selectedSeason,
                        episode = lastEpisode,
                        maxEpisode = lastEpisode,
                        positionMs = 0L,
                        durationMs = 0L,
                        progress = 1f,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    /** Episodes already watched in the selected season. */
    suspend fun watchedEpisodes(): Set<Int> {
        val detail = _uiState.value.detail ?: return emptySet()
        val key = detail.subjectId.ifBlank { detailPath }
        val entry = store.history.first().firstOrNull { it.key == key } ?: return emptySet()
        if (entry.season != _uiState.value.selectedSeason) return emptySet()
        return (1 until entry.episode).toSet()
    }

    fun retry() = load()

    companion object {
        fun factory(
            repository: MediaRepository,
            store: WatchBoxStore,
            detailPath: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DetailViewModel(repository, store, detailPath) as T
        }
    }
}
