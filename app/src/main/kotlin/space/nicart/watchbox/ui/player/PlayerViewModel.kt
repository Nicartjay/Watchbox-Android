package space.nicart.watchbox.ui.player

import android.app.Application
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
import space.nicart.watchbox.data.local.WatchHistoryEntry
import space.nicart.watchbox.domain.EpisodeItem
import space.nicart.watchbox.domain.MediaDetail
import space.nicart.watchbox.domain.MediaRepository
import space.nicart.watchbox.domain.PlayableStream
import space.nicart.watchbox.domain.PlaybackSource

/** Aspect-ratio modes, cycled by the player's aspect button. */
enum class AspectMode(val label: String) {
    FIT("Fit"),
    FILL("Fill"),
    ZOOM("Zoom"),
    ;

    fun next(): AspectMode = entries[(ordinal + 1) % entries.size]
}

data class PlayerUiState(
    val isResolving: Boolean = true,
    val detail: MediaDetail? = null,
    val source: PlaybackSource? = null,
    val selectedStream: PlayableStream? = null,
    val selectedSubtitleIndex: Int = -1,
    val selectedAudioIndex: Int = -1,
    val season: Int = 1,
    val episode: Int = 1,
    val episodes: List<EpisodeItem> = emptyList(),
    val resumeMs: Long = 0L,
    val speed: Float = 1f,
    val aspectMode: AspectMode = AspectMode.FIT,
    val locked: Boolean = false,
    val autoPlayNext: Boolean = true,
    val errorMessage: String? = null,
    val resolvingServer: String? = null,
) {
    val title: String get() = detail?.title.orEmpty()

    val episodeLabel: String?
        get() = if (detail?.isSeries == true) {
            val name = episodes.firstOrNull { it.episode == episode && it.season == season }?.title
            "S%02dE%02d".format(season, episode) + name?.let { " · $it" }.orEmpty()
        } else {
            null
        }

    val hasNextEpisode: Boolean
        get() = detail?.isSeries == true && episodes.any { it.episode > episode }

    val hasPreviousEpisode: Boolean
        get() = detail?.isSeries == true && episodes.any { it.episode < episode }
}

/**
 * Player state holder.
 *
 * Owns source resolution, quality/subtitle/audio selection, episode navigation
 * and the throttled history writes. The Compose layer only renders the state and
 * forwards intents, which keeps the 570-line `switchPlayerMode` monolith from the
 * web app from reappearing here.
 */
