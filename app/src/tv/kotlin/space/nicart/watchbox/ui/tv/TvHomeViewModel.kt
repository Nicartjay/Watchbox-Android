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
import kotlinx.coroutines.launch
import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.domain.AnimeRepository
import space.nicart.watchbox.domain.AnimeRow
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
    val rows: List<AnimeRow> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
) {
    val hasNoSources: Boolean get() = sources.isEmpty()
}

class TvHomeViewModel(
    private val repository: AnimeRepository,
    private val extensions: ExtensionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(TvHomeState())
    val state: StateFlow<TvHomeState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
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
                // so installing a second extension does not move the user's feed.
                val keep = _state.value.selected?.let { current ->
                    sources.firstOrNull { it.id == current.id }
                }

                _state.value = _state.value.copy(sources = sources, selected = keep)

                val target = keep ?: sources.firstOrNull()
                if (target != null && (keep == null || _state.value.rows.isEmpty())) {
                    select(target)
                } else if (sources.isEmpty()) {
                    _state.value = _state.value.copy(isLoading = false, rows = emptyList())
                }
            }
        }
    }

    /** Switches the feed to [source]. */
    fun select(source: SourceEntry) {
        loadJob?.cancel()

        _state.value = _state.value.copy(
            selected = source,
            isLoading = true,
            errorMessage = null,
            // Cleared rather than kept: leaving the previous source's posters on screen
            // under a new source's name is worse than an honest empty moment.
            rows = emptyList(),
        )

        loadJob = viewModelScope.launch {
            // Fetched together: they are independent requests to the same source, and
            // serialising them doubles the wait for no benefit.
            val (popular, latest) = coroutineScope {
                val popularDeferred = async { repository.popular(source.id) }
                val latestDeferred = async {
                    if (source.supportsLatest) repository.latest(source.id) else null
                }
                popularDeferred.await() to latestDeferred.await()
            }

            val rows = buildList {
                // Latest first: it is the row that changes between visits, so it is
                // what the backdrop should be showing when the screen opens. Popular is
                // largely static and stays useful one press down.
                //
                // Omitted entirely when the source does not support it, rather than
                // shown empty - a source without a latest feed is normal.
                latest?.getOrNull()?.takeIf { it.isNotEmpty() }?.let { items ->
                    add(
                        AnimeRow(
                            sourceId = source.id,
                            sourceName = source.name,
                            title = ROW_LATEST,
                            items = items,
                            isLatest = true,
                        ),
                    )
                }

                popular.getOrNull()?.takeIf { it.isNotEmpty() }?.let { items ->
                    add(
                        AnimeRow(
                            sourceId = source.id,
                            sourceName = source.name,
                            title = ROW_POPULAR,
                            items = items,
                        ),
                    )
                }
            }

            _state.value = _state.value.copy(
                rows = rows,
                isLoading = false,
                // Only when nothing loaded at all: one feed failing while the other
                // returned titles is not worth an error over content the user can see.
                errorMessage = (popular.exceptionOrNull() ?: latest?.exceptionOrNull())
                    ?.let { it.message ?: "This source could not be reached." }
                    ?.takeIf { rows.isEmpty() },
            )
        }
    }

    companion object {
        fun factory(
            repository: AnimeRepository,
            extensions: ExtensionManager,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TvHomeViewModel(repository, extensions) as T
        }
    }
}

/**
 * Row labels.
 *
 * Just the feed name: the source is already named by the picker in the top-right corner,
 * so "Popular on Cineby" repeats it on every row and buries the word that distinguishes
 * one row from the other.
 *
 * Latest is always first, and the order is fixed - so the rows do not reshuffle between
 * sources depending on which feeds each one happens to support.
 */
private const val ROW_POPULAR = "Popular"
private const val ROW_LATEST = "Latest"

/** First card across all rows, used to seed the backdrop before focus lands. */
fun TvHomeState.firstCard(): AnimeCard? = rows.firstOrNull()?.items?.firstOrNull()
