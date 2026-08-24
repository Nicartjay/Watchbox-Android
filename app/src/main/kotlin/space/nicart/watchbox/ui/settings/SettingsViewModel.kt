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
import space.nicart.watchbox.data.remote.SubtitleProvider
import space.nicart.watchbox.ui.player.SubtitleBackground
import space.nicart.watchbox.ui.player.SubtitleEdgeWidth
import space.nicart.watchbox.ui.player.SubtitleSize
import space.nicart.watchbox.data.remote.AppUpdate
import space.nicart.watchbox.data.remote.ReleaseNote
import space.nicart.watchbox.data.remote.UpdateChecker
import space.nicart.watchbox.data.remote.UpdateDownload
import space.nicart.watchbox.data.remote.UpdateInstaller
import space.nicart.watchbox.data.remote.UpdateResult
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.nicart.watchbox.download.DownloadController
import space.nicart.watchbox.download.DownloadStorage
import space.nicart.watchbox.download.DownloadVolume

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

@UnstableApi
class SettingsViewModel(
    private val store: WatchBoxStore,
    private val updateChecker: UpdateChecker,
    private val updateInstaller: UpdateInstaller,
    private val downloads: DownloadController,
    private val downloadStorage: DownloadStorage,
    val currentVersion: String,
) : ViewModel() {

    private val _storage = MutableStateFlow(StorageUiState())

    /** Disk usage, measured rather than totalled from the registry. */
    val storage: StateFlow<StorageUiState> = _storage.asStateFlow()

    /**
     * Re-measures disk usage.
     *
     * Walked from the filesystem, not summed from the registry: a partial download, an orphan
     * left by a crash and a file deleted by hand all make the registry's own figures a claim
     * rather than a measurement. Called on every screen resume for the same reason the battery
     * row re-reads its state - the number goes stale the moment a download finishes.
     */
    fun refreshStorage() {
        viewModelScope.launch {
            val used = withContext(Dispatchers.IO) { downloadStorage.usedBytes() }
            val volumeId = store.currentSettings().downloadVolume
            val volumes = withContext(Dispatchers.IO) { downloadStorage.volumes() }
            _storage.value = StorageUiState(
                usedBytes = used,
                freeBytes = volumes.firstOrNull { it.id == volumeId }?.freeBytes
                    ?: volumes.firstOrNull()?.freeBytes
                    ?: 0L,
                volumes = volumes,
                selectedVolume = volumeId ?: volumes.firstOrNull()?.id,
            )
        }
    }

    fun setDownloadWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            store.setDownloadWifiOnly(enabled)
            // Applied to the running queue as well as stored, or an item already waiting
            // would keep waiting on the old rule until something else restarted it.
            downloads.applyRequirements(enabled)
        }
    }

    fun setDownloadConcurrency(count: Int) {
        viewModelScope.launch {
            store.setDownloadConcurrency(count)
            // Applied to the running queue as well as stored, or the change would appear to do
            // nothing until the app was restarted.
            downloads.applyConcurrency(count)
        }
    }

    fun setDownloadVolume(volumeId: String) {
        viewModelScope.launch {
            store.setDownloadVolume(volumeId)
            refreshStorage()
        }
    }

    /**
     * Deletes every download.
     *
     * Both halves, in that order: the engine owns the bytes and the registry only indexes
     * them, so clearing the index first would leave files with nothing pointing at them.
     */
    fun clearDownloads() {
        viewModelScope.launch {
            downloads.removeAll()
            store.clearDownloads()
            refreshStorage()
        }
    }

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    /**
     * Recent release notes, for the About card.
     *
     * Empty until they arrive, and empty on failure. The card renders the version without
     * them, so an unreachable GitHub costs the notes rather than the section.
     */
    private val _changelog = MutableStateFlow<List<ReleaseNote>>(emptyList())
    val changelog: StateFlow<List<ReleaseNote>> = _changelog.asStateFlow()

    private var updateJob: Job? = null

    init {
        // The changelog is fetched on its own, unconditionally.
        //
        // It used to ride along inside the auto-check block below, which looked economical -
        // same endpoint, same open - but that block returns early unless an update check is
        // actually due. The check is throttled to once a day, so for the other 23 hours
        // About showed a version number and nothing else, and disabling automatic updates
        // hid the changelog permanently. What changed in the last release does not expire on
        // a timer, so it does not belong behind one.
        viewModelScope.launch {
            _changelog.value = updateChecker.changelog()
        }

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

    fun setBackgroundPlayback(enabled: Boolean) {
        viewModelScope.launch { store.setBackgroundPlayback(enabled) }
    }

    fun setAutoplayTrailers(enabled: Boolean) {
        viewModelScope.launch { store.setAutoplayTrailers(enabled) }
    }

    fun setTrailerMuteButton(enabled: Boolean) {
        viewModelScope.launch { store.setTrailerMuteButton(enabled) }
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

    fun setSubtitleProvider(provider: SubtitleProvider) {
        viewModelScope.launch { store.setSubtitleProvider(provider) }
    }

    /**
     * Preferred language for posters and title logos.
     *
     * Nothing more is needed here: the application observes this value and hands it to the
     * artwork client, which drops its cache so already-seen titles are re-resolved rather
     * than keeping the logos chosen under the previous language.
     */
    fun setArtworkLanguage(code: String) {
        viewModelScope.launch { store.setArtworkLanguage(code) }
    }

    fun setSubtitleApiKey(key: String) {
        viewModelScope.launch { store.setSubtitleApiKey(key) }
    }

    fun setUiScale(scale: Float) {
        viewModelScope.launch { store.setUiScale(scale) }
    }

    fun setPosterScale(scale: Float) {
        viewModelScope.launch { store.setPosterScale(scale) }
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
            downloads: DownloadController,
            downloadStorage: DownloadStorage,
            currentVersion: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
                store = store,
                updateChecker = updateChecker,
                updateInstaller = updateInstaller,
                downloads = downloads,
                downloadStorage = downloadStorage,
                currentVersion = currentVersion,
            ) as T
        }
    }
}

/** Disk usage for the Settings storage group. */
data class StorageUiState(
    val usedBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val volumes: List<DownloadVolume> = emptyList(),
    val selectedVolume: String? = null,
)
