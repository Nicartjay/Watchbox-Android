package space.nicart.watchbox.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.nicart.watchbox.core.ui.AppTheme
import space.nicart.watchbox.data.local.AppSettings
import space.nicart.watchbox.data.local.WatchBoxStore

class SettingsViewModel(private val store: WatchBoxStore) : ViewModel() {

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

    fun setWorkerBaseUrl(url: String) {
        viewModelScope.launch { store.setWorkerBaseUrl(url) }
    }

    fun clearHistory() {
        viewModelScope.launch { store.clearHistory() }
    }

    fun clearWatchlist() {
        viewModelScope.launch { store.clearWatchlist() }
    }

    companion object {
        fun factory(store: WatchBoxStore): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(store) as T
            }
    }
}
