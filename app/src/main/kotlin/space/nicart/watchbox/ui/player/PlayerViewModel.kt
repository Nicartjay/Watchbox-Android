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
import androidx.media3.common.util.UnstableApi
import space.nicart.watchbox.download.DownloadEngine
import space.nicart.watchbox.data.local.DownloadEntry
import space.nicart.watchbox.data.remote.SubtitleApi.Companion.toIso639_1

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
    /**
     * How long the current resolve has been running, in seconds.
     *
     * Drives the wording under the spinner. The source resolves every server
     * inside one opaque call - the extension API exposes no per-server progress -
     * so this cannot claim to know which server is being tried. What it can
     * honestly say is that the work is still going and roughly how long for,
     * which is what a bare spinner fails to convey.
     */
    val resolveSeconds: Int = 0,
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
    /**
     * Cache key for a downloaded episode, or null when streaming.
     *
     * Handed to the media item so the cache can find bytes written under a URL that has since
     * expired. Null means the URL is live and the default URL keying is correct.
     */
    val offlineCacheKey: String? = null,
    val subtitleSearch: SubtitleSearchState = SubtitleSearchState.Idle,
    /**
     * Opening/ending intervals for this episode, empty when none are known.
     *
     * Only anime with a TMDB-to-MAL mapping has any, so empty is the ordinary case rather than
     * a failure - see [SkipRepository].
     */
    val skipIntervals: List<SkipInterval> = emptyList(),
    /**
     * Subtitle timing correction in milliseconds; positive delays the subtitles.
     *
     * Seeded from the persisted setting and adjustable mid-playback, because a desync is
     * only noticeable once something is being watched.
     */
    val subtitleOffsetMs: Long = 0L,
    /** In-progress two-tap sync measurement, idle when nothing has been marked. */
    val syncCalibration: SyncCalibration = SyncCalibration(),
    /**
     * Cues for the selected subtitle, loaded only to support a timing offset.
     *
     * Empty when no offset is set, when the format cannot be parsed, or when the track is
     * embedded in the stream - in all of which cases the player's own rendering is used.
     */
    val offsetCues: List<SubtitleCue> = emptyList(),
) {
    /**
     * True when the selected subtitle cannot be shifted earlier, only later.
     *
     * A track with no URL is embedded in the stream, so there is no file to parse and the
     * only way to move it is to hold the decoder's own cues back. That serves a delay but
     * cannot surface a line before the decoder has emitted it, so a negative correction is
     * impossible for these - a real limit of the format rather than a missing feature.
     */
    val subtitleIsEmbedded: Boolean
        get() = selectedSubtitleIndex >= 0 &&
            subtitles.getOrNull(selectedSubtitleIndex)?.url.isNullOrBlank()

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
@UnstableApi
class PlayerViewModel(
    private val repository: AnimeRepository,
    private val subtitles: SubtitleRepository,
    private val skips: SkipRepository,
    private val store: WatchBoxStore,
    private val downloadEngine: DownloadEngine,
    private val sourceId: Long,
    private val animeUrl: String,
    private val initialEpisodeUrl: String,
    initialResumeMs: Long,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState(resumeMs = initialResumeMs))
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var resolveJob: Job? = null

    /** Elapsed-time ticker for the resolve, cancelled with it. */
    private var resolveTicker: Job? = null
    private var cuesJob: Job? = null

    /**
     * The subtitle URL whose cues are loaded or loading.
     *
     * Guards against refetching: the cue list depends on which subtitle is selected, not on
     * the offset value, so adjusting the offset must not restart the load.
     */
    private var cuesLoadedFor: String? = null
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

            // Repaired on read, not just on write.
            //
            // Earlier builds stored a source's own track label here - "English",
            // "Portuguese (Brazil)" - and that value is what every online search was built
            // from, so a device carrying one would stay broken however the write path is
            // fixed. Reducing it to a code here heals it in place; "off" is passed through
            // because it is a state rather than a language.
            val stored = settings.subtitleLanguage
            subtitleLanguage = when {
                stored.equals("off", ignoreCase = true) -> stored
                stored.toIso639_1().isNotBlank() -> stored.toIso639_1()
                // Unrecognisable, so the default is better than a value known to fail.
                else -> DEFAULT_SUBTITLE_LANGUAGE
            }

            // Written back only when it actually changed, so this costs nothing on a device
            // that was never affected.
            if (subtitleLanguage != stored) {
                store.setSubtitleLanguage(subtitleLanguage)
            }
            _uiState.value = _uiState.value.copy(
                autoPlayNext = settings.autoPlayNext,
                backgroundPlayback = settings.backgroundPlayback,
                subtitleOffsetMs = settings.subtitleOffsetMs,
            )
            loadDetail()
        }
    }

    private suspend fun loadDetail() {
        // The downloaded copy first, before anything reaches the network.
        //
        // A download is meant to be watchable with the aeroplane on, and this is the point
        // where that either works or does not: loading the detail is a network call, so
        // resolving it first meant an offline episode failed before its own file was ever
        // considered. It also means an online viewer with a download plays it from disk rather
        // than paying for the stream a second time.
        val offline = offlineEntry()
        if (offline != null) {
            playOffline(offline)
            // The detail is still fetched, because episode navigation and the skip markers
            // need it - but it is no longer on the path to playing, so failing is survivable.
            repository.detail(sourceId, animeUrl).onSuccess { detail ->
                _uiState.value = _uiState.value.copy(
                    detail = detail,
                    episode = detail.episodes.firstOrNull { it.url == initialEpisodeUrl }
                        ?: _uiState.value.episode,
                )
            }
            return
        }

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

    /** The completed download for the episode being opened, or null. */
    private suspend fun offlineEntry(): DownloadEntry? = offlineEntryFor(initialEpisodeUrl)

    /** The completed, present-on-disk download for [episodeUrl], or null. */
    private suspend fun offlineEntryFor(episodeUrl: String): DownloadEntry? {
        val entry = store.downloadFor(sourceId, animeUrl, episodeUrl) ?: return null
        if (!entry.isComplete) return null

        // The registry can outlive the bytes - a cleared cache, a pulled card, a file deleted by
        // hand - so what actually holds the download is checked too. Believing the registry alone
        // would offer something that is not there and fail with no way back to streaming.
        val present = if (entry.isRemuxed) {
            runCatching {
                java.io.File(java.net.URI(entry.downloadUri)).length() > 0
            }.getOrDefault(false)
        } else {
            downloadEngine.isDownloaded(entry.key)
        }
        if (!present) return null
        return entry
    }

    /**
     * Plays a downloaded episode from disk.
     *
     * No extension call and no resolve. The stream it builds carries the download's cache key
     * rather than a live URL, which is what lets the cache match bytes written under a URL that
     * has long since expired; the URI is only a placeholder for the cache to key against and is
     * never fetched while the download is complete.
     */
    private fun playOffline(entry: DownloadEntry) {
        val episode = EpisodeEntry(
            url = entry.episodeUrl,
            name = entry.episodeName,
            number = entry.episodeNumber,
            dateUpload = 0L,
            scanlator = null,
        )

        val stream = StreamOption(
            label = entry.streamLabel.ifBlank { OFFLINE_STREAM_LABEL },
            // The manifest URI for an adaptive stream, because that is what its cache entry is
            // keyed by and what names the segments inside it. Its signature expired long ago,
            // but it is never fetched: the request is served from the cache. A progressive
            // download is found by its key instead, so any URI would do and the episode URL is
            // used to keep isHls and isDash false for it.
            url = when {
                // A remuxed download is a plain file. Played from its own path, with no cache
                // and no manifest, which is the simplest of the three cases.
                entry.isRemuxed -> entry.downloadUri
                entry.isAdaptive -> entry.downloadUri
                else -> entry.episodeUrl
            },
            headers = emptyMap(),
            // Language recovered from the filename, which the downloader encodes as
            // `src-<index>-<lang>.<ext>`. Labelling every track "Downloaded" in the viewer's
            // own language was fine while there could only be one, but a source may supply
            // several - and identical rows in the panel are unpickable.
            subtitles = entry.subtitlePaths.map { path ->
                val lang = offlineSubtitleLanguage(path) ?: subtitleLanguage
                SubtitleOption(
                    label = offlineSubtitleLabel(lang),
                    url = path,
                    language = lang,
                    isExternal = true,
                )
            },
            audioTracks = emptyList(),
            resolution = 0,
        )

        _uiState.value = _uiState.value.copy(
            isResolving = false,
            episode = _uiState.value.episode ?: episode,
            streams = listOf(stream),
            selectedStream = stream,
            // Progressive only. An adaptive stream is matched through its manifest URI, and
            // handing a custom key to a media item Media3 treats as adaptive would put the
            // cache lookup back on a key nothing was written under.
            // Neither an adaptive stream nor a remuxed file wants a cache key: the first is
            // matched through its manifest URI, the second is read straight off disk.
            offlineCacheKey = entry.key.takeUnless { entry.isAdaptive || entry.isRemuxed },
            errorMessage = null,
        )
    }

    private fun resolve(episode: EpisodeEntry) {
        resolveJob?.cancel()
        resolveTicker?.cancel()

        _uiState.value = _uiState.value.copy(
            isResolving = true,
            resolveSeconds = 0,
            errorMessage = null,
            streams = emptyList(),
            selectedStream = null,
        )

        // Counts while the single resolve call runs. Separate job so it can be
        // cancelled with the resolve rather than racing it.
        resolveTicker = viewModelScope.launch {
            var elapsed = 0
            while (true) {
                delay(1_000)
                elapsed++
                _uiState.value = _uiState.value.copy(resolveSeconds = elapsed)
            }
        }

        resolveJob = viewModelScope.launch {
            val preferredHeight = store.currentSettings()
                .preferredQuality
                .filter(Char::isDigit)
                .toIntOrNull()

            repository.streams(sourceId, episode.url)
                .also { resolveTicker?.cancel() }
                .onSuccess { streams ->
                    // Nearest at or below the setting - see defaultStream for why
                    // an exact match was wrong, and why no setting means the
                    // source's own order is kept.
                    val chosen = defaultStream(streams, preferredHeight)

                    val subtitleLang = store.currentSettings().subtitleLanguage
                    val subtitleIndex = chosen?.subtitles
                        ?.indexOfFirst { it.language.equals(subtitleLang, true) }
                        ?: -1

                    // A subtitle fetched when this episode was downloaded, if there was
                    // one. Added here rather than searched for again: it is already on disk
                    // beside the video, and an offline copy that needed the network to find
                    // its own subtitle would not be much of an offline copy.
                    val offlineSubtitles = store
                        .downloadFor(sourceId, animeUrl, episode.url)
                        ?.subtitlePaths
                        .orEmpty()
                        .map { path ->
                            // Same labelling as the offline path, and read from the filename
                            // for the same reason: the two lists sit in one panel, so a track
                            // must not be named differently depending on how it got there.
                            val lang = offlineSubtitleLanguage(path) ?: subtitleLanguage
                            SubtitleOption(
                                label = offlineSubtitleLabel(lang),
                                url = path,
                                language = lang,
                                isExternal = true,
                            )
                        }

                    _uiState.value = _uiState.value.copy(
                        isResolving = false,
                        streams = streams,
                        selectedStream = chosen,
                        selectedSubtitleIndex = subtitleIndex,
                        externalSubtitles = offlineSubtitles,
                        errorMessage = if (chosen == null) NO_STREAM else null,
                    )

                    // Loads the cues for the subtitle picked here.
                    //
                    // Without this a saved offset did nothing on a fresh episode: the
                    // correction was restored from settings and shown in the panel, but the
                    // cue list it needs was only ever fetched when the subtitle or the
                    // offset changed. Resolution picks a subtitle without going through
                    // either path, so the list stayed empty and the renderer fell back to
                    // the player's own unshifted timing - the offset appeared to be ignored.
                    refreshOffsetCues()
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

        // Normalised before it is stored, because this comes from the source's own track label
        // and those are written for people: "English", "Portuguese (Brazil)". Stored verbatim,
        // that value became the preferred language for every later online search, which then
        // asked the catalogue for a language named rather than coded - so picking an embedded
        // track silently broke subtitle search from then on.
        //
        // An unrecognised label leaves the stored preference alone rather than overwriting it
        // with something unusable: the track still plays, and a language that cannot be coded
        // is no basis for a search.
        val raw = _uiState.value.subtitles.getOrNull(index)?.language
        val code = raw?.toIso639_1().orEmpty()

        // Only a real language updates the search default: "off" is a state, not a language,
        // and letting it through would make a later search have nothing to look for.
        if (code.isNotBlank()) subtitleLanguage = code

        viewModelScope.launch {
            store.setSubtitleLanguage(
                when {
                    raw == null -> "off"
                    code.isNotBlank() -> code
                    // Nothing usable, so what was already stored is kept.
                    else -> subtitleLanguage.ifBlank { DEFAULT_SUBTITLE_LANGUAGE }
                },
            )
        }
        refreshOffsetCues()
    }

    /**
     * Loads the selected subtitle's cues, or clears them.
     *
     * Only fetched while an offset is actually set: at zero the player's own rendering is
     * used, and downloading and parsing a file nobody asked to shift would be waste.
     * Cleared when the offset returns to zero so the parsed copy cannot go stale against a
     * changed track.
     */
    private fun refreshOffsetCues() {
        val state = _uiState.value
        val url = state.subtitles.getOrNull(state.selectedSubtitleIndex)?.url

        if (state.subtitleOffsetMs == 0L || url == null) {
            // Logged, because this is the one path that fails in complete silence. An
            // offset with no URL to shift against does nothing at all, and from the screen
            // that is indistinguishable from the offset being wrong: the panel shows the
            // correction, the subtitles do not move, and nothing reports a problem.
            //
            // No URL means the track is embedded in the stream rather than sideloaded -
            // common for HLS - so there is no file to parse and the timing belongs to the
            // decoder.
            if (state.subtitleOffsetMs != 0L) {
                android.util.Log.w(
                    TAG,
                    "offset=${state.subtitleOffsetMs}ms but the selected subtitle has no " +
                        "URL (index=${state.selectedSubtitleIndex}, " +
                        "tracks=${state.subtitles.size}) - embedded track, cannot shift",
                )
            }
            cuesJob?.cancel()
            cuesLoadedFor = null
            if (state.offsetCues.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(offsetCues = emptyList())
            }
            return
        }

        // Already have them, or already fetching them, for this exact subtitle.
        //
        // This is what made the offset appear to do nothing. Every call used to cancel the
        // job in flight and start again, and the callers fire repeatedly - the stepper on
        // each press, and the sync buttons on each mark - so on device the fetch was killed
        // and restarted several times a second and never once completed. The log was a wall
        // of `cue parse failed: CancellationException`, and offsetCues stayed empty, so the
        // renderer kept falling back to the player's own unshifted timing.
        //
        // The cue list depends only on which subtitle is selected, never on the offset
        // value, so changing the offset must not refetch at all.
        if (cuesLoadedFor == url) return

        cuesJob?.cancel()
        cuesLoadedFor = url

        cuesJob = viewModelScope.launch {
            val cues = subtitles.cues(url)
            // Guarded against a race: the selection may have moved on while this loaded.
            if (_uiState.value.subtitles.getOrNull(
                    _uiState.value.selectedSubtitleIndex,
                )?.url != url
            ) {
                return@launch
            }
            // Cleared on failure so a later attempt is not blocked by a load that produced
            // nothing - a transient network error should not disable the feature for the
            // rest of the episode.
            if (cues.isEmpty()) cuesLoadedFor = null
            _uiState.value = _uiState.value.copy(offsetCues = cues)
        }
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
     * Applies a subtitle timing correction and remembers it.
     *
     * Persisted as well as applied: a release's desync is a property of that release, so
     * the same correction usually holds for the next episode.
     */
    fun setSubtitleOffset(offsetMs: Long) {
        val clamped = clampSubtitleOffset(offsetMs)
        _uiState.value = _uiState.value.copy(
            subtitleOffsetMs = clamped,
            // A completed measurement clears the arming state; leaving it armed would
            // disable one button with nothing pending.
            syncCalibration = SyncCalibration(),
        )
        viewModelScope.launch { store.setSubtitleOffsetMs(clamped) }
        refreshOffsetCues()
    }

    /** Nudges the correction by one step, for the manual stepper. */
    fun nudgeSubtitleOffset(deltaMs: Long) {
        setSubtitleOffset(_uiState.value.subtitleOffsetMs + deltaMs)
    }

    /**
     * Records one half of a two-tap sync measurement at [positionMs].
     *
     * The second tap resolves the offset; the first only arms. Tapping the button that is
     * already armed is ignored rather than treated as a new first mark, so a stray press
     * cannot silently discard a measurement in progress.
     */
    fun markSync(mark: SyncMark, positionMs: Long) {
        val current = _uiState.value.syncCalibration

        val resolved = current.resolve(mark, positionMs)
        if (resolved != null) {
            // Added to the current offset, because the marks are taken against subtitles
            // that are already shifted by it: the measurement is the error that remains,
            // not the total.
            //
            // That only holds while the shift is actually being applied. When it is not -
            // an embedded track with no cue list - the subtitle stays where it was, so a
            // second measurement re-measures the same error and the offset doubles. Which
            // is exactly what a report of "5.57 then 11.11 for the same line" describes.
            //
            // So it replaces rather than accumulates when nothing is being shifted, and the
            // value stays the honest measurement instead of compounding.
            // A positive offset is served from the player's own buffered cues even without
            // a parsed list, so that counts as live too - otherwise an embedded track would
            // replace on every measurement and never converge.
            val st = _uiState.value
            val shiftIsLive = st.offsetCues.isNotEmpty() || st.subtitleOffsetMs > 0L
            setSubtitleOffset(
                if (shiftIsLive) st.subtitleOffsetMs + resolved else resolved,
            )
            return
        }

        if (current.isArmed) return

        _uiState.value = _uiState.value.copy(
            syncCalibration = SyncCalibration(firstMark = mark, firstPositionMs = positionMs),
        )
    }

    /** Abandons a measurement in progress, for a mistimed first tap. */
    fun cancelSync() {
        _uiState.value = _uiState.value.copy(syncCalibration = SyncCalibration())
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
            // Nor are its cues. Left in place they would be shifted against the new
            // episode's clock, putting the wrong dialogue on screen - worse than no
            // correction. `resolve` reloads them for whatever subtitle it picks.
            offsetCues = emptyList(),
        )
        // Reset alongside them, or the guard would treat the next episode's identical
        // subtitle URL as already loaded and never refetch. Some sources reuse a URL
        // shape per episode, so this is not hypothetical.
        cuesLoadedFor = null

        // The downloaded copy first here as well, or moving to the next episode of a show that
        // was downloaded whole would go back to the network for one that is already on disk -
        // and fail outright with no connection.
        viewModelScope.launch {
            val offline = offlineEntryFor(episode.url)
            if (offline != null) {
                playOffline(offline)
                refreshOffsetCues()
            } else {
                resolve(episode)
            }
        }
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
            downloadEngine: DownloadEngine,
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
                downloadEngine = downloadEngine,
                sourceId = sourceId,
                animeUrl = animeUrl,
                initialEpisodeUrl = episodeUrl,
                initialResumeMs = resumeMs,
            ) as T
        }
    }
}

