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
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.extension.ExtensionManager
import space.nicart.watchbox.extension.model.Extension
import space.nicart.watchbox.extension.model.InstallStep

data class ExtensionsUiState(
    val installed: List<Extension.Installed> = emptyList(),
    val available: List<Extension.Available> = emptyList(),
    /** Filter text applied to both lists. */
    val query: String = "",
    val failures: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    /** Package name -> current step, for per-row progress. */
    val installing: Map<String, InstallStep> = emptyMap(),
)

/** Carries the four local flows through the outer [combine], which is full. */
private data class LocalState(
    val installing: Map<String, InstallStep>,
    val refreshing: Boolean,
    val error: String?,
    val query: String,
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
    private val _query = MutableStateFlow("")

    val uiState: StateFlow<ExtensionsUiState> = combine(
        extensions.installed,
        extensions.available,
        extensions.failures,
        extensions.isLoading,
        combine(_installing, _refreshing, _error, _query) { installing, refreshing, error, query ->
            LocalState(installing, refreshing, error, query)
        },
    ) { installed, available, failures, isLoading, local ->
        val installedPkgs = installed.map { it.pkgName }.toSet()
        val nsfwAllowed = nsfwEnabled

        ExtensionsUiState(
            installed = installed.filter { it.matches(local.query) },
            available = available
                .filterNot { it.pkgName in installedPkgs }
                .filter { nsfwAllowed || !it.isNsfw }
                .filter { it.matches(local.query) },
            query = local.query,
            failures = failures.map { "${it.pkgName}: ${it.reason}" },
            isLoading = isLoading,
            isRefreshing = local.refreshing,
            errorMessage = local.error,
            installing = local.installing,
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

            val repoUrl = store.currentSettings().repoUrl
            extensions.refreshAvailable(repoUrl)
                .onFailure { _error.value = it.message ?: "Could not reach the repository." }

            _refreshing.value = false
        }
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

    fun onQueryChange(query: String) {
        _query.value = query
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
