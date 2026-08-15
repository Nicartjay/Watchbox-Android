package space.nicart.watchbox.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.data.remote.AppUpdate
import space.nicart.watchbox.data.remote.UpdateChecker
import space.nicart.watchbox.data.remote.UpdateDownload
import space.nicart.watchbox.data.remote.UpdateInstaller
import space.nicart.watchbox.data.remote.UpdateResult

/**
 * The launch update prompt.
 *
 * Separate from `SettingsViewModel`, which already checks for updates, because that one is
 * scoped to the Settings screen: its check only ran if the user happened to open Settings,
 * which is the one place they would have found the manual button anyway. This is scoped to
 * the app, so the check happens on launch wherever the user lands.
 *
 * Deliberately not merged with the Settings flow. That one reports every outcome, including
 * "up to date", because it answers a question the user just asked. This one must stay silent
 * unless there is something to say - an unprompted dialog announcing no news is an
 * interruption with no content.
 */
sealed interface UpdatePromptState {
    /** Nothing to show. */
    data object Hidden : UpdatePromptState

    /** An update exists and has not been skipped. */
    data class Available(val update: AppUpdate) : UpdatePromptState

    data class Downloading(val percent: Int) : UpdatePromptState

    /** The installer has been handed the APK; the system takes over from here. */
    data object Launching : UpdatePromptState

    data class Failed(val message: String) : UpdatePromptState
}

class UpdatePromptViewModel(
    private val store: WatchBoxStore,
    private val checker: UpdateChecker,
    private val installer: UpdateInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdatePromptState>(UpdatePromptState.Hidden)
    val state: StateFlow<UpdatePromptState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            val settings = store.settings.first()

            // Three gates, all of them the user's own choice:
            //  - automatic checking can be turned off entirely;
            //  - the check is throttled to once a day, since GitHub's unauthenticated API
            //    allows 60 requests an hour and checking every launch would not make an
            //    update arrive sooner;
            //  - a version already skipped is not offered again.
            if (!settings.shouldAutoCheck) return@launch

            store.markUpdateChecked()

            val result = checker.check()
            if (result !is UpdateResult.Available) return@launch
            if (result.update.versionName == settings.skippedUpdateVersion) return@launch

            _state.value = UpdatePromptState.Available(result.update)
        }
    }

    fun download(update: AppUpdate) {
        job?.cancel()
        job = viewModelScope.launch {
            installer.downloadAndInstall(update).collect { step ->
                _state.value = when (step) {
                    is UpdateDownload.Progress -> UpdatePromptState.Downloading(step.percent)
                    UpdateDownload.Launching -> UpdatePromptState.Launching
                    is UpdateDownload.Failed -> UpdatePromptState.Failed(step.message)
                }
            }
        }
    }

    /**
     * Dismisses this version for good.
     *
     * Recorded rather than merely closed: an update the user has declined should not reappear
     * on the next launch, or the prompt becomes something to dismiss reflexively.
     */
    fun skip(update: AppUpdate) {
        viewModelScope.launch { store.skipUpdateVersion(update.versionName) }
        _state.value = UpdatePromptState.Hidden
    }

    /** Closes the prompt without recording anything, so it may appear again tomorrow. */
    fun dismiss() {
        job?.cancel()
        _state.value = UpdatePromptState.Hidden
    }

    companion object {
        fun factory(
            store: WatchBoxStore,
            checker: UpdateChecker,
            installer: UpdateInstaller,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                UpdatePromptViewModel(store, checker, installer) as T
        }
    }
}
