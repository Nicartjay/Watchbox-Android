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
import space.nicart.watchbox.data.remote.SubtitleQuery
import space.nicart.watchbox.data.remote.SubtitleResult
import space.nicart.watchbox.domain.StreamOption
import space.nicart.watchbox.domain.SubtitleOption
import space.nicart.watchbox.data.remote.SkipInterval
import space.nicart.watchbox.domain.SkipRepository
import space.nicart.watchbox.domain.SubtitleRepository

/**
 * The online subtitle search, as a state machine.
 *
 * Modelled explicitly because every state needs its own UI: a spinner, a list, an explanation
 * of why there is nothing, and a note that the title cannot be searched at all. Collapsing
 * these into a nullable list plus a boolean loses the difference between "found nothing" and
 * "never asked", which are not the same message.
 */
sealed interface SubtitleSearchState {
    data object Idle : SubtitleSearchState

    /**
     * A subtitle was downloaded and turned on.
     *
     * Distinct from [Idle] so the player can close the panel on success without also closing it
     * the moment it opens - the search starts from Idle, and "nothing has happened yet" and
     * "the thing you asked for worked" have to be different states to act on.
     */
    data object Applied : SubtitleSearchState
    data object Searching : SubtitleSearchState
    data class Results(val results: List<SubtitleResult>) : SubtitleSearchState

    /**
     * Downloading a chosen result.
     *
     * Carries the list it was chosen from as well as the [id] of the chosen row, so the panel
     * can keep showing the results with a spinner on one of them. Without the list the panel
     * would have to blank itself mid-download and hide what the user had just picked.
     */
    data class Downloading(val id: String, val previous: List<SubtitleResult>) : SubtitleSearchState
    data object Empty : SubtitleSearchState

