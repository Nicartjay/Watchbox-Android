package space.nicart.watchbox.extension

import android.content.Context
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.nicart.watchbox.extension.loader.ExtensionLoader
import space.nicart.watchbox.extension.model.Extension
import space.nicart.watchbox.extension.model.LoadResult

/**
 * Owns the installed/available extension lists and the sources they expose.
 *
 * Sources are resolved by id, because that is what the rest of the app persists:
 * a library entry or watch-history row references a source id, and the extension
 * that provided it may be uninstalled and reinstalled underneath.
 */
class ExtensionManager(
    private val context: Context,
    private val repoApi: ExtensionRepoApi,
    private val installer: ExtensionInstaller,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reloadMutex = Mutex()

    private val _installed = MutableStateFlow<List<Extension.Installed>>(emptyList())
    val installed: StateFlow<List<Extension.Installed>> = _installed.asStateFlow()

    private val _available = MutableStateFlow<List<Extension.Available>>(emptyList())
    val available: StateFlow<List<Extension.Available>> = _available.asStateFlow()

    private val _untrusted = MutableStateFlow<List<Extension.Untrusted>>(emptyList())
    val untrusted: StateFlow<List<Extension.Untrusted>> = _untrusted.asStateFlow()

    /** Load failures, surfaced so a broken extension is visible rather than silent. */
    private val _failures = MutableStateFlow<List<LoadResult.Error>>(emptyList())
    val failures: StateFlow<List<LoadResult.Error>> = _failures.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** All catalogue sources from every installed extension, keyed by source id. */
    val sources: StateFlow<Map<Long, AnimeSource>>
        get() = _sources.asStateFlow()

    private val _sources = MutableStateFlow<Map<Long, AnimeSource>>(emptyMap())

    fun init() {
        scope.launch { reloadInstalled() }
    }

    // ------------------------------------------------------------ installed

    suspend fun reloadInstalled() = reloadMutex.withLock {
        _isLoading.value = true
        try {
            val results = ExtensionLoader.loadExtensions(context)

            val loaded = results.filterIsInstance<LoadResult.Success>().map { it.extension }
            _installed.value = loaded.sortedBy { it.name.lowercase() }
            _untrusted.value = results.filterIsInstance<LoadResult.Untrusted>()
                .map { it.extension }
            _failures.value = results.filterIsInstance<LoadResult.Error>()

            _sources.value = loaded
                .flatMap { it.sources }
                .associateBy { it.id }

            reconcileUpdates()
        } finally {
            _isLoading.value = false
        }
    }

    fun sourceById(id: Long): AnimeSource? = _sources.value[id]

    fun catalogueSourceById(id: Long): AnimeCatalogueSource? =
        _sources.value[id] as? AnimeCatalogueSource

    /** Every browsable source, in a stable display order. */
    fun catalogueSources(): List<AnimeCatalogueSource> = _installed.value
        .flatMap { it.sources }
        .filterIsInstance<AnimeCatalogueSource>()
        .sortedBy { it.name.lowercase() }

    // ------------------------------------------------------------ available

    suspend fun refreshAvailable(repoUrl: String): Result<Unit> =
        repoApi.fetchIndex(repoUrl).map { entries ->
            _available.value = entries
            reconcileUpdates()
        }

    /**
     * Flags installed extensions that the repo has a newer build of, and those
     * the repo no longer lists at all.
     */
    private fun reconcileUpdates() {
        val availableByPkg = _available.value.associateBy { it.pkgName }
        if (availableByPkg.isEmpty()) return

        _installed.value = _installed.value.map { ext ->
            val remote = availableByPkg[ext.pkgName]
            when {
                remote == null -> ext.copy(hasUpdate = false, isObsolete = true)
                remote.versionCode > ext.versionCode ||
                    remote.libVersion > ext.libVersion ->
                    ext.copy(hasUpdate = true, isObsolete = false)

                else -> ext.copy(hasUpdate = false, isObsolete = false)
            }
        }
    }

    /** Available entries with no installed counterpart. */
    fun installable(): List<Extension.Available> {
        val installedPkgs = _installed.value.map { it.pkgName }.toSet()
        return _available.value.filterNot { it.pkgName in installedPkgs }
    }

    // -------------------------------------------------------------- install

    fun install(extension: Extension.Available) = installer.install(extension)

    suspend fun uninstall(pkgName: String): Boolean {
        val removed = installer.uninstall(pkgName)
        if (removed) reloadInstalled()
        return removed
    }

    val lastInstallError: String? get() = installer.lastError
}
