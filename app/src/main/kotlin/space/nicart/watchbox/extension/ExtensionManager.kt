package space.nicart.watchbox.extension

import android.content.Context
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.nicart.watchbox.extension.loader.ExtensionLoader
import space.nicart.watchbox.data.local.ExtensionRepo
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

    /** True when the last refresh reached every enabled repository. */
    private val _lastRefreshComplete = MutableStateFlow(false)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** All catalogue sources from every installed extension, keyed by source id. */
    val sources: StateFlow<Map<Long, AnimeSource>>
        get() = _sources.asStateFlow()

    private val _sources = MutableStateFlow<Map<Long, AnimeSource>>(emptyMap())

    /**
     * Loads installed extensions, then refreshes the repository indexes.
     *
     * Ordered rather than concurrent: update detection compares installed against
     * available, so refreshing first would reconcile against an empty installed
     * list and find nothing. [repoProvider] is a lambda because the manager is
     * constructed before the settings store is readable.
     *
     * Runs off the main thread and is never awaited, so a slow or unreachable
     * repository delays the update badge but not app startup.
     */
    fun init(repoProvider: (suspend () -> List<ExtensionRepo>)? = null) {
        scope.launch {
            reloadInstalled()

            val repos = repoProvider?.let { provider ->
                runCatching { provider() }.getOrNull()
            } ?: return@launch

            // Failures are already reported per repo inside refreshAvailable; at
            // startup there is no UI to show them in, so the result is dropped and
            // the Extensions screen reports them on its own refresh.
            runCatching { refreshAvailable(repos) }
        }
    }

    /**
     * Installed extensions with a newer build in some enabled repository.
     *
     * Exposed as a flow so a badge can react without the Extensions screen having
     * been opened.
     */
    val updateCount: StateFlow<Int> = _installed
        .map { list -> list.count { it.hasUpdate } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

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

    /**
     * The extension a source belongs to.
     *
     * Needed because a source has no icon of its own - the icon belongs to the
     * extension that created it, and [sources] flattens that association away. Walking
     * the installed list is cheap: it holds a handful of entries, not thousands.
     */
    fun extensionForSource(sourceId: Long): Extension.Installed? =
        _installed.value.firstOrNull { extension ->
            extension.sources.any { it.id == sourceId }
        }

    fun catalogueSourceById(id: Long): AnimeCatalogueSource? =
        _sources.value[id] as? AnimeCatalogueSource

    /** Every browsable source, in a stable display order. */
    fun catalogueSources(): List<AnimeCatalogueSource> = _installed.value
        .flatMap { it.sources }
        .filterIsInstance<AnimeCatalogueSource>()
        .sortedBy { it.name.lowercase() }

    // ------------------------------------------------------------ available

    /**
     * Fetches every enabled repository and merges the results.
     *
     * Repositories are fetched concurrently and failures are reported per repo
     * rather than aborting: with several configured, one unreachable repo must not
     * hide the extensions the others listed.
     *
     * When a package appears in more than one repository the highest [versionCode]
     * wins, matching how the loader resolves duplicate installed APKs.
     */
    suspend fun refreshAvailable(repos: List<ExtensionRepo>): RepoRefreshResult {
        val enabled = repos.filter { it.enabled }

        if (enabled.isEmpty()) {
            // Distinct from a failure: the user has switched everything off, so the
            // empty list is correct and must not be reported as an error.
            _available.value = emptyList()
            _lastRefreshComplete.value = true
            reconcileUpdates()
            return RepoRefreshResult(failures = emptyList(), succeeded = 0)
        }

        val results = coroutineScope {
            enabled.map { repo ->
                async { repo to repoApi.fetchIndex(repo.url) }
            }.awaitAll()
        }

        val failures = results.mapNotNull { (repo, result) ->
            result.exceptionOrNull()?.let { error ->
                RepoFailure(
                    repo = repo,
                    message = error.message ?: "Could not reach this repository.",
                )
            }
        }

        _available.value = mergeRepoEntries(
            results.mapNotNull { (repo, result) ->
                result.getOrNull()?.let { entries -> repo.url to entries }
            },
        )
        // Obsolete detection is only sound with a complete picture; see
        // reconcileUpdates.
        _lastRefreshComplete.value = failures.isEmpty()
        reconcileUpdates()

        return RepoRefreshResult(
            failures = failures,
            succeeded = enabled.size - failures.size,
        )
    }

    /**
     * Flags installed extensions that the repo has a newer build of, and those
     * the repo no longer lists at all.
     */
    private fun reconcileUpdates() {
        val availableByPkg = _available.value.associateBy { it.pkgName }
        if (availableByPkg.isEmpty()) return

        // "Not in any repo" can only be concluded when every enabled repository
        // answered. After a partial refresh, an extension from the repo that failed
        // would otherwise be wrongly marked obsolete.
        val canDetectObsolete = _lastRefreshComplete.value

        _installed.value = _installed.value.map { ext ->
            val remote = availableByPkg[ext.pkgName]
            when {
                remote == null && !canDetectObsolete -> ext
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

/**
 * Merges per-repository index results into one list.
 *
 * When several repositories carry the same package the highest [versionCode] wins,
 * mirroring how the loader resolves duplicate installed APKs. Without that a user
 * with two overlapping repos would see every shared extension twice, and which
 * copy installed would depend on repository order.
 *
 * Each entry is tagged with the repository it came from so the list can be filtered
 * by origin and the winner can be attributed.
 *
 * Extracted from [ExtensionManager.refreshAvailable] so it is testable without
 * network access.
 */
internal fun mergeRepoEntries(
    results: List<Pair<String, List<Extension.Available>>>,
): List<Extension.Available> = results
    .flatMap { (repoUrl, entries) -> entries.map { it.copy(repoUrl = repoUrl) } }
    .groupBy { it.pkgName }
    .map { (_, entries) -> entries.maxBy { it.versionCode } }
    .sortedBy { it.name.lowercase() }

/** One repository that could not be read. */
data class RepoFailure(val repo: ExtensionRepo, val message: String)

/**
 * Outcome of a multi-repository refresh.
 *
 * Carries both halves so the UI can say "3 of 4 repositories loaded" rather than
 * choosing between a total success and a total failure.
 */
data class RepoRefreshResult(
    val failures: List<RepoFailure>,
    val succeeded: Int,
) {
    val allFailed: Boolean get() = succeeded == 0 && failures.isNotEmpty()
    val hasFailures: Boolean get() = failures.isNotEmpty()
}