    /** The title has no id either provider can search by, so there is nothing to try. */
    data object Unsupported : SubtitleSearchState
    data object Failed : SubtitleSearchState
}

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
    /** Keep playing when the app is backgrounded; off by default. */
    val backgroundPlayback: Boolean = false,
    val errorMessage: String? = null,
    /**
     * Subtitles fetched online, appended after the ones the source supplied.
     *
     * Held here rather than folded into [selectedStream] so switching quality does not discard
     * them: the stream is replaced wholesale on a quality change, and a subtitle the user went
     * looking for should outlive that.
     */
    val externalSubtitles: List<SubtitleOption> = emptyList(),
    val subtitleSearch: SubtitleSearchState = SubtitleSearchState.Idle,
    /**
     * Opening/ending intervals for this episode, empty when none are known.
     *
     * Only anime with a TMDB-to-MAL mapping has any, so empty is the ordinary case rather than
     * a failure - see [SkipRepository].
     */
    val skipIntervals: List<SkipInterval> = emptyList(),
) {
    val title: String get() = detail?.title.orEmpty()

    val episodeLabel: String? get() = episode?.displayName

    /**
     * Every selectable subtitle: the source's own, then anything fetched online.
     *
     * Order matters and is relied on by [selectedSubtitleIndex] - appending keeps existing
     * indices valid, so adding a track cannot silently change which one is playing.
     */
    val subtitles: List<SubtitleOption>
        get() = selectedStream?.subtitles.orEmpty() + externalSubtitles

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
    private val subtitles: SubtitleRepository,
    private val skips: SkipRepository,
    private val store: WatchBoxStore,
    private val sourceId: Long,
    private val animeUrl: String,
    private val initialEpisodeUrl: String,
    initialResumeMs: Long,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState(resumeMs = initialResumeMs))
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var resolveJob: Job? = null
    private var subtitleJob: Job? = null
    private var skipJob: Job? = null
    private var lastHistoryWrite = 0L

    /**
     * The user's preferred subtitle language, cached from settings.
     *
     * Kept as a field because the search runs from a click and cannot suspend to read the
     * store first. Refreshed whenever a selection writes it back, so it stays current without
     * observing the whole settings flow.
     */
    private var subtitleLanguage: String = DEFAULT_SUBTITLE_LANGUAGE

    init {
        viewModelScope.launch {
            val settings = store.currentSettings()
            subtitleLanguage = settings.subtitleLanguage
            _uiState.value = _uiState.value.copy(
                autoPlayNext = settings.autoPlayNext,
                backgroundPlayback = settings.backgroundPlayback,
            )
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
        // Only a real language updates the search default: "off" is a state, not a language,
        // and letting it through would make a later search have nothing to look for.
        language?.takeIf { it.isNotBlank() }?.let { subtitleLanguage = it }
        viewModelScope.launch { store.setSubtitleLanguage(language ?: "off") }
    }

    /**
     * Searches online for subtitles matching the episode being played.
     *
     * Only ever user-initiated. An automatic search on every playback start would spend a
     * request per episode to replace tracks the source already supplied correctly, and an
     * online release is matched to a specific encode - it is as likely to be out of sync as
     * it is to be an improvement.
     */
    fun searchSubtitles() {
        subtitleJob?.cancel()

        val state = _uiState.value
        val detail = state.detail
        val episode = state.episode
        val isSeries = detail != null && !detail.isMovie

        // Read from the last known settings rather than awaited: this runs on a click, and
        // "off" is a real stored value meaning the user turned subtitles off - not a language
        // to search for.
        val language = subtitleLanguage.takeIf { it.isNotBlank() && !it.equals("off", true) }
            ?: DEFAULT_SUBTITLE_LANGUAGE

        val query = SubtitleQuery(
            imdbId = detail?.imdbId,
            tmdbId = detail?.tmdbId,
            // A film is a single entry with no season. Sending season/episode for one filters
            // every result away, because the catalogue has no such entry to match.
            season = if (isSeries) episode?.season ?: 1 else null,
            episode = if (isSeries) episode?.number?.toInt() else null,
            language = language,
            title = state.title,
        )

        if (query.isUnusable) {
            _uiState.value = state.copy(subtitleSearch = SubtitleSearchState.Unsupported)
            return
        }

        _uiState.value = state.copy(subtitleSearch = SubtitleSearchState.Searching)

        subtitleJob = viewModelScope.launch {
            val results = subtitles.search(query)
            _uiState.value = _uiState.value.copy(
                subtitleSearch = if (results.isEmpty()) {
                    SubtitleSearchState.Empty
                } else {
                    SubtitleSearchState.Results(results)
                },
            )
        }
    }

    /**
     * Downloads a chosen result and selects it immediately.
     *
     * Selecting it is the point: a user who picked a subtitle from a list wants to see it, not
     * to then find it in a second list and turn it on.
     */
    fun applySubtitle(result: SubtitleResult) {
        subtitleJob?.cancel()

        subtitleJob = viewModelScope.launch {
            val shown = (_uiState.value.subtitleSearch as? SubtitleSearchState.Results)
                ?.results
                .orEmpty()

            _uiState.value = _uiState.value.copy(
                subtitleSearch = SubtitleSearchState.Downloading(result.id, shown),
            )

            val option = subtitles.download(result)
            if (option == null) {
                _uiState.value = _uiState.value.copy(
                    subtitleSearch = SubtitleSearchState.Failed,
                )
                return@launch
            }

            val current = _uiState.value
            // Replaces any earlier download rather than accumulating: each is an attempt at
            // the same thing, and a panel filling up with near-identical release names makes
            // the working one harder to find.
            val external = current.externalSubtitles.filterNot { it.url == option.url } + option
            val sourceCount = current.selectedStream?.subtitles?.size ?: 0

            _uiState.value = current.copy(
                externalSubtitles = external,
                selectedSubtitleIndex = sourceCount + external.lastIndex,
                subtitleSearch = SubtitleSearchState.Applied,
            )
        }
    }

    /**
     * Loads skip intervals once the runtime is known.
     *
     * Called from the player after preparation rather than on episode selection, because AniSkip
     * checks the episode length against the interval it holds - a length of zero returns nothing.
     *
     * Cleared first, so a stale interval from the previous episode cannot leave a button on
     * screen that jumps to the wrong place.
     */
    fun loadSkipTimes(episodeLengthMs: Long) {
        skipJob?.cancel()

        if (episodeLengthMs <= 0) return

        val state = _uiState.value
        skipJob = viewModelScope.launch {
            val intervals = skips.skipTimes(
                detail = state.detail,
                episode = state.episode,
                episodeLengthMs = episodeLengthMs,
            )

            // Discarded if the episode moved on while the two lookups were in flight.
            if (_uiState.value.episode?.url == state.episode?.url) {
                _uiState.value = _uiState.value.copy(skipIntervals = intervals)
            }
        }
    }

    /** Acknowledges the Applied state so a later search does not re-close its own panel. */
    fun onSubtitleApplied() {
        if (_uiState.value.subtitleSearch is SubtitleSearchState.Applied) {
            _uiState.value = _uiState.value.copy(subtitleSearch = SubtitleSearchState.Idle)
        }
    }

    /** Closes the search, discarding results but keeping anything already downloaded. */
    fun dismissSubtitleSearch() {
        subtitleJob?.cancel()
        _uiState.value = _uiState.value.copy(subtitleSearch = SubtitleSearchState.Idle)
    }

    fun setSpeed(speed: Float) {
        _uiState.value = _uiState.value.copy(speed = speed)
    }

    /**
     * Remembers the cast relay choice.
     *
     * Persisted here rather than in CastManager so the cast layer keeps no reference to the
     * store: it is told the value at startup and when it changes, and never reads settings
     * itself.
     */
    fun setCastForceProxy(enabled: Boolean) {
        viewModelScope.launch { store.setCastForceProxy(enabled) }
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

        // Downloaded subtitles are dropped with the episode. They are timed against one
        // specific release, so carrying them over would leave the next episode showing the
        // previous one's dialogue - worse than no subtitles, because it looks like it works.
        subtitleJob?.cancel()
        skipJob?.cancel()
        subtitles.clearCache()

        _uiState.value = _uiState.value.copy(
            episode = episode,
            resumeMs = 0L,
            externalSubtitles = emptyList(),
            selectedSubtitleIndex = -1,
            subtitleSearch = SubtitleSearchState.Idle,
            // The previous episode's opening is not this one's.
            skipIntervals = emptyList(),
        )
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

        /** Fallback when the stored preference is absent or is the "off" sentinel. */
        private const val DEFAULT_SUBTITLE_LANGUAGE = "en"

        fun factory(
            repository: AnimeRepository,
            subtitles: SubtitleRepository,
            skips: SkipRepository,
            store: WatchBoxStore,
            sourceId: Long,
            animeUrl: String,
            episodeUrl: String,
            resumeMs: Long,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PlayerViewModel(
                repository = repository,
                subtitles = subtitles,
                skips = skips,
                store = store,
                sourceId = sourceId,
                animeUrl = animeUrl,
                initialEpisodeUrl = episodeUrl,
                initialResumeMs = resumeMs,
            ) as T
        }
    }
}
