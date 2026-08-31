package space.nicart.watchbox.domain

import android.content.Context
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.data.remote.SubtitleApi
import space.nicart.watchbox.data.remote.SubtitleProvider
import space.nicart.watchbox.data.remote.SubtitleQuery
import space.nicart.watchbox.data.remote.SubtitleResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import space.nicart.watchbox.ui.player.SubtitleCue
import space.nicart.watchbox.ui.player.SubtitleParser
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

    /**
     * Searches every enabled provider at once, keeping the answers grouped by their source.
     *
     * Grouped rather than merged. The same file appears in several catalogues under different
     * names, so a merged list fills with near-duplicates that all have to be downloaded to tell
     * apart - where a grouped one lets a viewer who knows a provider suits their releases go
     * straight to it, and shows plainly which catalogues had nothing.
     *
     * Run in parallel because the slowest provider would otherwise set the wait for all of them.
     * A provider that cannot answer at all is skipped rather than queried: the REST one without
     * a key, the legacy one for a title with no IMDb match. Both return empty, and an empty
     * section for a provider that was never able to answer is misleading.
     *
     * Groups are returned in the enum's own order so the sections do not reshuffle between
     * searches according to which service happened to answer first.
     */
    suspend fun searchGrouped(query: SubtitleQuery): List<SubtitleGroup> {
        if (query.isUnusable) return emptyList()

        val settings = store.currentSettings()
        val enabled = SubtitleProvider.entries
            .filter { it in settings.subtitleProviders }
            .filter { it.isUsable(query, settings.subtitleApiKey) }

        if (enabled.isEmpty()) return emptyList()

        return coroutineScope {
            enabled
                .map { provider ->
                    provider to async {
                        api.search(
                            query = query,
                            provider = provider,
                            apiKey = settings.subtitleApiKey,
                        )
                    }
                }
                .map { (provider, task) -> SubtitleGroup(provider, task.await()) }
                // A provider that found nothing is dropped rather than shown empty: four empty
                // headings say less than one line reporting that nothing was found anywhere,
                // which is what the panel shows when every group is gone.
                .filter { it.results.isNotEmpty() }
        }
    }

    /**
     * Flattened results, for callers that only need a subtitle rather than a choice of them.
     *
     * Used by the download prompt, where the grouping would be noise: that flow asks for one
     * file to save beside the video, and the provider it came from does not change what it is.
     */
    suspend fun search(query: SubtitleQuery): List<SubtitleResult> =
        searchGrouped(query).flatMap { it.results }

    /**
     * Whether [provider] could return anything for this query at all.
     *
     * Checked up front so a provider that is structurally unable to answer - no key, or no id of
     * the kind it indexes by - does not absorb a fallback step and mask a provider that could
     * have succeeded.
     */
    private fun SubtitleProvider.isUsable(query: SubtitleQuery, apiKey: String): Boolean =
        when (this) {
            // Positional path keyed by IMDb id; a TMDB id is of no use to it.
            SubtitleProvider.OPEN_SUBTITLES_LEGACY -> !query.imdbId.isNullOrBlank()
            SubtitleProvider.OPEN_SUBTITLES_API -> apiKey.isNotBlank()
            // Both take either id, so they are usable whenever the query is.
            SubtitleProvider.SUBS_BRIGHT -> true
            SubtitleProvider.VIDFAST_WYZIE -> true
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
     * Downloads [result] into permanent storage, for an episode being kept offline.
     *
     * The one case where a subtitle must outlive the cache. [download] puts files in
     * `cacheDir` on the reasoning that a subtitle is disposable because it can be fetched
     * again - which is exactly untrue for an offline copy, whose whole purpose is to work with
     * no network. It would also be deleted by [clearCache] at the end of the next episode.
     *
     * Returns null on failure, so the caller can record a download without a subtitle rather
     * than one that silently plays none.
     */
    suspend fun downloadForOffline(result: SubtitleResult, targetDir: File): SubtitleOption? {
        val key = store.currentSettings().subtitleApiKey
        val file = api.download(result, targetDir.apply { mkdirs() }, key) ?: return null

        return SubtitleOption(
            label = result.displayLabel(),
            url = file.toURI().toString(),
            language = result.language,
            isExternal = true,
        )
    }

    /**
     * Fetches a subtitle's text from a remote URL.
     *
     * Exposed for downloads, which have to put a source-supplied track on disk rather than
     * hand its URL to the player: those URLs are signed like the stream's own and expire within
     * minutes, so an offline copy holding one would show no subtitles.
     *
     * Returns empty for anything unreadable, which the caller treats as "skip this track"
     * rather than as a failure worth reporting.
     */
    suspend fun rawText(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        runCatching { api.fetchText(url, headers) }.getOrDefault("")
    }

    /**
     * The best match for [language] among [results], or null when none is close enough.
     *
     * Exact language first, then the base language of a regional tag - `pt-BR` satisfies a
     * request for `pt`, since a subtitle in the wrong dialect is far better than none. The
     * ranking within a language is the API's own, which is by download count, so this takes
     * the most-used file rather than guessing at release names.
     */
    fun bestMatch(results: List<SubtitleResult>, language: String): SubtitleResult? {
        if (results.isEmpty() || language.isBlank()) return null

        val exact = results.firstOrNull { it.language.equals(language, ignoreCase = true) }
        if (exact != null) return exact

        val base = language.substringBefore('-')
        return results.firstOrNull { it.language.substringBefore('-').equals(base, true) }
    }

    /**
     * Fetches and parses a subtitle into cues, for timing adjustment.
     *
     * Needed only when a timing offset is applied. ExoPlayer reports cues through
     * `onCues` as they become current, which can delay a line but never surface one
     * early, so shifting in both directions requires owning the cue list.
     *
     * Returns an empty list for anything unreadable - an unsupported format, a dead link,
     * a subtitle behind an authenticated redirect - and the caller then keeps the player's
     * own rendering, which is correct at zero offset anyway.
     *
     * A `file://` URL is read directly; a downloaded subtitle is already on disk and going
     * back to the network for it would be pointless and could fail.
     */
    suspend fun cues(url: String): List<SubtitleCue> = withContext(Dispatchers.IO) {
        runCatching {
            val text = if (url.startsWith("file://")) {
                File(java.net.URI(url)).readText()
            } else {
                api.fetchText(url)
            }
            val cues = SubtitleParser.parse(text)

            // Logged with both sizes, because "nothing came back" and "it came back and
            // parsed to nothing" are different faults and look identical from the UI: the
            // offset silently does nothing either way. An empty result here is the whole
            // reason a timing correction can appear to be ignored.
            android.util.Log.i(
                TAG,
                "cues($url): ${text.length} chars -> ${cues.size} cues",
            )
            cues
        }.onFailure {
            android.util.Log.w(TAG, "cue parse failed for $url: ${it::class.java.simpleName}: ${it.message}")
        }.getOrDefault(emptyList())
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
        const val TAG = "WbSubtitles"
    }
}

/**
 * One provider's answer to a search, kept apart from the others.
 *
 * Grouped rather than merged because the catalogues overlap: the same file is listed in several
 * of them under different release names, and a flat list of near-duplicates has to be downloaded
 * one by one to tell apart. Keeping the source visible also lets a viewer who has learnt that one
 * catalogue suits their releases go straight to it.
 */
data class SubtitleGroup(
    val provider: SubtitleProvider,
    val results: List<SubtitleResult>,
)
