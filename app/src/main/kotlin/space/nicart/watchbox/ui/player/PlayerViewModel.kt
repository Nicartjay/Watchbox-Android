package space.nicart.watchbox.ui.player

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
import space.nicart.watchbox.domain.AnimeDetail
import space.nicart.watchbox.domain.AnimeRepository
import space.nicart.watchbox.domain.EpisodeEntry
import space.nicart.watchbox.domain.StreamOption
import space.nicart.watchbox.domain.SubtitleOption

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
    val detail: AnimeDetail? = null,
    val episode: EpisodeEntry? = null,
    val streams: List<StreamOption> = emptyList(),
    val selectedStream: StreamOption? = null,
    val selectedSubtitleIndex: Int = -1,
    val resumeMs: Long = 0L,
    val speed: Float = 1f,
    val aspectMode: AspectMode = AspectMode.FIT,
    val locked: Boolean = false,
    val autoPlayNext: Boolean = true,
    val errorMessage: String? = null,
) {
    val title: String get() = detail?.title.orEmpty()

    val episodeLabel: String? get() = episode?.displayName

    val subtitles: List<SubtitleOption> get() = selectedStream?.subtitles.orEmpty()

    val episodes: List<EpisodeEntry> get() = detail?.episodes.orEmpty()

    private val episodeIndex: Int
        get() = episodes.indexOfFirst { it.url == episode?.url }

    val hasNextEpisode: Boolean
        get() = episodeIndex >= 0 && episodeIndex < episodes.lastIndex

    val hasPreviousEpisode: Boolean get() = episodeIndex > 0

    val nextEpisode: EpisodeEntry?
        get() = episodes.getOrNull(episodeIndex + 1).takeIf { episodeIndex >= 0 }

    val previousEpisode: EpisodeEntry?
        get() = if (episodeIndex > 0) episodes[episodeIndex - 1] else null
}

/**
 * Player state holder.
 *
 * Owns stream resolution, track selection, episode navigation and the throttled
 * history writes; the Compose layer only renders state and forwards intents.
 *
 * Resolution is deliberately per-episode rather than cached: the URLs extensions
 * return are usually signed and short-lived, so reusing an old one across an
 * episode change tends to 403.
 */
class PlayerViewModel(
    private val repository: AnimeRepository,
    private val store: WatchBoxStore,
    private val sourceId: Long,
    private val animeUrl: String,
    private val initialEpisodeUrl: String,
    initialResumeMs: Long,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState(resumeMs = initialResumeMs))
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var resolveJob: Job? = null
    private var lastHistoryWrite = 0L

    init {
        viewModelScope.launch {
            val settings = store.currentSettings()
            _uiState.value = _uiState.value.copy(autoPlayNext = settings.autoPlayNext)
            loadDetail()
        }
    }

    private suspend fun loadDetail() {
        repository.detail(sourceId, animeUrl)
            .onSuccess { detail ->
                val episode = detail.episodes.firstOrNull { it.url == initialEpisodeUrl }
                    ?: detail.episodes.firstOrNull()

                _uiState.value = _uiState.value.copy(detail = detail, episode = episode)

                if (episode == null) {
                    _uiState.value = _uiState.value.copy(
                        isResolving = false,
                        errorMessage = "This title has no episodes.",
                    )
                } else {
                    resolve(episode)
                }
            }
            .onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isResolving = false,
                    errorMessage = error.message ?: "Could not load this title.",
                )
            }
    }

    private fun resolve(episode: EpisodeEntry) {
        resolveJob?.cancel()

        _uiState.value = _uiState.value.copy(
            isResolving = true,
            errorMessage = null,
            streams = emptyList(),
            selectedStream = null,
        )

        resolveJob = viewModelScope.launch {
            val preferredHeight = store.currentSettings()
                .preferredQuality
                .filter(Char::isDigit)
                .toIntOrNull()

            repository.streams(sourceId, episode.url)
                .onSuccess { streams ->
                    // Honour the saved quality when that height exists, else take
                    // the best available.
                    val chosen = streams.firstOrNull { it.resolution == preferredHeight }
                        ?: streams.firstOrNull()

                    val subtitleLang = store.currentSettings().subtitleLanguage
                    val subtitleIndex = chosen?.subtitles
                        ?.indexOfFirst { it.language.equals(subtitleLang, true) }
                        ?: -1

                    _uiState.value = _uiState.value.copy(
                        isResolving = false,
                        streams = streams,
                        selectedStream = chosen,
                        selectedSubtitleIndex = subtitleIndex,
                        errorMessage = if (chosen == null) NO_STREAM else null,
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isResolving = false,
                        errorMessage = error.message ?: NO_STREAM,
                    )
                }
        }
    }

    // ---------------------------------------------------------- selections

    fun selectStream(stream: StreamOption) {
        _uiState.value = _uiState.value.copy(selectedStream = stream)
        viewModelScope.launch {
            store.setPreferredQuality(
                stream.resolution.takeIf { it > 0 }?.toString() ?: "auto",
            )
        }
    }

    fun selectSubtitle(index: Int) {
        _uiState.value = _uiState.value.copy(selectedSubtitleIndex = index)
        val language = _uiState.value.subtitles.getOrNull(index)?.language
        viewModelScope.launch { store.setSubtitleLanguage(language ?: "off") }
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

    // ------------------------------------------------------- episode nav

    fun goToEpisode(episode: EpisodeEntry) {
        if (episode.url == _uiState.value.episode?.url) return
        _uiState.value = _uiState.value.copy(episode = episode, resumeMs = 0L)
        resolve(episode)
    }

    fun nextEpisode() {
        _uiState.value.nextEpisode?.let(::goToEpisode)
    }

    fun previousEpisode() {
        _uiState.value.previousEpisode?.let(::goToEpisode)
    }

    fun retry() {
        _uiState.value.episode?.let(::resolve)
    }

    // ------------------------------------------------------------ history

    /** Throttled to one write per 10s; [flushProgress] covers pause and exit. */
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
        val episode = _uiState.value.episode ?: return

        viewModelScope.launch {
            store.saveHistory(
                WatchHistoryEntry(
                    sourceId = detail.sourceId,
                    animeUrl = detail.url,
                    title = detail.title,
                    posterUrl = detail.posterUrl,
                    sourceName = detail.sourceName,
                    episodeUrl = episode.url,
                    episodeName = episode.displayName,
                    episodeNumber = episode.number,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    progress = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

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
        private const val NO_STREAM = "No playable stream found."

        fun factory(
            repository: AnimeRepository,
            store: WatchBoxStore,
            sourceId: Long,
            animeUrl: String,
            episodeUrl: String,
            resumeMs: Long,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PlayerViewModel(
                repository = repository,
                store = store,
                sourceId = sourceId,
                animeUrl = animeUrl,
                initialEpisodeUrl = episodeUrl,
                initialResumeMs = resumeMs,
            ) as T
        }
    }
}
