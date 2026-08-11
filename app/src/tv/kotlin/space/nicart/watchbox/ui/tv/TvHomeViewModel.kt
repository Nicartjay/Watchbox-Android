package space.nicart.watchbox.ui.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.data.local.WatchHistoryEntry
import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.domain.AnimeRepository
import space.nicart.watchbox.extension.ExtensionManager
import space.nicart.watchbox.ui.browse.SourceEntry

/**
 * TV home state: one source at a time.
 *
 * The phone home shows a rail per installed source, which suits a scrolling list. On a
 * television it does not: with several extensions installed the screen becomes a long
 * vertical traverse, and a D-pad crosses one row per press. Showing a single source's
 * Popular and Latest instead keeps the whole feed within a couple of presses, and makes
 * the backdrop meaningful - it belongs to a catalogue you chose rather than whichever
 * source happened to sort first.
 */
data class TvHomeState(
    val sources: List<SourceEntry> = emptyList(),
    val selected: SourceEntry? = null,
    /** Popular, as a single horizontal row. */
    val popular: List<AnimeCard> = emptyList(),
    /**
     * Latest, as a growing grid.
     *
     * A grid rather than a row because it is the browsing surface: a horizontal row caps
     * what you can reach at whatever fits on one line, while a grid keeps paging as long
     * as the user keeps scrolling. Popular stays a row precisely because it is a
     * shortlist, not something to browse through.
     */
    val latest: List<AnimeCard> = emptyList(),
    val latestPage: Int = 0,
    val isLoading: Boolean = true,
    val isAppending: Boolean = false,
    val hasMoreLatest: Boolean = true,
    val errorMessage: String? = null,
) {
    val hasNoSources: Boolean get() = sources.isEmpty()

    val isEmpty: Boolean get() = popular.isEmpty() && latest.isEmpty()
}

/**
 * State of the hero's Play button.
 *
 * The hero shows a card, not a title's episode list, so "play" cannot navigate straight
 * to the player: the episode to open is only known after fetching the detail. That is a
 * network round-trip, so the button has to report progress rather than appear inert, and
 * the resolved route has to survive as state until the screen has consumed it.
 */
sealed interface TvPlayRequest {

    data object Idle : TvPlayRequest

    /** Fetching the episode list. Carries the card key so only that hero shows a spinner. */
    data class Resolving(val cardKey: String) : TvPlayRequest

    /** Resolved: the screen navigates to the player and consumes this. */
    data class Ready(
        val sourceId: Long,
        val animeUrl: String,
        val episodeUrl: String,
        val resumeMs: Long,
    ) : TvPlayRequest

    /**
     * Nothing playable was found.
     *
     * Carries the card so the screen can fall back to opening the detail page. Silently
     * doing nothing would read as the button being broken, and the detail page is where
     * the user can see for themselves that there are no episodes.
     */
    data class Unavailable(val card: AnimeCard) : TvPlayRequest
}

