package space.nicart.watchbox.domain

import android.content.Context
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.data.remote.SubtitleApi
import space.nicart.watchbox.data.remote.SubtitleQuery
import space.nicart.watchbox.data.remote.SubtitleResult
import java.io.File

/**
 * Searches for and caches online subtitles.
 *
 * Sits between the player and [SubtitleApi] so the player never has to know which provider is
 * configured, where files are cached, or that a downloaded file has to become a
 * [SubtitleOption] before it can be played.
 *
 * Downloads land in the cache directory rather than files: a subtitle is disposable and can
 * always be fetched again, so it should not survive a low-storage clean-up at the expense of
 * something that cannot be re-derived.
 */
class SubtitleRepository(
    private val context: Context,
    private val api: SubtitleApi,
    private val store: WatchBoxStore,
) {

    /** Searches using whichever provider and language the user has configured. */
    suspend fun search(query: SubtitleQuery): List<SubtitleResult> {
        if (query.isUnusable) return emptyList()

        val settings = store.currentSettings()
        return api.search(
            query = query,
            provider = settings.subtitleProvider,
            apiKey = settings.subtitleApiKey,
        )
    }

    /**
     * Downloads [result] and returns it as a playable track.
     *
     * Returns null when the download failed, so the caller can say so rather than adding an
     * entry that silently plays nothing.
     *
     * The file URI is handed over rather than the remote URL: the player's data source
     * resolves `file://` directly, and going through the network again for something already
     * on disk would fail for exactly the sources most likely to need this - the ones behind an
     * unreliable connection.
     */
    suspend fun download(result: SubtitleResult): SubtitleOption? {
        val key = store.currentSettings().subtitleApiKey
        val file = api.download(result, cacheDir(), key) ?: return null

        return SubtitleOption(
            label = result.displayLabel(),
            url = file.toURI().toString(),
            language = result.language,
            isExternal = true,
        )
    }

    /**
     * Clears cached subtitle files.
     *
     * Called when playback of a title ends rather than on a timer: the files are only useful
     * for the episode they were fetched for, and an unbounded cache of text files accumulates
     * silently because none of them is large enough to notice.
     */
    fun clearCache() {
        runCatching { cacheDir().listFiles()?.forEach { it.delete() } }
    }

    private fun cacheDir(): File = File(context.cacheDir, DIR).apply { mkdirs() }

    /**
     * A label short enough to read in a side panel.
     *
     * Release names are long - `Show.S01E01.1080p.WEB-DL.DDP5.1.H.264-GROUP.srt` - and a panel
     * row is a few dozen characters wide. The tail is kept rather than the head: the leading
     * portion is the title the user already knows, while the release group and quality at the
     * end are what distinguishes two entries from each other.
     */
    private fun SubtitleResult.displayLabel(): String {
        val base = name.substringBeforeLast('.').replace('.', ' ').trim()
        val trimmed = if (base.length > LABEL_MAX) "…" + base.takeLast(LABEL_MAX) else base
        val suffix = if (hearingImpaired) " (HI)" else ""
        return (trimmed.ifBlank { languageName.ifBlank { language } }) + suffix
    }

    private companion object {
        const val DIR = "subtitles"
        const val LABEL_MAX = 42
    }
}
