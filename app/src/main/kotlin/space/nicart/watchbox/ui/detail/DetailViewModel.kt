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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import space.nicart.watchbox.domain.StreamOption
import space.nicart.watchbox.download.DownloadController
import space.nicart.watchbox.download.DownloadStorage
import space.nicart.watchbox.ui.download.DownloadPickerState
import space.nicart.watchbox.ui.download.EpisodeDownloadStatus
import space.nicart.watchbox.ui.download.buildStatusMap
import androidx.media3.common.util.UnstableApi
import space.nicart.watchbox.domain.AnimeStatus

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
    /**
     * Whether the hero offers a mute toggle over the trailer.
     *
     * Read alongside the trailer rather than collected, for the same reason: the hero is
     * built when the page opens, and a control appearing mid-visit because a setting
     * changed elsewhere would be a surprise. Only ever true when [trailer] is non-null,
     * so the hero needs no second condition of its own.
     */
    val trailerMuteButton: Boolean = false,
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

/**
 * Marked because the download controller it holds is built on Media3's offline API, which is
 * annotated unstable in its entirety.
 */
@UnstableApi
class DetailViewModel(
    private val repository: AnimeRepository,
    private val store: WatchBoxStore,
    private val countryResolver: CountryResolver,
    private val downloads: DownloadController,
    private val downloadStorage: DownloadStorage,
    private val sourceId: Long,
    private val animeUrl: String,
) : ViewModel() {

    private var suggestionsJob: Job? = null
    private var extrasJob: Job? = null
    private var trailerJob: Job? = null
    private var downloadResolveJob: Job? = null

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private val _picker = MutableStateFlow<DownloadPickerState>(DownloadPickerState.Hidden)

    /** The download quality prompt, shown when an episode's download button is pressed. */
    val picker: StateFlow<DownloadPickerState> = _picker.asStateFlow()

    /**
     * Per-episode download status, keyed by episode URL.
     *
     * Combines the registry with the engine's live progress, because neither alone can say
     * what an episode's button should look like: one knows whether a download exists, the
     * other how far it has got.
     */
    val downloadStatus: StateFlow<Map<String, EpisodeDownloadStatus>> = combine(
        store.downloads,
        downloads.progress,
    ) { entries, progress ->
        buildStatusMap(
            entries = entries,
            progress = progress,
            sourceId = sourceId,
            animeUrl = animeUrl,
            mountedVolumes = downloadStorage.volumes().map { it.id }.toSet(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyMap(),
    )

    init {
        load()
        observeStoredState()
        // Safe to call repeatedly; it latches internally. Done here rather than at app
        // startup so the download database is only opened once downloads are actually in
        // view, which most sessions never do.
        downloads.start()
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
                    // Falls back to what has been downloaded before reporting a failure.
                    //
                    // Fetching the detail needs the network, so with none the page showed an
                    // error and an empty episode list even where every episode on it was
                    // already on disk. Rebuilt from the registry instead, which is enough to
                    // list and play them - it holds the title, the poster and one entry per
                    // downloaded episode.
                    val offline = offlineDetail()
                    if (offline != null) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            detail = offline,
                            errorMessage = null,
                        )
                        return@onFailure
                    }

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.friendlyMessage(),
                    )
                }
        }
    }

    /**
     * A detail assembled from downloaded episodes, for when the source cannot be reached.
     *
     * Deliberately sparse. There is no overview, no artwork beyond the poster already stored
     * and no season structure, because none of that was kept - only what a download needs to be
     * listed and opened. Returns null when nothing has been downloaded for this title, so the
     * real error is reported rather than an empty page.
     */
    private suspend fun offlineDetail(): AnimeDetail? {
        val entries = store.currentDownloads()
            .filter { it.sourceId == sourceId && it.animeUrl == animeUrl && it.isComplete }
            .sortedBy { it.episodeNumber }
        if (entries.isEmpty()) return null

        val first = entries.first()
        return AnimeDetail(
            sourceId = sourceId,
            url = animeUrl,
            title = first.title,
            posterUrl = first.posterUrl,
            sourceName = first.sourceName,
            description = "",
            author = null,
            artist = null,
            genres = emptyList(),
            // Unknown rather than guessed: nothing about a download says whether the show
            // has finished airing.
            status = AnimeStatus.UNKNOWN,
            episodes = entries.map { entry ->
                EpisodeEntry(
                    url = entry.episodeUrl,
                    name = entry.episodeName,
                    number = entry.episodeNumber,
                    dateUpload = 0L,
                    scanlator = null,
                )
            },
        )
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
            // Both read from the same snapshot, so the button flag cannot disagree with
            // the trailer it belongs to.
            val settings = store.currentSettings()
            if (!settings.autoplayTrailers) return@launch

            val trailer = repository.trailer(tmdbId = tmdbId, isMovie = detail.isMovie)
                ?: return@launch

            // Guarded like the others: the user may have navigated on, or a reload
            // replaced the detail with a different title, while this was in flight.
            if (_uiState.value.detail?.key != detail.key) return@launch
            _uiState.value = _uiState.value.copy(
                trailer = trailer,
                trailerMuteButton = settings.trailerMuteButton,
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

    // ------------------------------------------------------------ downloads

    /**
     * Resolves the streams for [episode] and opens the quality prompt.
     *
     * The same call the player makes, and just as slow - the source resolves every server
     * inside one opaque request - so the prompt opens on a spinner rather than after one.
     */
    fun requestDownload(episode: EpisodeEntry) {
        downloadResolveJob?.cancel()
        _picker.value = DownloadPickerState.Resolving(episode.url)

        downloadResolveJob = viewModelScope.launch {
            repository.streams(sourceId, episode.url)
                .onSuccess { streams ->
                    // Dropped if the prompt was dismissed while this was in flight, so a
                    // late result cannot reopen a dialog the user closed.
                    val pending = _picker.value
                    if (pending !is DownloadPickerState.Resolving ||
                        pending.episodeUrl != episode.url
                    ) {
                        return@onSuccess
                    }

                    _picker.value = if (streams.isEmpty()) {
                        DownloadPickerState.Failed(NO_STREAMS)
                    } else {
                        DownloadPickerState.Ready(
                            episodeUrl = episode.url,
                            episodeLabel = episode.displayName,
                            streams = streams,
                        )
                    }
                }
                .onFailure { error ->
                    _picker.value = DownloadPickerState.Failed(error.friendlyMessage())
                }
        }
    }

    /** Queues the chosen stream and closes the prompt. */
    fun confirmDownload(stream: StreamOption) {
        val pending = _picker.value as? DownloadPickerState.Ready ?: return
        val detail = _uiState.value.detail ?: return
        val episode = detail.episodes.firstOrNull { it.url == pending.episodeUrl } ?: return

        downloads.enqueue(
            sourceId = sourceId,
            animeUrl = animeUrl,
            title = detail.title,
            posterUrl = detail.posterUrl,
            sourceName = detail.sourceName,
            episode = episode,
            stream = stream,
        )
        _picker.value = DownloadPickerState.Hidden
    }

    fun dismissDownloadPicker() {
        downloadResolveJob?.cancel()
        _picker.value = DownloadPickerState.Hidden
    }

    fun pauseDownload(episodeUrl: String) =
        downloads.pause(downloadKey(episodeUrl))

    fun resumeDownload(episodeUrl: String) =
        downloads.resume(downloadKey(episodeUrl))

    fun deleteDownload(episodeUrl: String) =
        downloads.remove(downloadKey(episodeUrl))

    private fun downloadKey(episodeUrl: String) = "$sourceId::$animeUrl::$episodeUrl"

    companion object {
        fun factory(
            repository: AnimeRepository,
            store: WatchBoxStore,
            countryResolver: CountryResolver,
            downloads: DownloadController,
            downloadStorage: DownloadStorage,
            sourceId: Long,
            animeUrl: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DetailViewModel(
                    repository,
                    store,
                    countryResolver,
                    downloads,
                    downloadStorage,
                    sourceId,
                    animeUrl,
                ) as T
        }
    }
}

/** Shown when a source resolves but offers nothing downloadable. */
private const val NO_STREAMS = "This source returned no downloadable streams."
