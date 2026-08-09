package space.nicart.watchbox.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.nicart.watchbox.core.ui.AppTheme
import space.nicart.watchbox.data.local.AppSettings
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.ui.player.SubtitleBackground
import space.nicart.watchbox.ui.player.SubtitleEdgeWidth
import space.nicart.watchbox.ui.player.SubtitleSize
import space.nicart.watchbox.data.remote.AppUpdate
import space.nicart.watchbox.data.remote.UpdateChecker
import space.nicart.watchbox.data.remote.UpdateDownload
import space.nicart.watchbox.data.remote.UpdateInstaller
import space.nicart.watchbox.data.remote.UpdateResult

/** What the update row is currently showing. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val update: AppUpdate) : UpdateUiState
    data class Downloading(val percent: Int) : UpdateUiState
    data object Launching : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

class SettingsViewModel(
    private val store: WatchBoxStore,
    private val updateChecker: UpdateChecker,
    private val updateInstaller: UpdateInstaller,
    val currentVersion: String,
) : ViewModel() {

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private var updateJob: Job? = null

    init {
        // Silent check on first open: only surfaces something when an update
        // actually exists, so it never interrupts with "you are up to date".
        viewModelScope.launch {
            val current = store.settings.first()
            if (!current.shouldAutoCheck) return@launch

            store.markUpdateChecked()
            val result = updateChecker.check()
            if (result is UpdateResult.Available &&
                result.update.versionName != current.skippedUpdateVersion
            ) {
                _updateState.value = UpdateUiState.Available(result.update)
            }
        }
    }

    /** Explicit check from the settings row; reports every outcome. */
    fun checkForUpdates() {
        if (_updateState.value is UpdateUiState.Downloading) return

        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            _updateState.value = UpdateUiState.Checking
            store.markUpdateChecked()

            _updateState.value = when (val result = updateChecker.check()) {
                is UpdateResult.Available -> UpdateUiState.Available(result.update)
                UpdateResult.UpToDate -> UpdateUiState.UpToDate
                is UpdateResult.Failed -> UpdateUiState.Failed(result.message)
            }
        }
    }

    fun downloadUpdate(update: AppUpdate) {
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            updateInstaller.downloadAndInstall(update).collect { step ->
                _updateState.value = when (step) {
                    is UpdateDownload.Progress -> UpdateUiState.Downloading(step.percent)
                    UpdateDownload.Launching -> UpdateUiState.Launching
                    is UpdateDownload.Failed -> UpdateUiState.Failed(step.message)
                }
            }
        }
    }

    /** Dismisses one version so it is not offered again on launch. */
    fun skipUpdate(update: AppUpdate) {
        viewModelScope.launch {
            store.skipUpdateVersion(update.versionName)
            _updateState.value = UpdateUiState.Idle
        }
    }

    fun dismissUpdateState() {
        _updateState.value = UpdateUiState.Idle
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        viewModelScope.launch { store.setAutoCheckUpdates(enabled) }
    }

    val settings: StateFlow<AppSettings> = store.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch { store.setTheme(theme) }
    }

    fun setAmoled(enabled: Boolean) {
        viewModelScope.launch { store.setAmoled(enabled) }
    }

    fun setAutoPlayNext(enabled: Boolean) {
        viewModelScope.launch { store.setAutoPlayNext(enabled) }
    }

    // ------------------------------------------------------------ subtitles

    fun setSubtitleSize(size: SubtitleSize) {
        viewModelScope.launch { store.setSubtitleSize(size) }
    }

    fun setSubtitleBackground(background: SubtitleBackground) {
        viewModelScope.launch { store.setSubtitleBackground(background) }
    }

    fun setSubtitleTextColor(color: Int) {
        viewModelScope.launch { store.setSubtitleTextColor(color) }
    }

    fun setSubtitleBackgroundOpacity(opacity: Float) {
        viewModelScope.launch { store.setSubtitleBackgroundOpacity(opacity) }
    }

    fun setSubtitleEdgeWidth(width: SubtitleEdgeWidth) {
        viewModelScope.launch { store.setSubtitleEdgeWidth(width) }
    }

    fun setSubtitleBold(bold: Boolean) {
        viewModelScope.launch { store.setSubtitleBold(bold) }
    }

    fun addRepo(url: String) {
        viewModelScope.launch { store.addRepo(url) }
    }

    fun removeRepo(url: String) {
        viewModelScope.launch { store.removeRepo(url) }
    }

    fun setRepoEnabled(url: String, enabled: Boolean) {
        viewModelScope.launch { store.setRepoEnabled(url, enabled) }
    }

    fun resetRepos() {
        viewModelScope.launch { store.resetRepos() }
    }

    fun setNsfwEnabled(enabled: Boolean) {
        viewModelScope.launch { store.setNsfwSourcesEnabled(enabled) }
    }

    fun clearHistory() {
        viewModelScope.launch { store.clearHistory() }
    }

    fun clearWatchlist() {
        viewModelScope.launch { store.clearWatchlist() }
    }

    companion object {
        fun factory(
            store: WatchBoxStore,
            updateChecker: UpdateChecker,
            updateInstaller: UpdateInstaller,
            currentVersion: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
                store = store,
                updateChecker = updateChecker,
                updateInstaller = updateInstaller,
                currentVersion = currentVersion,
            ) as T
        }
    }
}
