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
import space.nicart.watchbox.data.remote.CountryResolver
import space.nicart.watchbox.data.remote.Trailer
import space.nicart.watchbox.domain.AnimeRepository
import space.nicart.watchbox.domain.EpisodeEntry
import space.nicart.watchbox.domain.friendlyMessage

data class DetailUiState(
    val isLoading: Boolean = true,
    val detail: AnimeDetail? = null,
    val inWatchlist: Boolean = false,
    val suggestions: List<AnimeCard> = emptyList(),
    val suggestionsLoading: Boolean = false,
    val history: WatchHistoryEntry? = null,
    val errorMessage: String? = null,
    /** This title's page on the source's site, when it has one. */
    val webUrl: String? = null,
    /**
     * Hero trailer, once resolved and only when the setting allows it.
     *
     * Null covers every reason there is no video - setting off, no TMDB match,
     * nothing published, service unreachable - because the hero does the same thing
     * in all of them: shows its backdrop.
     */
    val trailer: Trailer? = null,
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
    private val countryResolver: CountryResolver,
    private val sourceId: Long,
    private val animeUrl: String,
) : ViewModel() {

    private var suggestionsJob: Job? = null
    private var extrasJob: Job? = null
    private var trailerJob: Job? = null

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
                        // Resolved once here rather than per recomposition: it calls into
                        // extension code, which must not run during composition.
                        webUrl = repository.titleWebUrl(sourceId, animeUrl),
                    )
                    // Fetched after the detail is on screen: tier 2 is a second
                    // network round-trip, and the episode list should not wait on
                    // a section the user may never scroll to.
                    loadSuggestions(detail)
                    loadExtras(detail)
                    loadTrailer(detail)
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.friendlyMessage(),
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

    /**
     * Trailers, availability, reviews and tags.
     *
     * After the page rather than with it: the episode list is what the user opened this for,
     * and it should not wait on a trailer list. The sections appear as the data arrives.
     *
     * The country is resolved from the network, not the device locale: a locale reflects the
     * language the user picked, so an English-language phone in Manila reports US and would
     * show the wrong catalogue. TMDB carries 129 countries and they differ substantially -
     * one PH provider against seven for US on the same title.
     */
    private fun loadExtras(detail: AnimeDetail) {
        extrasJob?.cancel()
        val tmdbId = detail.tmdbId ?: return

        extrasJob = viewModelScope.launch {
            val extras = repository.extras(
                tmdbId = tmdbId,
                isMovie = detail.isMovie,
                country = countryResolver.country(),
            )
            // Guarded: the user may have navigated on, or a reload may have replaced the
            // detail with a different title, while this was in flight.
            if (_uiState.value.detail?.key != detail.key) return@launch
            _uiState.value = _uiState.value.copy(
                detail = _uiState.value.detail?.copy(extras = extras),
            )

            // Second pass, on the same job so navigating away cancels it. External
            // scores come from a different service than everything above, and most
            // titles have none, so the sections that did arrive are shown first
            // rather than made to wait on a lookup that often adds nothing.
            val withRatings = repository.withRatings(
                extras = extras,
                tmdbId = tmdbId,
                isMovie = detail.isMovie,
            )
            if (withRatings.ratings.isEmpty()) return@launch
            if (_uiState.value.detail?.key != detail.key) return@launch
            _uiState.value = _uiState.value.copy(
                detail = _uiState.value.detail?.copy(extras = withRatings),
            )
        }
    }

    /**
     * Resolves the hero trailer, when the setting allows one.
     *
     * Read once here rather than collected: the hero is built when the page opens,
     * and a trailer appearing because a setting changed in another screen mid-visit
     * would be a surprise rather than a feature.
     *
     * Its own job so it cancels with navigation, and separate from [loadExtras]
     * because it is a different service - a slow trailer lookup must not delay the
     * provider list, and a slow extras call must not delay the hero.
     */
    private fun loadTrailer(detail: AnimeDetail) {
        trailerJob?.cancel()
        val tmdbId = detail.tmdbId ?: return

        trailerJob = viewModelScope.launch {
            if (!store.currentSettings().autoplayTrailers) return@launch

            val trailer = repository.trailer(tmdbId = tmdbId, isMovie = detail.isMovie)
                ?: return@launch

            // Guarded like the others: the user may have navigated on, or a reload
            // replaced the detail with a different title, while this was in flight.
            if (_uiState.value.detail?.key != detail.key) return@launch
            _uiState.value = _uiState.value.copy(trailer = trailer)
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
            countryResolver: CountryResolver,
            sourceId: Long,
            animeUrl: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DetailViewModel(repository, store, countryResolver, sourceId, animeUrl) as T
        }
    }
}
