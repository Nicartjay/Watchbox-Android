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
/** The settings-derived and panel flags, grouped so the nested combine stays legible. */
private data class ScreenFlags(
    val repos: List<ExtensionRepo>,
    val panelOpen: Boolean,
    val partial: Boolean,
    val dismissedFailures: Set<String>,
)

private data class LocalState(
    val installing: Map<String, InstallStep>,
    val refreshing: Boolean,
    val error: String?,
    val filters: ExtensionFilters,
    val repos: List<ExtensionRepo>,
    val panelOpen: Boolean,
    val partial: Boolean,
    /** Packages whose load failure has been dismissed; see `dismissFailures`. */
    val dismissedFailures: Set<String>,
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

    /**
     * Packages whose load failure the user has dismissed.
     *
     * Declared here rather than beside `dismissFailures` because `uiState` reads it: Kotlin
     * initialises properties in declaration order, so a flow the combine touches has to exist
     * before it.
     */
    private val _dismissedFailures = MutableStateFlow<Set<String>>(emptySet())
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
            // A named holder rather than a fourth positional tuple: combine caps out at five
            // flows, and nesting Triples inside Triples stops being readable at this depth.
            combine(
                store.settings,
                _panelOpen,
                _partial,
                _dismissedFailures,
            ) { settings, panelOpen, partial, dismissed ->
                ScreenFlags(settings.repos, panelOpen, partial, dismissed)
            },
        ) { installing, refreshing, error, filters, flags ->
            LocalState(
                installing = installing,
                refreshing = refreshing,
                error = error,
                filters = filters,
                repos = flags.repos,
                panelOpen = flags.panelOpen,
                partial = flags.partial,
                dismissedFailures = flags.dismissedFailures,
            )
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
            failures = failures
                .filterNot { it.pkgName in local.dismissedFailures }
                .map { "${it.pkgName}: ${it.reason}" },
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

                // Un-dismiss this package on any install attempt.
                //
                // Dismissal previously outlived a reinstall: it recorded package names, and a
                // reinstall produces the same name, so pressing Install again reported nothing
                // whether it worked or failed. Someone retrying an install is precisely the
                // person who needs to see the result.
                _dismissedFailures.value = _dismissedFailures.value - extension.pkgName

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

    /**
     * Hides the load-failure banner.
     *
     * Records which packages were dismissed rather than clearing the list: the failures are
     * recomputed on every extension reload, so a plain flag would be undone the moment anything
     * else changed. Keying on the package also means a *different* extension failing later still
     * reports, which a blanket "never show again" would suppress.
     *
     * The record is cleared for a package when the user installs it again - see [install]. Without
     * that, dismissing once silenced every future attempt at the same extension, since a
     * reinstall reports under the same name.
     */
    fun dismissFailures() {
        _dismissedFailures.value = extensions.failures.value.map { it.pkgName }.toSet()
    }

    /**
     * Installs the newer build over an existing extension.
     *
     * The same code path as a first install - the installer replaces the file in place and the
     * loader re-reads it - so this only exists to give the action a name that matches what the
     * user is doing, and to look up the available build the update badge refers to.
     */
    fun update(extension: Extension.Installed) {
        val newer = extensions.available.value.firstOrNull { it.pkgName == extension.pkgName }

        if (newer == null) {
            // The badge came from a repo listing that has since gone away.
            _error.value = "No update is available for ${extension.name} any more."
            return
        }

        install(newer)
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
