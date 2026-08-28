package space.nicart.watchbox.download

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.nicart.watchbox.data.local.DownloadEntry
import space.nicart.watchbox.data.local.DownloadState
import space.nicart.watchbox.data.local.DownloadedSubtitle
import space.nicart.watchbox.data.local.OfflineDetail
import space.nicart.watchbox.data.local.WatchBoxStore
import space.nicart.watchbox.domain.AnimeDetail
import space.nicart.watchbox.domain.AnimeRepository
import space.nicart.watchbox.domain.EpisodeEntry
import space.nicart.watchbox.domain.StreamOption
import space.nicart.watchbox.domain.SubtitleRepository
import java.io.File
import space.nicart.watchbox.domain.SubtitleOption
import space.nicart.watchbox.data.remote.SubtitleResult

/**
 * The app's handle on downloading.
 *
 * Sits between the UI and two stores that both hold part of the truth: Media3's own index
 * knows the bytes, and the registry knows what the download *is* - which title, which
 * episode, which quality was asked for. Neither can be derived from the other, so this keeps
 * them in step and hands the UI one merged view.
 *
 * Live byte progress is exposed from memory rather than persisted. It changes several times a
 * second, and every registry write rewrites the whole preferences blob and re-emits to every
 * collector in the app; persisting progress would make downloading the most expensive thing
 * in the process.
 */
