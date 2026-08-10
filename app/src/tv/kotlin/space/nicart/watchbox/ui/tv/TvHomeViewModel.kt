package space.nicart.watchbox.ui.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import space.nicart.watchbox.data.local.WatchBoxStore
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

class TvHomeViewModel(
    private val repository: AnimeRepository,
    private val extensions: ExtensionManager,
    private val store: WatchBoxStore,
) : ViewModel() {

    private val _state = MutableStateFlow(TvHomeState())
    val state: StateFlow<TvHomeState> = _state.asStateFlow()

    private var loadJob: Job? = null

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
 * First card, used to seed the backdrop before focus lands.
 *
 * Popular first, matching the on-screen order, so the backdrop shows what the user is
 * looking at rather than something further down.
 */
fun TvHomeState.firstCard(): AnimeCard? =
    popular.firstOrNull() ?: latest.firstOrNull()
