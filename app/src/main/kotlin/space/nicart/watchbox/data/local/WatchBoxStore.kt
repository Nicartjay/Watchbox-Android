package space.nicart.watchbox.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import space.nicart.watchbox.core.ui.AppTheme
import space.nicart.watchbox.data.remote.SubtitleProvider
import space.nicart.watchbox.ui.player.SubtitleBackground
import space.nicart.watchbox.ui.player.SubtitleEdgeWidth
import space.nicart.watchbox.ui.player.SubtitleSize

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "watchbox")

/**
 * Single persistence facade over DataStore.
 *
 * Everything the app stores lives here behind one key namespace, so there is
 * exactly one definition of each key. Lists are stored as JSON strings — the
 * volumes involved (tens of entries) don't justify Room.
 */
class WatchBoxStore(context: Context) {

    private val store = context.dataStore
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ------------------------------------------------------------- settings

    val settings: Flow<AppSettings> = store.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { prefs ->
            AppSettings(
                repos = readRepos(prefs),
                theme = AppTheme.fromName(prefs[Keys.THEME]),
                autoPlayNext = prefs[Keys.AUTO_NEXT] ?: true,
                preferredQuality = prefs[Keys.QUALITY] ?: "1080",
                nsfwSourcesEnabled = prefs[Keys.NSFW] ?: false,
                autoCheckUpdates = prefs[Keys.AUTO_UPDATE_CHECK] ?: true,
                lastUpdateCheck = prefs[Keys.LAST_UPDATE_CHECK] ?: 0L,
                skippedUpdateVersion = prefs[Keys.SKIPPED_UPDATE],
                subtitleSize = enumOrDefault(prefs[Keys.SUB_SIZE], SubtitleSize.MEDIUM),
                subtitleBackground = enumOrDefault(
                    prefs[Keys.SUB_BACKGROUND],
                    SubtitleBackground.OUTLINE,
                ),
                subtitleTextColor = prefs[Keys.SUB_COLOR] ?: SUBTITLE_DEFAULT_COLOR,
                subtitleBackgroundOpacity = prefs[Keys.SUB_BG_OPACITY] ?: 0.6f,
                subtitleBold = prefs[Keys.SUB_BOLD] ?: false,
                uiScale = prefs[Keys.UI_SCALE] ?: 1f,
                posterScale = prefs[Keys.POSTER_SCALE] ?: 1f,
                subtitleEdgeWidth = enumOrDefault(
                    prefs[Keys.SUB_EDGE_WIDTH],
                    SubtitleEdgeWidth.MEDIUM,
                ),
                subtitleLanguage = prefs[Keys.SUB_LANG] ?: "en",
                subtitleProvider = enumOrDefault(
                    prefs[Keys.SUB_PROVIDER],
                    SubtitleProvider.OPEN_SUBTITLES_LEGACY,
                ),
                subtitleApiKey = prefs[Keys.SUB_API_KEY].orEmpty(),
                castForceProxy = prefs[Keys.CAST_FORCE_PROXY] ?: false,
                lastServerId = prefs[Keys.LAST_SERVER],
                tvSourceId = prefs[Keys.TV_SOURCE],
            )
        }

    suspend fun currentSettings(): AppSettings = settings.first()

    // -------------------------------------------------------- repositories

    /**
     * Resolves the stored repository list.
     *
     * Migrates the legacy single-URL key on read rather than with a one-shot
     * write: a read-side migration cannot half-apply, and a user who had a custom
     * repository configured keeps it instead of silently reverting to the default.
     */
    private fun readRepos(prefs: Preferences): List<ExtensionRepo> {
        // Key presence, not emptiness: an empty list is a legitimate state now that
        // no repository ships by default, and treating it as "unset" would resurrect
        // the migrated legacy repository every time the user removed the last one.
        prefs[Keys.REPOS]?.let { return decodeList<ExtensionRepo>(it) }

        val legacy = prefs[Keys.REPO_URL]?.takeIf { it.isNotBlank() }
        return when {
            legacy != null -> listOf(ExtensionRepo(url = ExtensionRepo.normaliseUrl(legacy)))
            else -> ExtensionRepo.DEFAULT
        }
    }

    /**
     * Adds a repository, or returns false when it is already present.
     *
     * De-duplicated on the normalised URL so the same repo pasted as a root and as
     * an index link cannot produce two entries fetching the same index.
     */
    suspend fun addRepo(url: String): Boolean {
        val normalised = ExtensionRepo.normaliseUrl(url)
        if (normalised.isBlank()) return false

        var added = false
        store.edit { prefs ->
            val current = readRepos(prefs)
            if (current.any { it.url.equals(normalised, ignoreCase = true) }) return@edit

            prefs[Keys.REPOS] = json.encodeToString(current + ExtensionRepo(normalised))
            added = true
        }
        return added
    }