class PlayerViewModel(
    application: Application,
    private val repository: MediaRepository,
    private val store: WatchBoxStore,
    private val detailPath: String,
    initialSeason: Int,
    initialEpisode: Int,
    initialResumeMs: Long,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        PlayerUiState(
            season = initialSeason,
            episode = initialEpisode,
            resumeMs = initialResumeMs,
        ),
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var resolveJob: Job? = null
    private var lastHistoryWrite = 0L

    init {
        viewModelScope.launch {
            val settings = store.currentSettings()
            _uiState.value = _uiState.value.copy(autoPlayNext = settings.autoPlayNext)
            loadDetail(settings.preferredQuality)
        }
    }

    private suspend fun loadDetail(preferredQuality: String) {
        repository.detail(detailPath)
            .onSuccess { detail ->
                val episodes = if (detail.isSeries) {
                    val count = detail.seasons
                        .firstOrNull { it.season == _uiState.value.season }
                        ?.episodeCount
                        ?: 1
                    repository.episodes(detail.tmdbId, _uiState.value.season, count)
                } else {
                    emptyList()
                }
                _uiState.value = _uiState.value.copy(detail = detail, episodes = episodes)
                resolve(preferredQuality)
            }
            .onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isResolving = false,
                    errorMessage = error.message ?: "Could not load this title.",
                )
            }
    }

    private fun resolve(preferredQuality: String = "1080") {
        val detail = _uiState.value.detail ?: return
        resolveJob?.cancel()

        _uiState.value = _uiState.value.copy(
            isResolving = true,
            errorMessage = null,
            source = null,
            selectedStream = null,
        )

        resolveJob = viewModelScope.launch {
            repository.resolvePlayback(detail, _uiState.value.season, _uiState.value.episode)
                .onSuccess { source ->
                    // Honour the saved quality preference when that height exists.
                    val preferredHeight = preferredQuality.filter(Char::isDigit).toIntOrNull()
                    val stream = source.streams.firstOrNull { it.height == preferredHeight }
                        ?: source.best
                        ?: source.streams.firstOrNull()

                    val defaultSubtitle = source.subtitles.indexOfFirst {
                        it.language.equals(store.currentSettings().subtitleLanguage, true)
                    }

                    _uiState.value = _uiState.value.copy(
                        isResolving = false,
                        source = source,
                        selectedStream = stream,
                        selectedSubtitleIndex = defaultSubtitle,
                        selectedAudioIndex = if (source.audioTracks.isNotEmpty()) 0 else -1,
                        errorMessage = if (stream == null) "No playable source found." else null,
                        resolvingServer = null,
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isResolving = false,
                        errorMessage = error.message ?: "No playable source found.",
                        resolvingServer = null,
                    )
                }
        }
    }

    // ---------------------------------------------------------- selections

    fun selectStream(stream: PlayableStream) {
        _uiState.value = _uiState.value.copy(selectedStream = stream)
        viewModelScope.launch {
            store.setPreferredQuality(stream.height.takeIf { it > 0 }?.toString() ?: "auto")
        }
    }

    fun selectSubtitle(index: Int) {
        _uiState.value = _uiState.value.copy(selectedSubtitleIndex = index)
        val language = _uiState.value.source?.subtitles?.getOrNull(index)?.language
        viewModelScope.launch {
            store.setSubtitleLanguage(language ?: "off")
        }
    }

    fun selectAudio(index: Int) {
        val track = _uiState.value.source?.audioTracks?.getOrNull(index) ?: return
        _uiState.value = _uiState.value.copy(
            selectedAudioIndex = index,
            selectedStream = PlayableStream(
                url = track.url,
                label = track.label,
                height = 0,
                isHls = track.isHls,
            ),
        )
    }

    /** Swap to an alternate upstream host (Atlas / Rigel multi-host providers). */
    fun selectHost(index: Int) {
        val host = _uiState.value.source?.hosts?.getOrNull(index) ?: return
        _uiState.value = _uiState.value.copy(
            selectedStream = PlayableStream(
                url = host.url,
                label = host.label,
                height = 0,
                isHls = host.isHls,
            ),
        )
    }

    fun setSpeed(speed: Float) {
        _uiState.value = _uiState.value.copy(speed = speed)
    }

    fun cycleAspect() {
        _uiState.value = _uiState.value.copy(aspectMode = _uiState.value.aspectMode.next())
    }

    fun setLocked(locked: Boolean) {
        _uiState.value = _uiState.value.copy(locked = locked)
    }

    // ------------------------------------------------------ episode nav

    fun goToEpisode(episode: Int) {
        if (episode == _uiState.value.episode) return
        _uiState.value = _uiState.value.copy(
            episode = episode,
            resumeMs = 0L,
        )
        resolve()
    }

    fun nextEpisode() {
        val next = _uiState.value.episodes
            .filter { it.episode > _uiState.value.episode }
            .minByOrNull { it.episode }
            ?: return
        goToEpisode(next.episode)
    }

    fun previousEpisode() {
        val previous = _uiState.value.episodes
            .filter { it.episode < _uiState.value.episode }
            .maxByOrNull { it.episode }
            ?: return
        goToEpisode(previous.episode)
    }

    fun retry() {
        viewModelScope.launch { resolve(store.currentSettings().preferredQuality) }
    }

    // --------------------------------------------------------- history

    /**
     * Persist progress, throttled to one write per 10s (plus an immediate write on
     * pause/exit via [flushProgress]).
     */
    fun onProgress(positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        val now = System.currentTimeMillis()
        if (now - lastHistoryWrite < 10_000L) return
        lastHistoryWrite = now
        writeHistory(positionMs, durationMs)
    }

    fun flushProgress(positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        lastHistoryWrite = System.currentTimeMillis()
        writeHistory(positionMs, durationMs)
    }

    private fun writeHistory(positionMs: Long, durationMs: Long) {
        val detail = _uiState.value.detail ?: return
        val state = _uiState.value
        viewModelScope.launch {
            store.saveHistory(
                WatchHistoryEntry(
                    subjectId = detail.subjectId,
                    detailPath = detail.detailPath,
                    title = detail.title,
                    coverUrl = detail.posterUrl,
                    subjectType = detail.subjectType,
                    season = state.season,
                    episode = state.episode,
                    maxEpisode = state.episodes.maxOfOrNull { it.episode } ?: state.episode,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    progress = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
                    serverId = state.source?.serverId,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** Called when playback reaches the end. */
    fun onPlaybackEnded(positionMs: Long, durationMs: Long) {
        flushProgress(durationMs.takeIf { it > 0 } ?: positionMs, durationMs)
        if (_uiState.value.autoPlayNext && _uiState.value.hasNextEpisode) {
            viewModelScope.launch {
                delay(600)
                nextEpisode()
            }
        }
    }

    companion object {
        fun factory(
            application: Application,
            repository: MediaRepository,
            store: WatchBoxStore,
            detailPath: String,
            season: Int,
            episode: Int,
            resumeMs: Long,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PlayerViewModel(
                application = application,
                repository = repository,
                store = store,
                detailPath = detailPath,
                initialSeason = season,
                initialEpisode = episode,
                initialResumeMs = resumeMs,
            ) as T
        }
    }
}