private const val TAG = "WbPlayer"

/**
 * Names a subtitle stored with a download, as `English (Downloaded)`.
 *
 * The language leads because that is what is being chosen between - several downloaded tracks
 * all reading "Downloaded" were unpickable - and the suffix says where the file came from, which
 * matters when a source track and a downloaded one are both in the list.
 *
 * The language name is spelled out where it is known. A panel row reading "EN" asks the viewer
 * to decode it, and the codes stored here come from a source's own labels, so several are not
 * obvious even to someone who knows the language.
 */
private fun offlineSubtitleLabel(language: String): String {
    val name = LANGUAGE_NAMES[language.lowercase()]
        ?: language.takeIf { it.isNotBlank() }?.uppercase()
        ?: return OFFLINE_SUBTITLE_SUFFIX
    return "$name ($OFFLINE_SUBTITLE_SUFFIX)"
}

private const val OFFLINE_SUBTITLE_SUFFIX = "Downloaded"

/**
 * Codes to English names, for the languages the subtitle providers index.
 *
 * Mirrors the set the subtitle API maps, so anything that can be searched for can be named.
 */
private val LANGUAGE_NAMES = mapOf(
    "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German",
    "it" to "Italian", "pt" to "Portuguese", "ru" to "Russian", "ja" to "Japanese",
    "ko" to "Korean", "zh" to "Chinese", "ar" to "Arabic", "hi" to "Hindi",
    "id" to "Indonesian", "th" to "Thai", "vi" to "Vietnamese", "tr" to "Turkish",
    "pl" to "Polish", "nl" to "Dutch", "sv" to "Swedish", "da" to "Danish",
    "fi" to "Finnish", "no" to "Norwegian", "cs" to "Czech", "el" to "Greek",
    "he" to "Hebrew", "hu" to "Hungarian", "ro" to "Romanian", "uk" to "Ukrainian",
    "fa" to "Persian", "ms" to "Malay",
)

/** Shown in the quality panel for a stream being played from disk. */
private const val OFFLINE_STREAM_LABEL = "Downloaded"

/**
 * The language a downloaded subtitle file was saved under.
 *
 * Read back from the name the downloader gave it - `src-<index>-<lang>.<ext>` - because nothing
 * else records it per file. Null for a file that predates the scheme, or for one fetched by the
 * online fallback, where the language is the viewer's own preference anyway.
 */
private fun offlineSubtitleLanguage(path: String): String? {
    val name = path.substringAfterLast('/').substringBeforeLast('.')
    if (!name.startsWith("src-")) return null
    return name.substringAfterLast('-').takeIf { it.isNotBlank() && it != "sub" }
}