@UnstableApi
class DownloadController(
    private val context: Context,
    private val engine: DownloadEngine,
    private val ffmpeg: FfmpegDownloader,
    private val store: WatchBoxStore,
    private val storage: DownloadStorage,
    private val resolver: DownloadStreamResolver,
    private val repository: AnimeRepository,
    /**
     * Online subtitles, for downloads that should work with no network at all.
     *
     * Nullable so the engine can be built without it - a download with no subtitle is a
     * working download, and the search is the one part of this that depends on a third-party
     * service being reachable.
     */
    private val subtitleRepository: SubtitleRepository? = null,
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())

    /** Live progress by [DownloadEntry.key]. Empty for anything not currently running. */
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    /**
     * Starts listening to the engine and brings the registry back in line with disk.
     *
     * Called once the first time downloads are used rather than at startup: touching the
     * manager opens a database and scans the cache index, and most sessions never download
     * anything.
     */
    fun start() {
        if (started) return
        started = true

        scope.launch {
            val settings = store.currentSettings()
            engine.useVolume(settings.downloadVolume)
            engine.useConcurrency(settings.downloadConcurrency)

            val manager = engine.manager()
            manager.addListener(listener)
            applyRequirements(settings.downloadWifiOnly)
            reconcile()
            pollProgress(manager)
        }
    }

    /**
     * Polls the running downloads for their byte counts.
     *
     * Necessary because `onDownloadChanged` fires on a *state* transition - queued to
     * downloading, downloading to complete - and not as bytes arrive. Listening alone
     * therefore reports 0% and then 100%, which is why the notification appeared to work
     * while the app's own UI did not: `DownloadNotificationHelper` polls
     * `getCurrentDownloads()` on its own timer, and nothing here did.
     *
     * Runs only while something is actually downloading, and drops to a sleep otherwise, so an
     * idle app is not waking up twice a second to ask about nothing.
     */
    private suspend fun pollProgress(manager: DownloadManager) {
        while (true) {
            val active = manager.currentDownloads

            // Merged into whatever is already there, never assigned over it.
            //
            // Two things write this map: this poller for Media3's queue, and the ffmpeg
            // downloader for its own sessions. Replacing the map wholesale removed every ffmpeg
            // entry twice a second, and its callback put them straight back - so a running
            // remux flickered between its real size and nothing at all.
            val media3 = active.associate { download ->
                download.request.id to DownloadProgress(
                    percent = download.percentDownloaded.takeIf { it >= 0f } ?: 0f,
                    bytesDownloaded = download.bytesDownloaded,
                    // Total is only knowable for a progressive file, where the server sends a
                    // length. A segmented download has no total until it finishes, so this is
                    // zero there and the UI shows a percentage instead of "x of y".
                    totalBytes = download.contentLength.takeIf { it > 0 } ?: 0L,
                )
            }

            // Only this poller's own keys are dropped. An entry belonging to ffmpeg is left
            // alone: the poller cannot see those downloads and so cannot know they have ended.
            _progress.value = _progress.value
                .filterKeys { it !in media3Keys || it in media3 }
                .plus(media3)

            media3Keys = media3.keys

            delay(if (media3.isEmpty()) IDLE_POLL_MS else ACTIVE_POLL_MS)
        }
    }

    private var started = false

    /**
     * Keys the poller reported last time round.
     *
     * Kept so a finished Media3 download can be cleared without touching an ffmpeg entry, which
     * this poller has no visibility of.
     */
    private var media3Keys: Set<String> = emptySet()

    /**
     * Queues [episode] for download at the quality [stream] names.
     *
     * The URL on [stream] is used for this attempt only. What is persisted is the label, so a
     * resume hours later can re-resolve and match it - a signed URL is dead within minutes.
     */
    fun enqueue(
        sourceId: Long,
        animeUrl: String,
        title: String,
        posterUrl: String?,
        sourceName: String,
        episode: EpisodeEntry,
        stream: StreamOption,
        /**
         * A subtitle the viewer chose at download time, for a stream carrying none of its own.
         *
         * Null when the stream supplies its own tracks - those are saved instead - or when the
         * offer was declined.
         */
        subtitle: SubtitleResult? = null,
        /**
         * The title's page, cached so it can be opened with the network off.
         *
         * Optional because a download is still a download without it: the caller may not have
         * a detail to hand, and failing the download over a page would be the wrong trade.
         */
        detail: AnimeDetail? = null,
    ) {
        // Two engines, split by format rather than by preference.
        //
        // FFmpeg takes anything segmented - HLS, DASH - and anything served through a proxy
        // inside the extension. Media3 keeps plain progressive files.
        //
        // That split is from evidence, not taste. Media3's segment downloader fetches each
        // segment, key and variant playlist as a separate request through its own data source,
        // and these CDNs answered 403 to every one of them however the headers were applied -
        // while the same episodes played perfectly and the rest of this ecosystem downloads them
        // without trouble using FFmpeg. FFmpeg resolves the manifest and pulls the whole graph
        // itself in one session, which is the part that works.
        //
        // The cost is real: an ffmpeg session cannot pause and cannot resume after a restart, so
        // an interrupted segmented download starts again. Progressive files keep both, which is
        // why they stay on Media3 rather than everything moving across.
        if (stream.isLocalProxy || stream.isHls || stream.isDash) {
            enqueueViaFfmpeg(sourceId, animeUrl, title, posterUrl, sourceName, episode, stream, subtitle)
            detail?.let { scope.launch { cacheDetail(it) } }
            return
        }

        detail?.let { scope.launch { cacheDetail(it) } }

        scope.launch {
            val settings = store.currentSettings()
            engine.useVolume(settings.downloadVolume)

            val entry = DownloadEntry(
                sourceId = sourceId,
                animeUrl = animeUrl,
                episodeUrl = episode.url,
                title = title,
                episodeName = episode.name,
                episodeNumber = episode.number,
                posterUrl = posterUrl,
                sourceName = sourceName,
                streamLabel = stream.label,
                // Both needed to play it back offline. An adaptive stream is reopened at the
                // manifest URI it was downloaded from - its segments are keyed individually
                // and listed inside that cached manifest - while a progressive file is found
                // by the download's own key.
                isAdaptive = stream.isHls || stream.isDash,
                downloadUri = stream.url,
                volumeId = settings.downloadVolume ?: VOLUME_INTERNAL,
                state = DownloadState.QUEUED,
                createdAt = System.currentTimeMillis(),
            )
            store.saveDownload(entry)

            // The engine needs to know which download it is working on before the first
            // request, because the data source asks the resolver for headers rather than
            // being handed them.
            resolver.setActive(
                key = entry.key,
                sourceId = sourceId,
                episodeUrl = episode.url,
                streamLabel = stream.label,
                // From the resolve that produced this stream, so the first request
                // already carries the Referer these CDNs check for.
                knownHeaders = stream.headers,
            )

            val request = DownloadRequest.Builder(entry.key, android.net.Uri.parse(stream.url))
                // Declared, not sniffed. A manifest handed to the progressive downloader is
                // fetched as one opaque file - the segments it points at are never followed -
                // so an HLS download would "succeed" as an unplayable playlist.
                .setMimeType(
                    when {
                        stream.isHls -> MimeTypes.APPLICATION_M3U8
                        stream.isDash -> MimeTypes.APPLICATION_MPD
                        else -> null
                    },
                )
                // Progressive only, because Media3 rejects a custom key on anything adaptive -
                // "customCacheKey must be null for type: 2" and the download crashes.
                //
                // The two need different treatment anyway. A progressive file is one cache
                // entry keyed by its URL, and since these URLs are signed and expire it needs a
                // stable key or the bytes become unfindable. An adaptive stream is a manifest
                // plus hundreds of segments, each already keyed by its own URL and listed
                // inside the cached manifest - so it is found by reopening the same manifest
                // URI, which Media3 records in its own index and playback reads back.
                .apply {
                    if (!stream.isHls && !stream.isDash) setCustomCacheKey(entry.key)
                }
                .build()

            withContext(Dispatchers.Main) {
                DownloadService.sendAddDownload(
                    context,
                    MediaDownloadService::class.java,
                    request,
                    /* foreground = */ false,
                )
            }

            // After the video is queued, not before. A subtitle is a courtesy; failing to find
            // one must not stop the download the user actually asked for, and the search is a
            // network round-trip that would otherwise delay it.
            //
            // The stream's own tracks are passed in because the choice between them and an
            // online search is made from what this particular stream offers.
            fetchSubtitles(entry, stream.subtitles, subtitle, stream.headers)
        }
    }

    /**
     * Fetches a subtitle in the preferred language for a queued download.
     *
     * Best-effort throughout. No match, no IMDb or TMDB id to search with, a provider that is
     * down - each ends with the download having no external subtitle, which is the same
     * position as before this ran. Source-embedded tracks are unaffected: they travel inside
     * the media and need no help.
     *
     * The file is written beside the video rather than into the subtitle cache, which is
     * emptied at every episode change and would take an offline copy with it.
     */
    private suspend fun fetchSubtitles(
        entry: DownloadEntry,
        sourceTracks: List<SubtitleOption>,
        chosen: SubtitleResult?,
        streamHeaders: Map<String, String> = emptyMap(),
    ) {
        val subtitles = subtitleRepository ?: return

        // The source's own tracks first, and they were being lost entirely: an extension hands
        // these over as sidecar URLs, the player shows them, and nothing saved them - so a
        // downloaded episode arrived with no subtitles even where the source had supplied
        // several. They are also the better file when present, being cut for this exact release
        // rather than matched by title and episode.
        val saved = saveSourceTracks(entry, sourceTracks, streamHeaders)
        if (saved.isNotEmpty()) {
            appendSubtitles(entry.key, saved)
            return
        }

        // Otherwise whatever was chosen at download time. Nothing is searched for here: that
        // happened in front of the viewer, who picked a specific file or declined - so there is
        // no second guess to make behind their back.
        val result = chosen ?: return

        runCatching {
            val option = subtitles.downloadForOffline(
                result = result,
                targetDir = storage.subtitleDir(entry.volumeId, entry.key),
            ) ?: return@runCatching

            // The release name the viewer picked from the search, not the provider id the file
            // is stored under - that is unreadable and identifies nothing to a person.
            appendSubtitles(
                entry.key,
                listOf(
                    DownloadedSubtitle(
                        url = option.url,
                        label = option.label,
                        language = option.language,
                    ),
                ),
            )
        }
    }

    /**
     * Downloads the subtitle tracks the source supplied with the stream.
     *
     * Written to disk rather than kept as URLs, for the same reason the video is: the URLs are
     * signed like the stream's own and are dead within minutes, so an offline copy pointing at
     * one plays no subtitles at all.
     *
     * Best-effort per track. One that fails is skipped rather than abandoning the rest - three
     * of four languages is a better outcome than none.
     */
    private suspend fun saveSourceTracks(
        entry: DownloadEntry,
        tracks: List<SubtitleOption>,
        streamHeaders: Map<String, String>,
    ): List<DownloadedSubtitle> {
        if (tracks.isEmpty()) return emptyList()

        val dir = storage.subtitleDir(entry.volumeId, entry.key)

        return tracks.mapIndexedNotNull { index, track ->
            runCatching {
                val text = subtitleRepository?.rawText(track.url, streamHeaders).orEmpty()
                if (text.isBlank()) return@runCatching null

                // Named by index and language rather than by the remote filename, which is
                // routinely absent from a signed URL.
                val extension = track.url.substringBefore('?').substringAfterLast('.', "vtt")
                    .takeIf { it.length in 3..4 } ?: "vtt"
                val safeLang = track.language.filter(Char::isLetterOrDigit).ifBlank { "sub" }
                val file = File(dir, "src-$index-$safeLang.$extension")
                file.writeText(text)

                // The source's own label is kept as it was given. The filename cannot hold it -
                // that is what the record is for.
                DownloadedSubtitle(
                    url = file.toURI().toString(),
                    label = track.label,
                    language = track.language,
                )
            }.getOrNull()
        }
    }

    /**
     * Adds saved subtitles to a download's record.
     *
     * Re-read rather than using an entry captured earlier: the download may have started, or
     * finished, while these were being fetched.
     */
    private suspend fun appendSubtitles(key: String, tracks: List<DownloadedSubtitle>) {
        if (tracks.isEmpty()) return
        val current = entryFor(key) ?: return
        store.saveDownload(current.copy(subtitleTracks = current.subtitleTracks + tracks))
    }

    /**
     * Downloads a stream through FFmpeg.
     *
     * Limited by the same concurrency setting Media3's queue uses, through a semaphore rather
     * than a queue: these sessions run in this process rather than in the download service, so
     * there is nothing to hand them to. Without it, queueing a season would start every episode
     * at once and saturate the connection.
     *
     * A proxied stream is the exception and skips the wait entirely. The whole reason it can be
     * downloaded is that the extension's proxy is alive now and will not be later, so holding it
     * behind a slot would mean waiting for the thing that makes it possible to disappear.
     */
    private fun enqueueViaFfmpeg(
        sourceId: Long,
        animeUrl: String,
        title: String,
        posterUrl: String?,
        sourceName: String,
        episode: EpisodeEntry,
        stream: StreamOption,
        subtitle: SubtitleResult?,
    ) {
        scope.launch {
            val settings = store.currentSettings()
            val volumeId = settings.downloadVolume ?: VOLUME_INTERNAL

            // Resized on each use rather than held, since the preference can change between
            // downloads and a Semaphore's limit is fixed at construction.
            val slots = ffmpegSlots(settings.downloadConcurrency)

            val entry = DownloadEntry(
                sourceId = sourceId,
                animeUrl = animeUrl,
                episodeUrl = episode.url,
                title = title,
                episodeName = episode.name,
                episodeNumber = episode.number,
                posterUrl = posterUrl,
                sourceName = sourceName,
                streamLabel = stream.label,
                // Remuxed to a single file, so it is neither adaptive nor cache-backed as far as
                // playback is concerned - it is a plain file on disk.
                isAdaptive = false,
                isRemuxed = true,
                volumeId = volumeId,
                state = DownloadState.DOWNLOADING,
                createdAt = System.currentTimeMillis(),
            )
            store.saveDownload(entry)

            val target = storage.remuxFile(volumeId, entry.key)

            // The Wi-Fi rule, enforced here because the platform cannot enforce it for us.
            //
            // Media3's queue has this applied through Requirements, but an ffmpeg session runs in
            // this process and the download service knows nothing about it - so without this
            // check the setting silently did not apply to most downloads. Refused rather than
            // held: there is no scheduler here to release it later, and a download that sat
            // waiting forever with no way to see why would be worse than one that says no.
            if (settings.downloadWifiOnly && isMetered()) {
                android.util.Log.i(TAG, "refusing ${entry.key}: Wi-Fi only and connection is metered")
                store.saveDownload(entry.copy(state = DownloadState.FAILED))
                return@launch
            }

            // Queued behind the concurrency limit, except for a proxied stream which cannot
            // wait. Marked queued while it waits so the UI does not claim it is transferring.
            val proxied = stream.isLocalProxy
            if (!proxied) {
                store.saveDownload(entry.copy(state = DownloadState.QUEUED))
                slots.acquire()
                store.saveDownload(
                    entryFor(entry.key)?.copy(state = DownloadState.DOWNLOADING) ?: entry,
                )
            }

            val ok = try {
                ffmpeg.download(
                    key = entry.key,
                    stream = stream,
                    target = target,
                ) { written, percent ->
                    // In memory only, like Media3's own progress: this fires several times a
                    // second and every registry write rewrites the whole preferences blob.
                    _progress.value = _progress.value + (
                        entry.key to DownloadProgress(
                            // From ffprobe's duration, so this is real progress through the
                            // timeline. Zero when the probe could not read a length, in which
                            // case the UI falls back to showing the size written.
                            percent = percent,
                            bytesDownloaded = written,
                        )
                        )
                }
            } finally {
                if (!proxied) slots.release()
            }

            _progress.value = _progress.value - entry.key
            stopFfmpegServiceIfIdle()

            val current = entryFor(entry.key) ?: entry
            if (ok) {
                store.saveDownload(
                    current.copy(
                        state = DownloadState.COMPLETED,
                        sizeBytes = target.length(),
                        downloadUri = target.toURI().toString(),
                        completedAt = System.currentTimeMillis(),
                    ),
                )
                // No source tracks here: ffmpeg has already muxed those into the file, so
                // fetching them again as sidecars would store the same cues twice and show
                // every language in the panel two times over.
                fetchSubtitles(current, emptyList(), subtitle, stream.headers)
            } else {
                store.saveDownload(current.copy(state = DownloadState.FAILED))
            }
        }
    }

    /**
     * Stops a download, keeping what is already on disk so it can resume.
     *
     * A remuxed download cannot pause - an ffmpeg session is one process invocation with no
     * resume point - so pausing one cancels it outright. The UI hides the pause control for
     * those rather than offering something that silently discards the transfer.
     */
    /**
     * Stores [detail] so the title's page opens with the network off.
     *
     * Artwork is fetched here rather than left as a remote URL, since the image loader would
     * have nothing to read offline and the page would render with blank cards - which looks
     * broken rather than degraded. A failed fetch stores null and the page falls back to its
     * own placeholder, so the text is still there.
     */
    private suspend fun cacheDetail(detail: AnimeDetail) {
        val volumeId = store.currentSettings().downloadVolume ?: VOLUME_INTERNAL
        val titleKey = "${detail.sourceId}::${detail.url}"

        val poster = detail.posterUrl?.let { url ->
            cacheImage(url, storage.artworkFile(volumeId, titleKey, "poster"))
        }
        val backdrop = detail.backdropUrl?.let { url ->
            cacheImage(url, storage.artworkFile(volumeId, titleKey, "backdrop"))
        }

        store.saveOfflineDetail(
            OfflineDetail.from(
                detail = detail,
                posterPath = poster,
                backdropPath = backdrop,
                savedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Fetches one image to [target], returning its path or null.
     *
     * Kept as a plain connection rather than going through the extension's client: this is a
     * TMDB or source CDN URL needing no headers, and it must not fail the download it belongs
     * to. Skipped when the file is already there, so re-downloading a series does not refetch
     * the same poster for every episode.
     */
    private suspend fun cacheImage(url: String, target: File): String? =
        withContext(Dispatchers.IO) {
            if (target.exists() && target.length() > 0) return@withContext target.absolutePath

            runCatching {
                val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection)
                    .apply {
                        connectTimeout = ARTWORK_TIMEOUT_MS
                        readTimeout = ARTWORK_TIMEOUT_MS
                        instanceFollowRedirects = true
                    }

                connection.inputStream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                connection.disconnect()

                target.absolutePath.takeIf { target.length() > 0 }
            }.getOrElse {
                // A partial file is worse than none: it would be stored as a valid path and
                // render as a broken image rather than falling back.
                target.delete()
                null
            }
        }

    fun pause(key: String) {
        scope.launch {
            // A remuxed download has no pause. It is one ffmpeg invocation with no resume point,
            // and the stop-reason commands below go to Media3's service, which has never heard of
            // it - so the state flipped to PAUSED in the registry while ffmpeg carried on
            // downloading. The UI showed paused, the network meter showed otherwise.
            //
            // Refused rather than turned into a cancel: pause and discard are different
            // intentions, and silently throwing away a part-finished transfer because the only
            // available verb was "stop" is worse than declining.
            if (entryFor(key)?.isRemuxed == true) {
                android.util.Log.i(TAG, "pause refused for remuxed download $key")
                return@launch
            }
            DownloadService.sendSetStopReason(
                context,
                MediaDownloadService::class.java,
                key,
                STOP_REASON_USER,
                /* foreground = */ false,
            )
            entryFor(key)?.let { store.saveDownload(it.copy(state = DownloadState.PAUSED)) }
        }
    }

    /** Resumes a paused download, re-resolving its URL first. */
    /**
     * Resumes a paused download, or restarts one that cannot be resumed.
     *
     * A pause is a stop reason and is lifted by clearing it. A download orphaned by the old
     * URL-based cache key is a different case: its bytes are unreachable, so un-stopping it
     * would resume onto data nothing can read. Those are removed and re-added from scratch,
     * which needs a fresh resolve because the URL it was queued with is long expired.
     */
    fun resume(key: String) {
        scope.launch {
            val entry = entryFor(key) ?: return@launch

            // A remuxed download cannot be resumed either, for the same reason. One that is
            // already running is left alone; one that failed is started again from nothing,
            // which is the only thing an ffmpeg session can do.
            if (entry.isRemuxed) {
                if (ffmpeg.isRunning(key)) {
                    android.util.Log.i(TAG, "resume ignored: $key is already running")
                    return@launch
                }
                android.util.Log.i(TAG, "restarting remuxed download $key from the beginning")
                restartRemuxed(entry)
                return@launch
            }
            // Registered without seeded headers, deliberately: a resume happens long after the
            // stream was resolved, so the headers stored then may be as stale as the URL. The
            // first request resolves fresh ones, and requeue() seeds them for the rest.
            resolver.setActive(
                key = key,
                sourceId = entry.sourceId,
                episodeUrl = entry.episodeUrl,
                streamLabel = entry.streamLabel,
            )

            // A failure is re-queued from scratch; a pause is simply un-paused.
            //
            // These are not the same operation, and treating them alike is what left a retried
            // download sitting at QUEUED forever. A paused download is stopped, so clearing the
            // stop reason releases it. A failed one is not stopped - it has already exhausted
            // its retries - so clearing a reason it never had does nothing, and the URL it was
            // queued with expired long ago anyway. It has to be removed and added again with a
            // freshly resolved URL.
            if (entry.state == DownloadState.FAILED) {
                // Removed first, or the re-added request merges with the failed entry already in
                // the index and inherits its exhausted state.
                withContext(Dispatchers.Main) {
                    DownloadService.sendRemoveDownload(
                        context,
                        MediaDownloadService::class.java,
                        key,
                        /* foreground = */ false,
                    )
                }
                store.saveDownload(entry.copy(state = DownloadState.QUEUED, sizeBytes = 0L))
                requeue(entry)
                return@launch
            }

            DownloadService.sendSetStopReason(
                context,
                MediaDownloadService::class.java,
                key,
                Download.STOP_REASON_NONE,
                /* foreground = */ false,
            )
            store.saveDownload(entry.copy(state = DownloadState.QUEUED))
        }
    }

    /**
     * Re-queues a download from its stored label, resolving a fresh URL first.
     *
     * Used where the original request cannot be reused - a stale cache key, or a URL that has
     * expired past the point the data source can refresh it.
     */
    private suspend fun requeue(entry: DownloadEntry) {
        val streams = repository.streams(entry.sourceId, entry.episodeUrl).getOrNull()
        val match = streams?.let { matchLabel(it, entry.streamLabel) }

        // A retry must not land on a proxied server either: the label may now match one, and
        // re-queueing against it would fail exactly as the original did.
        if (match == null || match.isLocalProxy) {
            store.saveDownload(entry.copy(state = DownloadState.FAILED))
            return
        }

        // Re-registered under this download's own key, so a later credential failure resolves
        // against this episode rather than whichever was queued most recently.
        resolver.setActive(
            key = entry.key,
            sourceId = entry.sourceId,
            episodeUrl = entry.episodeUrl,
            streamLabel = entry.streamLabel,
            knownHeaders = match.headers,
        )

        val request = DownloadRequest.Builder(entry.key, android.net.Uri.parse(match.url))
            .setMimeType(
                when {
                    match.isHls -> MimeTypes.APPLICATION_M3U8
                    match.isDash -> MimeTypes.APPLICATION_MPD
                    else -> null
                },
            )
            // Progressive only; see the note in enqueue. Media3 throws on an adaptive stream
            // carrying one.
            .apply {
                if (!match.isHls && !match.isDash) setCustomCacheKey(entry.key)
            }
            .build()

        withContext(Dispatchers.Main) {
            DownloadService.sendAddDownload(
                context,
                MediaDownloadService::class.java,
                request,
                /* foreground = */ false,
            )
        }
    }

    /** Cancels a download and deletes whatever it had written. */
    fun remove(key: String) {
        scope.launch {
            // Volume read before the entry goes, since it names where the sidecar files are.
            val entry = entryFor(key)
            val volumeId = entry?.volumeId

            // An ffmpeg session is not in Media3's queue, so the service knows nothing about it
            // and it has to be stopped directly.
            if (ffmpeg.isRunning(key)) ffmpeg.cancel(key)
            if (entry?.isRemuxed == true) {
                runCatching { storage.remuxFile(volumeId, key).delete() }
            }

            DownloadService.sendRemoveDownload(
                context,
                MediaDownloadService::class.java,
                key,
                /* foreground = */ false,
            )
            storage.deleteSubtitles(volumeId, key)
            // Dropped from the resolver too, or its map grows for the life of the process and
            // keeps re-resolving an episode nobody is downloading any more.
            resolver.clearActive(key)
            store.removeDownload(key)

            // The cached page goes with the last episode of its title, not with each one: a
            // series downloaded episode by episode shares one copy, and removing it while
            // others remain would leave those unopenable offline.
            entry?.let { removed ->
                val remaining = store.currentDownloads().any {
                    it.sourceId == removed.sourceId && it.animeUrl == removed.animeUrl
                }
                if (!remaining) {
                    val titleKey = "${removed.sourceId}::${removed.animeUrl}"
                    storage.deleteArtwork(volumeId, titleKey)
                    store.removeOfflineDetail(titleKey)
                }
            }
        }
    }

    /** Cancels every download and deletes what they had written. */
    fun removeAll() {
        scope.launch {
            // Both engines, and the sidecars.
            //
            // Clearing Media3's queue alone left every remuxed file on disk with nothing
            // referencing it - the same orphan state that loses gigabytes silently, except
            // reached deliberately by pressing "delete all". FFmpeg's sessions are not in that
            // queue, so they have to be stopped and their files removed here.
            store.currentDownloads().forEach { entry ->
                if (ffmpeg.isRunning(entry.key)) ffmpeg.cancel(entry.key)
                if (entry.isRemuxed) {
                    runCatching { storage.remuxFile(entry.volumeId, entry.key).delete() }
                }
                storage.deleteSubtitles(entry.volumeId, entry.key)
                storage.deleteArtwork(entry.volumeId, "${entry.sourceId}::${entry.animeUrl}")
                store.removeOfflineDetail("${entry.sourceId}::${entry.animeUrl}")
                resolver.clearActive(entry.key)
            }

            withContext(Dispatchers.Main) {
                DownloadService.sendRemoveAllDownloads(
                    context,
                    MediaDownloadService::class.java,
                    /* foreground = */ false,
                )
            }
        }
    }

    /**
     * Applies the concurrency preference to the running queue.
     *
     * Takes effect immediately: raising it starts more of the queue at once, and lowering it
     * lets the extras finish rather than cutting them off - Media3 stops issuing new ones
     * instead of abandoning what is in flight.
     */
    fun applyConcurrency(count: Int) {
        engine.useConcurrency(count)
    }

    /** Applies the Wi-Fi-only preference to the running queue. */
    /**
     * Applies the Wi-Fi rule to Media3's queue.
     *
     * Reaches Media3 only. An ffmpeg session runs in this process rather than in the download
     * service, so the platform has nothing to gate it on - the rule is enforced for those at the
     * point of starting one instead, in [enqueueViaFfmpeg].
     */
    fun applyRequirements(wifiOnly: Boolean) {
        val requirements = if (wifiOnly) {
            Requirements(Requirements.NETWORK_UNMETERED)
        } else {
            Requirements(Requirements.NETWORK)
        }
        DownloadService.sendSetRequirements(
            context,
            MediaDownloadService::class.java,
            requirements,
            /* foreground = */ false,
        )
    }

    /**
     * Brings the registry back in line with what is really on disk.
     *
     * Both halves can drift. A crash mid-download leaves an entry the engine has never heard
     * of; a cache cleared by hand leaves an entry whose bytes are gone; a pulled SD card
     * leaves entries that are neither missing nor available. The registry is treated as an
     * index over the engine's index, so where they disagree the engine wins - except for
     * volumes that are absent, which are left alone rather than deleted, because the files
     * come back when the card does.
     */
    private suspend fun reconcile() {
        val manager = engine.manager()
        val known = mutableMapOf<String, Download>()

        manager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val download = cursor.download
                known[download.request.id] = download
            }
        }

        val entries = store.currentDownloads()

        val reconciled = entries.mapNotNull { entry ->
            // Left as it stands. The bytes are on a volume that is not mounted, so the
            // engine legitimately knows nothing about them and deleting the record would
            // lose a download that is merely offline.
            if (!storage.isVolumeAvailable(entry.volumeId)) return@mapNotNull entry

            // A remuxed download is not in Media3's index and never will be: it is a plain file
            // that FFmpeg wrote. Its own file is the authority, so it is checked on disk instead.
            //
            // This is what silently deleted finished downloads on restart. Reconciliation asked
            // Media3 about every entry, found nothing for the remuxed ones, and dropped them -
            // leaving hundreds of megabytes on disk with no record pointing at them.
            if (entry.isRemuxed) {
                val file = storage.remuxFile(entry.volumeId, entry.key)
                return@mapNotNull if (file.length() > 0L) {
                    // Size re-read from the file, which is more trustworthy than a figure written
                    // while the download was still running.
                    entry.copy(sizeBytes = file.length())
                } else {
                    // The file really is gone - deleted by hand, or the write never finished - so
                    // the entry has nothing behind it.
                    null
                }
            }

            // Dropped. The volume is mounted and the engine has no record, so whatever was
            // written is gone - a cleared cache, or a crash before the index was flushed -
            // and an entry pointing at nothing is worse than no entry at all.
            val download = known[entry.key] ?: return@mapNotNull null

            // Progressive downloads written before the cache key was stable are unplayable.
            //
            // Those were keyed by the stream URL, and those URLs are signed and expire, so the
            // bytes sit under a key nothing can recompute. No migration is possible - the key
            // cannot be derived from an expired URL - so they are marked failed rather than
            // left looking complete, and the row offers a retry that works instead of playback
            // that cannot.
            //
            // Adaptive downloads are excluded: Media3 forbids a custom key on them, so a null
            // one is correct there and says nothing about whether the download is readable.
            // They are found through their manifest URI instead.
            val adaptive = entry.isAdaptive ||
                download.request.mimeType == MimeTypes.APPLICATION_M3U8 ||
                download.request.mimeType == MimeTypes.APPLICATION_MPD

            val orphaned = download.state == Download.STATE_COMPLETED &&
                !adaptive &&
                download.request.customCacheKey == null

            entry.copy(
                state = if (orphaned) {
                    DownloadState.FAILED
                } else {
                    download.state.toDownloadState()
                },
                sizeBytes = download.bytesDownloaded,
                completedAt = if (download.state == Download.STATE_COMPLETED && !orphaned) {
                    entry.completedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
                } else {
                    entry.completedAt
                },
            )
        }

        store.replaceDownloads(reconciled)
    }

    /**
     * Keeps a remuxed download's progress and state honest.
     *
     * Its byte count comes from ffmpeg's statistics callback, which is silent while the screen is
     * off - so progress froze mid-download and stayed frozen after waking, even though the
     * transfer never stopped. Reading the file's own size fixes that without depending on any
     * callback arriving.
     *
     * It also catches a session whose completion never ran. That is what left a download sitting
     * at QUEUED while its bytes were still growing, and then appearing to finish all at once much
     * later.
     */
    private suspend fun reconcileRemuxedProgress() {
        val running = store.currentDownloads().filter { it.isRemuxed && !it.isComplete }
        if (running.isEmpty()) return

        running.forEach { entry ->
            val file = storage.remuxFile(entry.volumeId, entry.key)
            val size = file.length()

            if (ffmpeg.isRunning(entry.key)) {
                // Size from disk, percentage left as the callback last reported it: the file
                // says how much has arrived, not how far through the timeline that is.
                val previous = _progress.value[entry.key]
                if (size > 0 && size != previous?.bytesDownloaded) {
                    _progress.value = _progress.value + (
                        entry.key to DownloadProgress(
                            percent = previous?.percent ?: 0f,
                            bytesDownloaded = size,
                        )
                        )
                }

                // The state can be stale too, if the session outlived a pause that never
                // reached it.
                if (entry.state != DownloadState.DOWNLOADING) {
                    store.saveDownload(entry.copy(state = DownloadState.DOWNLOADING))
                }
                return@forEach
            }

            // No session and no file: nothing is happening, and something claimed otherwise.
            if (size <= 0L) {
                if (entry.state == DownloadState.DOWNLOADING) {
                    store.saveDownload(entry.copy(state = DownloadState.FAILED))
                }
                return@forEach
            }

            // A file with no session behind it is finished, whether or not the completion
            // handler got to run - the process may have been killed between the two.
            _progress.value = _progress.value - entry.key
            store.saveDownload(
                entry.copy(
                    state = DownloadState.COMPLETED,
                    sizeBytes = size,
                    downloadUri = file.toURI().toString(),
                    completedAt = entry.completedAt.takeIf { it > 0 }
                        ?: System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * Drops the foreground service once no ffmpeg session is left.
     *
     * Counted from the sessions themselves rather than from the registry: an entry can be marked
     * complete a moment before or after its session ends, and stopping the service while another
     * download is still running would throttle it exactly as before.
     */
    private suspend fun stopFfmpegServiceIfIdle() {
        val stillRunning = store.currentDownloads().any { ffmpeg.isRunning(it.key) }
        if (!stillRunning) {
            withContext(Dispatchers.Main) { FfmpegDownloadService.stop(context) }
        }
    }

    /** Starts a remuxed download over, the only recovery an ffmpeg session allows. */
    private suspend fun restartRemuxed(entry: DownloadEntry) {
        val streams = repository.streams(entry.sourceId, entry.episodeUrl).getOrNull()
        val match = streams?.let { matchLabel(it, entry.streamLabel) }

        if (match == null) {
            store.saveDownload(entry.copy(state = DownloadState.FAILED))
            return
        }

        runCatching { storage.remuxFile(entry.volumeId, entry.key).delete() }
        store.saveDownload(entry.copy(state = DownloadState.DOWNLOADING, sizeBytes = 0L))

        val target = storage.remuxFile(entry.volumeId, entry.key)
        FfmpegDownloadService.start(context, entry.title)

        val ok = ffmpeg.download(entry.key, match, target) { written, percent ->
            _progress.value = _progress.value + (
                entry.key to DownloadProgress(percent = percent, bytesDownloaded = written)
                )
        }

        _progress.value = _progress.value - entry.key
        stopFfmpegServiceIfIdle()
        val current = entryFor(entry.key) ?: entry
        store.saveDownload(
            if (ok) {
                current.copy(
                    state = DownloadState.COMPLETED,
                    sizeBytes = target.length(),
                    downloadUri = target.toURI().toString(),
                    completedAt = System.currentTimeMillis(),
                )
            } else {
                current.copy(state = DownloadState.FAILED)
            },
        )
    }

    private suspend fun entryFor(key: String): DownloadEntry? =
        store.currentDownloads().firstOrNull { it.key == key }

    private val listener = object : androidx.media3.exoplayer.offline.DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: androidx.media3.exoplayer.offline.DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            // Logged, because a download failure was otherwise completely silent: the reason
            // arrives here and was being discarded, so "it just failed" was all anyone could
            // observe - including from a logcat capture. These are the failures that matter
            // most when diagnosing a source, and they are usually an HTTP status or a dead
            // host rather than anything the app can act on.
            if (download.state == Download.STATE_FAILED) {
                android.util.Log.w(
                    TAG,
                    "download failed: ${download.request.id} " +
                        "uri=${download.request.uri} " +
                        "mime=${download.request.mimeType} " +
                        "bytes=${download.bytesDownloaded} " +
                        "reason=${finalException?.javaClass?.simpleName}: " +
                        finalException?.message,
                    finalException,
                )
            }

            // Progress is owned by the poller above; this only records the transition. The
            // two have very different write costs, and only one of them needs to survive a
            // restart.
            scope.launch {
                val entry = entryFor(download.request.id) ?: return@launch
                val next = download.state.toDownloadState()
                if (entry.state == next && entry.sizeBytes == download.bytesDownloaded) return@launch

                // Nothing more to resolve for a download that has finished.
                if (next == DownloadState.COMPLETED) resolver.clearActive(download.request.id)

                store.saveDownload(
                    entry.copy(
                        state = next,
                        sizeBytes = download.bytesDownloaded,
                        completedAt = if (next == DownloadState.COMPLETED) {
                            System.currentTimeMillis()
                        } else {
                            entry.completedAt
                        },
                    ),
                )
            }
        }

        override fun onDownloadRemoved(
            downloadManager: androidx.media3.exoplayer.offline.DownloadManager,
            download: Download,
        ) {
            _progress.value = _progress.value - download.request.id
            resolver.clearActive(download.request.id)
        }
    }

    /**
     * Whether the active connection is metered.
     *
     * Read at the moment a download starts rather than observed: this is a gate on starting, not
     * a condition to wait on. Treated as unmetered when it cannot be determined, so a device that
     * reports nothing useful still downloads rather than refusing everything.
     */
    private fun isMetered(): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(
            android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
        )?.not() ?: false
    }.getOrDefault(false)

    /** Semaphore sized to the current concurrency preference, rebuilt when it changes. */
    private var slotsLimit = 0
    private var slots: java.util.concurrent.Semaphore? = null

    private fun ffmpegSlots(limit: Int): java.util.concurrent.Semaphore {
        val clamped = limit.coerceAtLeast(1)
        val existing = slots
        if (existing != null && slotsLimit == clamped) return existing
        // A permit held under the old limit is released against the new semaphore, which is
        // harmless: the release is what the finally block does and an unmatched one only raises
        // the ceiling for a moment.
        return java.util.concurrent.Semaphore(clamped, true).also {
            slots = it
            slotsLimit = clamped
        }
    }

    private companion object {
        const val TAG = "WbDownload"

        /** Distinguishes a user pause from the engine stopping for an unmet requirement. */
        const val STOP_REASON_USER = 1

        /**
         * How often a running download's byte count is re-read.
         *
         * Twice a second: fast enough that a progress bar moves rather than steps, slow enough
         * that it costs nothing next to the transfer itself.
         */
        const val ACTIVE_POLL_MS = 500L

        /** Idle interval, so nothing is woken up to ask about an empty queue. */
        const val IDLE_POLL_MS = 2_000L
    }
}