    suspend fun removeRepo(url: String) = store.edit { prefs ->
        val next = readRepos(prefs).filterNot { it.url.equals(url, ignoreCase = true) }
        prefs[Keys.REPOS] = json.encodeToString(next)
    }

    suspend fun setRepoEnabled(url: String, enabled: Boolean) = store.edit { prefs ->
        val next = readRepos(prefs).map { repo ->
            if (repo.url.equals(url, ignoreCase = true)) repo.copy(enabled = enabled) else repo
        }
        prefs[Keys.REPOS] = json.encodeToString(next)
    }

    /** Removes every repository. */
    suspend fun resetRepos() = store.edit { prefs ->
        // The legacy key is cleared too, or the read-side migration would resurrect
        // the old custom URL on the next read.
        prefs.remove(Keys.REPO_URL)
        prefs[Keys.REPOS] = json.encodeToString(ExtensionRepo.DEFAULT)
    }

    suspend fun setTheme(theme: AppTheme) = store.edit { it[Keys.THEME] = theme.name }
    suspend fun setAutoPlayNext(enabled: Boolean) = store.edit { it[Keys.AUTO_NEXT] = enabled }
    suspend fun setPreferredQuality(quality: String) = store.edit { it[Keys.QUALITY] = quality }
    suspend fun setNsfwSourcesEnabled(enabled: Boolean) = store.edit { it[Keys.NSFW] = enabled }

    suspend fun setAutoCheckUpdates(enabled: Boolean) = store.edit {
        it[Keys.AUTO_UPDATE_CHECK] = enabled
    }

    /** Throttles the automatic check; see [AppSettings.shouldAutoCheck]. */
    suspend fun markUpdateChecked() = store.edit {
        it[Keys.LAST_UPDATE_CHECK] = System.currentTimeMillis()
    }

    /** Suppresses the prompt for one specific version the user dismissed. */
    suspend fun skipUpdateVersion(version: String) = store.edit {
        it[Keys.SKIPPED_UPDATE] = version
    }
    // ------------------------------------------------------------ subtitles

    suspend fun setSubtitleSize(size: SubtitleSize) = store.edit {
        it[Keys.SUB_SIZE] = size.name
    }

    suspend fun setSubtitleBackground(background: SubtitleBackground) = store.edit {
        it[Keys.SUB_BACKGROUND] = background.name
    }

    suspend fun setSubtitleTextColor(color: Int) = store.edit { it[Keys.SUB_COLOR] = color }

    suspend fun setSubtitleBackgroundOpacity(opacity: Float) = store.edit {
        it[Keys.SUB_BG_OPACITY] = opacity.coerceIn(0f, 1f)
    }

    suspend fun setSubtitleBold(bold: Boolean) = store.edit { it[Keys.SUB_BOLD] = bold }

    /**
     * Overall UI scale.
     *
     * Clamped rather than trusted: a value near zero renders the app unreadable and
     * unrecoverable, since the setting itself would be too small to find.
     */
    suspend fun setUiScale(scale: Float) = store.edit {
        it[Keys.UI_SCALE] = scale.coerceIn(UI_SCALE_MIN, UI_SCALE_MAX)
    }

    suspend fun setPosterScale(scale: Float) = store.edit {
        it[Keys.POSTER_SCALE] = scale.coerceIn(POSTER_SCALE_MIN, POSTER_SCALE_MAX)
    }

    suspend fun setSubtitleEdgeWidth(width: SubtitleEdgeWidth) = store.edit {
        it[Keys.SUB_EDGE_WIDTH] = width.name
    }
    suspend fun setSubtitleLanguage(lang: String) = store.edit { it[Keys.SUB_LANG] = lang }
    suspend fun setSubtitleProvider(provider: SubtitleProvider) = store.edit {
        it[Keys.SUB_PROVIDER] = provider.name
    }
    suspend fun setSubtitleApiKey(key: String) = store.edit { it[Keys.SUB_API_KEY] = key.trim() }
    suspend fun setCastForceProxy(enabled: Boolean) = store.edit {
        it[Keys.CAST_FORCE_PROXY] = enabled
    }
    suspend fun setLastServerId(id: String?) = store.edit { prefs ->
        if (id == null) prefs.remove(Keys.LAST_SERVER) else prefs[Keys.LAST_SERVER] = id
    }

    /** Records the source picked in the TV rail, shared by the home feed and search. */
    suspend fun setTvSourceId(id: Long?) = store.edit { prefs ->
        if (id == null) prefs.remove(Keys.TV_SOURCE) else prefs[Keys.TV_SOURCE] = id
    }

