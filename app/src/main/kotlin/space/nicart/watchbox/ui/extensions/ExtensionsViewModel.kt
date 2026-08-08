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
    val failures: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    /** Package name -> current step, for per-row progress. */
    val installing: Map<String, InstallStep> = emptyMap(),
)

class ExtensionsViewModel(
    private val extensions: ExtensionManager,
    private val store: WatchBoxStore,
) : ViewModel() {

    private val _installing = MutableStateFlow<Map<String, InstallStep>>(emptyMap())
    private val _refreshing = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ExtensionsUiState> = combine(
        extensions.installed,
        extensions.available,
        extensions.failures,
        extensions.isLoading,
        combine(_installing, _refreshing, _error) { installing, refreshing, error ->
            Triple(installing, refreshing, error)
        },
    ) { installed, available, failures, isLoading, (installing, refreshing, error) ->
        val installedPkgs = installed.map { it.pkgName }.toSet()
        val nsfwAllowed = nsfwEnabled

        ExtensionsUiState(
            installed = installed,
            available = available
                .filterNot { it.pkgName in installedPkgs }
                .filter { nsfwAllowed || !it.isNsfw },
            failures = failures.map { "${it.pkgName}: ${it.reason}" },
            isLoading = isLoading,
            isRefreshing = refreshing,
            errorMessage = error,
            installing = installing,
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
