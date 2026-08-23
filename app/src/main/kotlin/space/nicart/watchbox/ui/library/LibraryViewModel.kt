package space.nicart.watchbox.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.nicart.watchbox.data.local.DownloadEntry
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.data.local.WatchHistoryEntry
import space.nicart.watchbox.data.local.WatchlistEntry
import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.download.DownloadController
import space.nicart.watchbox.download.DownloadProgress
import space.nicart.watchbox.download.DownloadStorage

data class LibraryUiState(
    val myList: List<WatchlistEntry> = emptyList(),
    val continueWatching: List<WatchHistoryEntry> = emptyList(),
    val history: List<WatchHistoryEntry> = emptyList(),
    /**
     * Downloads, newest first, grouped by title in the UI rather than here.
     *
     * Per-episode where the other three lists are per-title, which is why the Downloads tab
     * renders rows instead of sharing the poster grid: a poster can carry one progress bar,
     * and a download needs a state, a size and a control.
     */
    val downloads: List<DownloadEntry> = emptyList(),
    /** Live byte progress, keyed by [DownloadEntry.key]. Empty for anything not running. */
    val downloadProgress: Map<String, DownloadProgress> = emptyMap(),
    /** Volumes currently mounted, so a download on a removed card can be marked. */
    val mountedVolumes: Set<String> = emptySet(),
)

@UnstableApi
class LibraryViewModel(
    private val store: WatchBoxStore,
    private val downloads: DownloadController,
    private val storage: DownloadStorage,
) : ViewModel() {

    init {
        // Latches internally, so the repeated call from every screen that touches downloads
        // is harmless. Needed here as well as on the detail page: the Library is a legitimate
        // first stop after a restart, and without this the registry would be shown
        // unreconciled.
        downloads.start()
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        store.watchlist,
        store.history,
        store.downloads,
        downloads.progress,
    ) { watchlist, history, downloadEntries, progress ->
        LibraryUiState(
            myList = watchlist.sortedByDescending { it.addedAt },
            continueWatching = history
                .filterNot { it.isFinished }
                .filter { it.progress > 0.005f }
                .sortedByDescending { it.updatedAt },
            history = history.sortedByDescending { it.updatedAt },
            // Active first, then newest. Something still downloading is what the user came
            // to check on; a finished file is not going anywhere.
            downloads = downloadEntries.sortedWith(
                compareByDescending<DownloadEntry> { !it.isComplete }
                    .thenByDescending { it.createdAt },
            ),
            downloadProgress = progress,
            mountedVolumes = storage.volumes().map { it.id }.toSet(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
    )

    fun removeFromHistory(key: String) {
        viewModelScope.launch { store.removeHistory(key) }
    }

    fun removeFromWatchlist(key: String) {
        viewModelScope.launch {
            store.watchlist.first()
                .firstOrNull { it.key == key }
                ?.let { store.toggleWatchlist(it) }
        }
    }

    fun pauseDownload(key: String) = downloads.pause(key)

    fun resumeDownload(key: String) = downloads.resume(key)

    fun deleteDownload(key: String) = downloads.remove(key)

    companion object {
        fun factory(
            store: WatchBoxStore,
            downloads: DownloadController,
            storage: DownloadStorage,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LibraryViewModel(store, downloads, storage) as T
            }
    }
}

// --------------------------------------------------------------- mappers

internal fun WatchlistEntry.toCard(): AnimeCard = AnimeCard(
    sourceId = sourceId,
    url = animeUrl,
    title = title,
    posterUrl = posterUrl,
    sourceName = sourceName,
)

internal fun WatchHistoryEntry.toCard(): AnimeCard = AnimeCard(
    sourceId = sourceId,
    url = animeUrl,
    title = title,
    posterUrl = posterUrl,
    sourceName = sourceName,
)

internal fun DownloadEntry.toCard(): AnimeCard = AnimeCard(
    sourceId = sourceId,
    url = animeUrl,
    title = title,
    posterUrl = posterUrl,
    sourceName = sourceName,
)
