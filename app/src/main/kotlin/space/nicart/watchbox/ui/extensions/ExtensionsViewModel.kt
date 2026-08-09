package space.nicart.watchbox.ui.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.nicart.watchbox.data.local.ExtensionRepo
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.extension.ExtensionManager
import space.nicart.watchbox.extension.model.Extension
import space.nicart.watchbox.extension.model.InstallStep

data class ExtensionsUiState(
    val installed: List<Extension.Installed> = emptyList(),
    val available: List<Extension.Available> = emptyList(),
    val filters: ExtensionFilters = ExtensionFilters(),
    val failures: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    /** Package name -> current step, for per-row progress. */
    val installing: Map<String, InstallStep> = emptyMap(),
    /** Language codes offered by the filter panel. */
    val languages: List<String> = emptyList(),
    /** Configured repositories, for the repository filter. */
    val repos: List<ExtensionRepo> = emptyList(),
    val filterPanelOpen: Boolean = false,
    /** True when some repositories loaded and others failed. */
    val partialRefresh: Boolean = false,
) {
    /** Convenience for the search field. */
    val query: String get() = filters.query
}

/** Carries the local flows through the outer [combine], which is full. */
private data class LocalState(
    val installing: Map<String, InstallStep>,
    val refreshing: Boolean,
    val error: String?,
    val filters: ExtensionFilters,
    val repos: List<ExtensionRepo>,
    val panelOpen: Boolean,
    val partial: Boolean,
)

/**
 * Whether an extension matches a filter term.
 *
 * Matches on language as well as name so "es" finds the Spanish extensions, and
 * on the last package-name segment so a partially-known id still resolves.
 *
 * Only the last segment is used, never the whole package name: every extension
 * shares the `eu.kanade.tachiyomi.animeextension` prefix, so matching the full
 * string makes short queries hit everything - "de" matches via "kan*ade*" and "en"
 * via "ext*en*sion", which silently turns a language search into a no-op.
 *
 * Blank matches everything, keeping the unfiltered list the default.
 */
internal fun Extension.matches(query: String): Boolean {
    val term = query.trim()
    if (term.isBlank()) return true

    return name.contains(term, ignoreCase = true) ||
        lang.contains(term, ignoreCase = true) ||
        pkgName.substringAfterLast('.').contains(term, ignoreCase = true)
}

class ExtensionsViewModel(
    private val extensions: ExtensionManager,
    private val store: WatchBoxStore,
) : ViewModel() {

    private val _installing = MutableStateFlow<Map<String, InstallStep>>(emptyMap())
    private val _refreshing = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _filters = MutableStateFlow(ExtensionFilters())
    private val _panelOpen = MutableStateFlow(false)
    private val _partial = MutableStateFlow(false)

    val uiState: StateFlow<ExtensionsUiState> = combine(
        extensions.installed,
        extensions.available,
        extensions.failures,
        extensions.isLoading,
        combine(
            _installing,
            _refreshing,
            _error,
            _filters,
            combine(store.settings, _panelOpen, _partial) { settings, panelOpen, partial ->
                Triple(settings.repos, panelOpen, partial)
            },
        ) { installing, refreshing, error, filters, (repos, panelOpen, partial) ->
            LocalState(installing, refreshing, error, filters, repos, panelOpen, partial)
        },
    ) { installed, available, failures, isLoading, local ->
        val installedPkgs = installed.map { it.pkgName }.toSet()
        val nsfwAllowed = nsfwEnabled

        // NSFW visibility is decided before filtering, so the 18+ filter can only
        // ever narrow what the setting already permits.
        val visibleAvailable = available
            .filterNot { it.pkgName in installedPkgs }
            .filter { nsfwAllowed || !it.isNsfw }

        ExtensionsUiState(
            installed = installed.applyFilters(local.filters),
            available = visibleAvailable.applyFilters(local.filters),
            filters = local.filters,
            failures = failures.map { "${it.pkgName}: ${it.reason}" },
            isLoading = isLoading,
            isRefreshing = local.refreshing,
            errorMessage = local.error,
            installing = local.installing,
            // Offered languages come from the unfiltered lists, or selecting one
            // would remove every other option from the panel.
            languages = (installed + visibleAvailable).availableLanguages(),
            repos = local.repos,
            filterPanelOpen = local.panelOpen,
            partialRefresh = local.partial,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ExtensionsUiState(),
    )

    private var nsfwEnabled = false

    init {
        viewModelScope.launch {
            store.settings.collect { nsfwEnabled = it.nsfwSourcesEnabled }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            _error.value = null

            val result = extensions.refreshAvailable(store.currentSettings().repos)

            // Reported per repository: with several configured, "could not reach the
            // repository" would not say which, and a single failure among four is a
            // very different situation from total failure.
            _error.value = when {
                result.allFailed -> result.failures.first().let { failure ->
                    "${failure.repo.displayName}: ${failure.message}"
                }

                result.hasFailures -> result.failures
                    .joinToString(prefix = "Could not reach ") { it.repo.displayName }

                else -> null
            }
            _partial.value = result.hasFailures && !result.allFailed

            _refreshing.value = false
        }
    }

    fun onQueryChange(query: String) {
        _filters.value = _filters.value.copy(query = query)
    }

    fun setFilterPanelOpen(open: Boolean) {
        _panelOpen.value = open
    }

    fun toggleLanguage(lang: String) {
        val current = _filters.value.languages
        _filters.value = _filters.value.copy(
            languages = if (lang in current) current - lang else current + lang,
        )
    }

    fun setNsfwFilter(filter: NsfwFilter) {
        _filters.value = _filters.value.copy(nsfw = filter)
    }

    fun toggleRepo(repoUrl: String) {
        val current = _filters.value.repoUrls
        _filters.value = _filters.value.copy(
            repoUrls = if (repoUrl in current) current - repoUrl else current + repoUrl,
        )
    }

    /** Clears every filter but keeps the search text, which is typed separately. */
    fun resetFilters() {
        _filters.value = ExtensionFilters(query = _filters.value.query)
    }

    fun install(extension: Extension.Available) {
        viewModelScope.launch {
            extensions.install(extension).collect { step ->
                _installing.value = _installing.value + (extension.pkgName to step)

                if (step == InstallStep.Installed) {
                    // Reload so the new sources appear everywhere at once.
                    extensions.reloadInstalled()
                    _installing.value = _installing.value - extension.pkgName
                }

                if (step == InstallStep.Error) {
                    _error.value = extensions.lastInstallError
                        ?: "Could not install ${extension.name}."
                    _installing.value = _installing.value - extension.pkgName
                }
            }
        }
    }

    fun uninstall(extension: Extension.Installed) {
        viewModelScope.launch {
            if (!extensions.uninstall(extension.pkgName)) {
                _error.value = "Could not remove ${extension.name}."
            }
        }
    }

    fun dismissError() {
        _error.value = null
    }

    companion object {
        fun factory(
            extensions: ExtensionManager,
            store: WatchBoxStore,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ExtensionsViewModel(extensions, store) as T
        }
    }
}