    // -------------------------------------------------------------- history

    val history: Flow<List<WatchHistoryEntry>> = store.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { prefs -> decodeList(prefs[Keys.HISTORY]) }

    /**
     * Upsert by [WatchHistoryEntry.key] and keep the list newest-first.
     *
     * Guards against the clobber the web app has to work around: a stale
     * low-progress write must not overwrite a newer high-progress one for the
     * same episode.
     */
    suspend fun saveHistory(entry: WatchHistoryEntry) = store.edit { prefs ->
        val current = decodeList<WatchHistoryEntry>(prefs[Keys.HISTORY])
        val existing = current.firstOrNull { it.key == entry.key }

        // A slow write for an earlier episode must not clobber a newer one.
        val isStaleWrite = existing != null &&
            existing.episodeUrl == entry.episodeUrl &&
            existing.updatedAt > entry.updatedAt

        if (isStaleWrite) return@edit

        val merged = buildList {
            add(entry)
            addAll(current.filter { it.key != entry.key })
        }.take(WatchHistoryEntry.MAX_ENTRIES)

        prefs[Keys.HISTORY] = json.encodeToString(merged)
    }

    suspend fun removeHistory(key: String) = store.edit { prefs ->
        val current = decodeList<WatchHistoryEntry>(prefs[Keys.HISTORY])
        prefs[Keys.HISTORY] = json.encodeToString(current.filter { it.key != key })
    }

    suspend fun clearHistory() = store.edit { it.remove(Keys.HISTORY) }

    suspend fun historyFor(sourceId: Long, animeUrl: String): WatchHistoryEntry? =
        history.first().firstOrNull { it.sourceId == sourceId && it.animeUrl == animeUrl }

    // ------------------------------------------------------------ watchlist

    val watchlist: Flow<List<WatchlistEntry>> = store.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { prefs -> decodeList(prefs[Keys.WATCHLIST]) }

    suspend fun toggleWatchlist(entry: WatchlistEntry): Boolean {
        var added = false
        store.edit { prefs ->
            val current = decodeList<WatchlistEntry>(prefs[Keys.WATCHLIST])
            val exists = current.any { it.key == entry.key }
            val next = if (exists) {
                current.filter { it.key != entry.key }
            } else {
                added = true
                (listOf(entry) + current).take(WatchlistEntry.MAX_ENTRIES)
            }
            prefs[Keys.WATCHLIST] = json.encodeToString(next)
        }
        return added
    }

    suspend fun clearWatchlist() = store.edit { it.remove(Keys.WATCHLIST) }

    // --------------------------------------------------------- search terms

    val recentSearches: Flow<List<String>> = store.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { prefs -> decodeList<String>(prefs[Keys.RECENT_SEARCHES]) }

    suspend fun addRecentSearch(term: String) {
        val clean = term.trim()
        if (clean.isEmpty()) return
        store.edit { prefs ->
            val current = decodeList<String>(prefs[Keys.RECENT_SEARCHES])
            val next = (listOf(clean) + current.filterNot { it.equals(clean, true) }).take(12)
            prefs[Keys.RECENT_SEARCHES] = json.encodeToString(next)
        }
    }

    suspend fun clearRecentSearches() = store.edit { it.remove(Keys.RECENT_SEARCHES) }

    // -------------------------------------------------------------- helpers

    /**
     * Resolves a stored enum name, falling back when it no longer exists.
     *
     * Enum constants are persisted by name, so a renamed or removed constant would
     * otherwise throw on read and take the whole settings flow down with it.
     */
    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T =
        raw?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    private inline fun <reified T> decodeList(raw: String?): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<T>>(raw) }.getOrDefault(emptyList())
    }

    private object Keys {
        /**
         * Removed: "amoled". The black background is now unconditional, so the stored flag is
         * no longer read. Any value left on disk from an older build is simply ignored.
         */

        /** Legacy single repository. Read for migration only; never written. */
        val REPO_URL = stringPreferencesKey("extension_repo_url")
        val REPOS = stringPreferencesKey("extension_repos")
        val THEME = stringPreferencesKey("theme")
        val AUTO_NEXT = booleanPreferencesKey("auto_play_next")
        val QUALITY = stringPreferencesKey("preferred_quality")
        val NSFW = booleanPreferencesKey("nsfw_sources")
        val AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_check_updates")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
        val SKIPPED_UPDATE = stringPreferencesKey("skipped_update_version")
        val SUB_SIZE = stringPreferencesKey("subtitle_size")
        val SUB_BACKGROUND = stringPreferencesKey("subtitle_background")
        val SUB_COLOR = intPreferencesKey("subtitle_text_color")
        val SUB_BG_OPACITY = floatPreferencesKey("subtitle_bg_opacity")
        val SUB_BOLD = booleanPreferencesKey("subtitle_bold")
        val SUB_EDGE_WIDTH = stringPreferencesKey("subtitle_edge_width")
        val UI_SCALE = floatPreferencesKey("ui_scale")
        val POSTER_SCALE = floatPreferencesKey("poster_scale")
        val SUB_LANG = stringPreferencesKey("subtitle_language")
        val SUB_PROVIDER = stringPreferencesKey("subtitle_provider")
        val SUB_API_KEY = stringPreferencesKey("subtitle_api_key")
        val CAST_FORCE_PROXY = booleanPreferencesKey("cast_force_proxy")
        val LAST_SERVER = stringPreferencesKey("last_server_id")
        val TV_SOURCE = longPreferencesKey("tv_selected_source")
        val HISTORY = stringPreferencesKey("watch_history")
        val WATCHLIST = stringPreferencesKey("watchlist")
        val RECENT_SEARCHES = stringPreferencesKey("recent_searches")
    }
}

