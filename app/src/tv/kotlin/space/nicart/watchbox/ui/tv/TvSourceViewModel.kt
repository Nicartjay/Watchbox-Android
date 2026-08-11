package space.nicart.watchbox.ui.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.extension.ExtensionManager
import space.nicart.watchbox.ui.browse.SourceEntry

/**
 * The source chosen in the TV navigation rail.
 *
 * Owned by the shell rather than by the home screen, because the choice now applies to
 * both the home feed and search. Keeping it in [TvHomeViewModel] would mean search had
 * to reach into the home screen's state to find out what to query.
 *
 * The selection is persisted through [WatchBoxStore], which is also how the home feed
 * learns about it: both read the same key rather than one calling the other.
 */
class TvSourceViewModel(
    private val extensions: ExtensionManager,
    private val store: WatchBoxStore,
) : ViewModel() {

    private val _sources = MutableStateFlow<List<SourceEntry>>(emptyList())
    val sources: StateFlow<List<SourceEntry>> = _sources.asStateFlow()

    private val _selected = MutableStateFlow<SourceEntry?>(null)
    val selected: StateFlow<SourceEntry?> = _selected.asStateFlow()

    /**
     * Whether the source picker drawer is open.
     *
     * Lives here because the drawer and the home screen are siblings under the shell, so
     * neither can see the other's local state. The home screen needs it to hold the hero
     * carousel still while the drawer covers it - inferring that from focus instead was
     * wrong: focus also leaves the screen for the nav rail, where the hero is fully visible
     * and should keep moving.
     */
    private val _pickerOpen = MutableStateFlow(false)
    val pickerOpen: StateFlow<Boolean> = _pickerOpen.asStateFlow()

    fun setPickerOpen(open: Boolean) {
        _pickerOpen.value = open
    }

    init {
        viewModelScope.launch {
            // Re-derived whenever extensions change, so installing one appears in the
            // rail without leaving the screen.
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

                _sources.value = sources

                // The stored choice wins on first load, then the live one, so a reload
                // does not move the user's feed. Falls back to the first source: the rail
                // must always name something once anything is installed.
                val wanted = _selected.value?.id ?: store.currentSettings().tvSourceId
                _selected.value = sources.firstOrNull { it.id == wanted }
                    ?: sources.firstOrNull()
            }
        }
    }

    fun select(source: SourceEntry) {
        if (source.id == _selected.value?.id) return
        _selected.value = source
        viewModelScope.launch { store.setTvSourceId(source.id) }
    }

    companion object {
        fun factory(
            extensions: ExtensionManager,
            store: WatchBoxStore,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TvSourceViewModel(extensions, store) as T
        }
    }
}
