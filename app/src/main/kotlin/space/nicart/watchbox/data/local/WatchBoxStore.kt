package space.nicart.watchbox.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import space.nicart.watchbox.core.ui.AppTheme
import space.nicart.watchbox.extension.ExtensionRepoApi

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
                repoUrl = prefs[Keys.REPO_URL]?.takeIf { it.isNotBlank() }
                    ?: ExtensionRepoApi.DEFAULT_REPO,
                theme = AppTheme.fromName(prefs[Keys.THEME]),
                amoled = prefs[Keys.AMOLED] ?: false,
                autoPlayNext = prefs[Keys.AUTO_NEXT] ?: true,
                preferredQuality = prefs[Keys.QUALITY] ?: "1080",
                nsfwSourcesEnabled = prefs[Keys.NSFW] ?: false,
                autoCheckUpdates = prefs[Keys.AUTO_UPDATE_CHECK] ?: true,
                lastUpdateCheck = prefs[Keys.LAST_UPDATE_CHECK] ?: 0L,
                skippedUpdateVersion = prefs[Keys.SKIPPED_UPDATE],
                subtitleScale = prefs[Keys.SUB_SCALE] ?: 1f,
                subtitleLanguage = prefs[Keys.SUB_LANG] ?: "en",
                lastServerId = prefs[Keys.LAST_SERVER],
            )
        }

    suspend fun currentSettings(): AppSettings = settings.first()

    suspend fun setRepoUrl(url: String) = store.edit { prefs ->
        val normalised = url.trim().trimEnd('/')
        if (normalised.isBlank()) prefs.remove(Keys.REPO_URL)
        else prefs[Keys.REPO_URL] = normalised
    }

    suspend fun setTheme(theme: AppTheme) = store.edit { it[Keys.THEME] = theme.name }
    suspend fun setAmoled(enabled: Boolean) = store.edit { it[Keys.AMOLED] = enabled }
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
    suspend fun setSubtitleScale(scale: Float) = store.edit { it[Keys.SUB_SCALE] = scale }
    suspend fun setSubtitleLanguage(lang: String) = store.edit { it[Keys.SUB_LANG] = lang }
    suspend fun setLastServerId(id: String?) = store.edit { prefs ->
        if (id == null) prefs.remove(Keys.LAST_SERVER) else prefs[Keys.LAST_SERVER] = id
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

    private inline fun <reified T> decodeList(raw: String?): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<T>>(raw) }.getOrDefault(emptyList())
    }

    private object Keys {
        val REPO_URL = stringPreferencesKey("extension_repo_url")
        val THEME = stringPreferencesKey("theme")
        val AMOLED = booleanPreferencesKey("amoled")
        val AUTO_NEXT = booleanPreferencesKey("auto_play_next")
        val QUALITY = stringPreferencesKey("preferred_quality")
        val NSFW = booleanPreferencesKey("nsfw_sources")
        val AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_check_updates")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
        val SKIPPED_UPDATE = stringPreferencesKey("skipped_update_version")
        val SUB_SCALE = floatPreferencesKey("subtitle_scale")
        val SUB_LANG = stringPreferencesKey("subtitle_language")
        val LAST_SERVER = stringPreferencesKey("last_server_id")
        val HISTORY = stringPreferencesKey("watch_history")
        val WATCHLIST = stringPreferencesKey("watchlist")
        val RECENT_SEARCHES = stringPreferencesKey("recent_searches")
    }
}

data class AppSettings(
    val repoUrl: String = ExtensionRepoApi.DEFAULT_REPO,
    val theme: AppTheme = AppTheme.Default,
    val amoled: Boolean = false,
    val autoPlayNext: Boolean = true,
    val preferredQuality: String = "1080",
    val nsfwSourcesEnabled: Boolean = false,
    val autoCheckUpdates: Boolean = true,
    val lastUpdateCheck: Long = 0L,
    /** Version the user chose to skip, so it is not offered again. */
    val skippedUpdateVersion: String? = null,
    val subtitleScale: Float = 1f,
    val subtitleLanguage: String = "en",
    val lastServerId: String? = null,
) {

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