/** Live progress for one running download. */
data class DownloadProgress(
    val percent: Float,
    val bytesDownloaded: Long,
    /**
     * Total size, or zero when it is not knowable.
     *
     * Only a progressive file reports a length up front. A segmented download has no total
     * until its last segment arrives, so the UI falls back to a percentage there rather than
     * printing "1.2 GB of 0 B".
     */
    val totalBytes: Long = 0L,
)

/** Maps Media3's download state onto the registry's. */
@UnstableApi
private fun Int.toDownloadState(): DownloadState = when (this) {
    Download.STATE_QUEUED -> DownloadState.QUEUED
    Download.STATE_DOWNLOADING -> DownloadState.DOWNLOADING
    Download.STATE_STOPPED -> DownloadState.PAUSED
    Download.STATE_COMPLETED -> DownloadState.COMPLETED
    Download.STATE_FAILED -> DownloadState.FAILED
    // RESTARTING and REMOVING are both transient; treating them as queued keeps the UI
    // from flashing a state the user cannot act on.
    else -> DownloadState.QUEUED
}

/**
 * Timeout for fetching a poster or backdrop.
 *
 * Short, because this is a nicety attached to a download that matters more: a slow CDN must
 * delay neither the queue nor the page, and the fallback is the placeholder already shown for
 * a title with no artwork.
 */
private const val ARTWORK_TIMEOUT_MS = 15_000