class TvHomeViewModel(
    private val repository: AnimeRepository,
    private val extensions: ExtensionManager,
    private val store: WatchBoxStore,
) : ViewModel() {

    private val _state = MutableStateFlow(TvHomeState())
    val state: StateFlow<TvHomeState> = _state.asStateFlow()

    private val _playRequest = MutableStateFlow<TvPlayRequest>(TvPlayRequest.Idle)
    val playRequest: StateFlow<TvPlayRequest> = _playRequest.asStateFlow()

    /**
     * Which spotlight title the hero is showing.
     *
     * Held here rather than remembered in the composition because pushing Detail disposes
     * the home subtree: a remembered index came back as 0, so the spotlight silently reset
     * to the first title while the user was away. That also broke focus restoration - the
     * card the hero was showing no longer matched the card that had been opened, so nothing
     * claimed focus and the shell dropped the user on the navigation rail.
     */
    private val _heroIndex = MutableStateFlow(0)
    val heroIndex: StateFlow<Int> = _heroIndex.asStateFlow()

    /** Moves the spotlight on, wrapping. [count] is the current item count. */
    fun advanceHero(count: Int) {
        if (count <= 0) return
        _heroIndex.value = (_heroIndex.value + 1) % count
    }

    /**
     * Clamps the spotlight into range.
     *
     * Switching source replaces the shortlist, and an index left pointing past the new end
     * would read off the end of the list.
     */
    fun clampHero(count: Int) {
        if (count <= 0 || _heroIndex.value < count) return
        _heroIndex.value = 0
    }

    /**
     * Continue Watching, kept separate from [state].
     *
     * Its own flow because it comes from persisted history rather than the selected
     * source's catalogue: folding it into [state] would rebuild the row every time a
     * feed request completed, and clear it on every source switch even though watch
     * history spans all sources.
     *
     * Filtered exactly as the phone home does - finished titles are resume candidates
     * for nobody, and an entry barely started is noise.
     */
    val continueWatching: StateFlow<List<WatchHistoryEntry>> = store.history
        .map { history ->
            history
                .filterNot { it.isFinished }
                .filter { it.progress > 0.005f }
                .sortedByDescending { it.updatedAt }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private var loadJob: Job? = null
    private var playJob: Job? = null

    fun removeFromHistory(key: String) {
        viewModelScope.launch { store.removeHistory(key) }
    }

    /**
     * Resolves what the hero's Play button should open.
     *
     * Resumes where the user left off when there is unfinished history for this title,
     * otherwise starts at the first episode - the same rule the detail page's play button
     * follows, so the two never disagree about what "play" means.
     *
     * History is consulted before the network: an entry already names the episode, so a
     * resume needs the detail only to confirm the episode still exists in the catalogue.
     *
     * Re-entrant presses cancel the previous attempt rather than queueing. Holding OK on a
     * remote repeats the key, and without this every repeat would start another fetch and
     * the last one to land would win.
     */
    fun play(card: AnimeCard) {
        playJob?.cancel()
        _playRequest.value = TvPlayRequest.Resolving(card.key)

        playJob = viewModelScope.launch {
            val history = store.historyFor(card.sourceId, card.url)
                ?.takeIf { !it.isFinished && it.positionMs > MIN_RESUME_MS }

            val episodes = repository.detail(card.sourceId, card.url)
                .getOrNull()
                ?.episodes
                .orEmpty()

            // Matched by url rather than trusting the stored episode outright: a source can
            // renumber or drop episodes between sessions, and resuming into a url that is
            // no longer in the list strands the player on an unresolvable episode.
            val resume = history?.let { entry ->
                episodes.firstOrNull { it.url == entry.episodeUrl }?.let { it to entry.positionMs }
            }
            val target = resume ?: episodes.firstOrNull()?.let { it to 0L }

            _playRequest.value = when {
                target == null -> TvPlayRequest.Unavailable(card)
                else -> TvPlayRequest.Ready(
                    sourceId = card.sourceId,
                    animeUrl = card.url,
                    episodeUrl = target.first.url,
                    resumeMs = target.second,
                )
            }
        }
    }

    /**
     * Clears a resolved request once the screen has acted on it.
     *
     * Without this the stored route would fire again every time the home screen came back
     * into composition, sending the user into the player instead of back to the rows.
     */
    fun onPlayHandled() {
        _playRequest.value = TvPlayRequest.Idle
    }

    init {
        // The rail owns the source now, so the feed follows the stored choice rather
        // than holding its own. Observed, not read once: picking a source in the rail
        // must reload the feed without the home screen being told directly.
        viewModelScope.launch {
            store.settings
                .map { it.tvSourceId }
                .distinctUntilChanged()
                .collect { id ->
                    val target = _state.value.sources.firstOrNull { it.id == id }
                    if (target != null && target.id != _state.value.selected?.id) {
                        select(target)
                    }
                }
        }

        viewModelScope.launch {
            // Re-derived whenever extensions change, so installing one appears here
            // without leaving the screen.
            extensions.installed.collect { installed ->
                val sources = installed.flatMap { extension ->
                    extension.sources
                        .filterIsInstance<AnimeCatalogueSource>()
                        .map { source ->
                            SourceEntry(
                                id = source.id,
                                name = source.name,
                                lang = source.lang,
                                supportsLatest = runCatching { source.supportsLatest }
                                    .getOrDefault(false),
                                icon = extension.icon,
                            )
                        }
                }.sortedBy { it.name.lowercase() }

                // The current selection is kept across reloads unless it disappeared,
                // so installing a second extension does not move the user's feed. On the
                // first pass there is none, so the stored choice stands in - otherwise
                // every launch would reset the feed to whichever source sorts first.
                val remembered = store.currentSettings().tvSourceId
                val keep = (_state.value.selected?.id ?: remembered)?.let { id ->
                    sources.firstOrNull { it.id == id }
                }

                _state.value = _state.value.copy(sources = sources, selected = keep)

                val target = keep ?: sources.firstOrNull()
                if (target != null && (keep == null || _state.value.isEmpty)) {
                    select(target)
                } else if (sources.isEmpty()) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        popular = emptyList(),
                        latest = emptyList(),
                    )
                }
            }
        }
    }

    /**
     * Switches the feed to [source].
     *
     * Does not persist: the rail is what records the choice, and writing it back here
     * would loop through the settings flow that drives this method.
     */
    fun select(source: SourceEntry) {
        loadJob?.cancel()

        _state.value = _state.value.copy(
            selected = source,
            isLoading = true,
            errorMessage = null,
            // Cleared rather than kept: leaving the previous source's posters on screen
            // under a new source's name is worse than an honest empty moment. The page
            // counter resets with them, or the next append would fetch page 5 of a
            // catalogue showing page 1.
            popular = emptyList(),
            latest = emptyList(),
            latestPage = 0,
            hasMoreLatest = true,
            isAppending = false,
        )

        loadJob = viewModelScope.launch {
            // Fetched together: they are independent requests to the same source, and
            // serialising them doubles the wait for no benefit.
            val (popular, latest) = coroutineScope {
                val popularDeferred = async { repository.popular(source.id) }
                val latestDeferred = async {
                    if (source.supportsLatest) repository.latest(source.id, page = 1) else null
                }
                popularDeferred.await() to latestDeferred.await()
            }

            val popularItems = popular.getOrNull().orEmpty()
            val latestItems = latest?.getOrNull().orEmpty()

            _state.value = _state.value.copy(
                popular = popularItems,
                latest = latestItems,
                latestPage = if (latestItems.isEmpty()) 0 else 1,
                isLoading = false,
                // Inferred from an empty page rather than the ABI's hasNextPage, which
                // is unreliable across sources - running dry is the only dependable
                // signal.
                hasMoreLatest = latestItems.isNotEmpty(),
                // Only when nothing loaded at all: one feed failing while the other
                // returned titles is not worth an error over content the user can see.
                errorMessage = (popular.exceptionOrNull() ?: latest?.exceptionOrNull())
                    ?.let { it.message ?: "This source could not be reached." }
                    ?.takeIf { popularItems.isEmpty() && latestItems.isEmpty() },
            )
        }
    }

    /**
     * Appends the next page of Latest.
     *
     * Guarded against re-entry: the grid fires this as focus approaches the end, which
     * happens repeatedly while a page is still in flight.
     */
    fun loadMoreLatest() {
        val current = _state.value
        val source = current.selected ?: return

        if (current.isLoading || current.isAppending || !current.hasMoreLatest) return
        if (!source.supportsLatest) return

        _state.value = current.copy(isAppending = true)

        viewModelScope.launch {
            val nextPage = current.latestPage + 1
            val page = repository.latest(source.id, page = nextPage).getOrNull().orEmpty()

            _state.value = _state.value.copy(
                // De-duplicated by key: several sources repeat entries across pages, and
                // a duplicate key crashes a lazy grid rather than merely looking wrong.
                latest = (_state.value.latest + page).distinctBy { it.key },
                latestPage = nextPage,
                isAppending = false,
                hasMoreLatest = page.isNotEmpty(),
            )
        }
    }

    companion object {
        /**
         * Below this, a stored position is treated as never started.
         *
         * Matches the detail page's resume threshold: a few seconds in is where the user
         * was still deciding whether to watch, and resuming there is indistinguishable
         * from starting over while looking like the button ignored them.
         */
        private const val MIN_RESUME_MS = 5_000L

        fun factory(
            repository: AnimeRepository,
            extensions: ExtensionManager,
            store: WatchBoxStore,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TvHomeViewModel(repository, extensions, store) as T
        }
    }
}

/**
 * The titles the hero carousel pages through.
 *
 * Taken from Popular, which is already the shortlist the row presents, and capped: a
 * carousel is only useful while the user can believe they might see all of it, and a
 * spotlight of thirty titles is just Popular again with one item visible at a time.
 *
 * Falls back to Latest so a source with no Popular feed still gets a hero rather than an
 * empty band above the rows. The first entry is also what seeds the backdrop before the
 * D-pad has touched anything, so the order matches what is on screen at rest.
 */
fun TvHomeState.heroItems(): List<AnimeCard> =
    (popular.takeIf { it.isNotEmpty() } ?: latest).take(HERO_ITEM_COUNT)

/**
 * How many titles the hero rotates through.
 *
 * Five is enough to feel like a curated spotlight and few enough that the dots stay
 * countable at a glance from across a room.
 */
const val HERO_ITEM_COUNT = 5