data class AppSettings(
    val repos: List<ExtensionRepo> = ExtensionRepo.DEFAULT,
    val theme: AppTheme = AppTheme.Default,
    val autoPlayNext: Boolean = true,
    val preferredQuality: String = "1080",
    val nsfwSourcesEnabled: Boolean = false,
    val autoCheckUpdates: Boolean = true,
    val lastUpdateCheck: Long = 0L,
    /** Version the user chose to skip, so it is not offered again. */
    val skippedUpdateVersion: String? = null,
    val subtitleSize: SubtitleSize = SubtitleSize.MEDIUM,
    /** Outline by default: it is the style that survives the widest range of video. */
    val subtitleBackground: SubtitleBackground = SubtitleBackground.OUTLINE,
    val subtitleTextColor: Int = SUBTITLE_DEFAULT_COLOR,
    val subtitleBackgroundOpacity: Float = 0.6f,
    val subtitleBold: Boolean = false,
    val subtitleEdgeWidth: SubtitleEdgeWidth = SubtitleEdgeWidth.MEDIUM,
    /** Multiplier applied to text and spacing. */
    val uiScale: Float = 1f,
    /** Multiplier applied to poster and card sizes only. */
    val posterScale: Float = 1f,
    val subtitleLanguage: String = "en",
    /** Which online catalogue the subtitle search uses. */
    val subtitleProvider: SubtitleProvider = SubtitleProvider.OPEN_SUBTITLES_LEGACY,
    /** Key for the OpenSubtitles REST API. Empty means that provider is unavailable. */
    val subtitleApiKey: String = "",
    /**
     * Relay cast streams through this device even when they need no headers.
     *
     * Persisted because it is a property of the user's setup - a receiver that cannot fetch
     * these links will not start being able to - so having to rediscover the switch on every
     * cast would be tedious.
     */
    val castForceProxy: Boolean = false,
    val lastServerId: String? = null,
    /**
     * The source chosen in the TV navigation rail.
     *
     * Persisted so the choice survives a restart: on a television this is the closest
     * thing the app has to a "channel", and re-picking it on every launch with a remote
     * is far more tedious than on a phone.
     */
    val tvSourceId: Long? = null,
) {

    /** Repositories to actually fetch from. */
    val enabledRepos: List<ExtensionRepo>
        get() = repos.filter { it.enabled }

    /**
     * True when an automatic check is due.
     *
     * Throttled to once a day: GitHub's unauthenticated API allows 60 requests
     * per hour per IP, and checking on every launch would be wasteful without
     * making updates arrive meaningfully sooner.
     */
    val shouldAutoCheck: Boolean
        get() = autoCheckUpdates &&
            System.currentTimeMillis() - lastUpdateCheck > CHECK_INTERVAL_MS

    private companion object {
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}

/** Default subtitle colour: opaque white. */
internal const val SUBTITLE_DEFAULT_COLOR: Int = 0xFFFFFFFF.toInt()

/**
 * Bounds for the UI scale.
 *
 * The floor is 0.8 rather than something smaller because the setting has to remain
 * legible enough to undo. The ceiling is 1.4: beyond that a phone layout starts
 * clipping rather than reflowing, since the design uses fixed poster metrics.
 */
internal const val UI_SCALE_MIN = 0.8f
internal const val UI_SCALE_MAX = 1.4f

/** Posters tolerate a wider range: they scale independently of any text inside them. */
internal const val POSTER_SCALE_MIN = 0.7f
internal const val POSTER_SCALE_MAX = 1.6f
