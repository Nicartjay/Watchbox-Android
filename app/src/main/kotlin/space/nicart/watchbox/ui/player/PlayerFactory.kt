package space.nicart.watchbox.ui.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import okhttp3.OkHttpClient
import space.nicart.watchbox.domain.StreamOption
import space.nicart.watchbox.domain.SubtitleOption
import java.util.concurrent.TimeUnit

/**
 * ExoPlayer construction.
 *
 * Streams come straight from an extension, which means the per-request headers
 * it supplied (usually a Referer the CDN checks) have to be applied on the media
 * requests too — without them most sources return 403.
 *
 * Those headers are read through [headerProvider] on every request rather than
 * baked into the data source at construction. Some sources sign their URLs and
 * hand back a short-lived credential — a CloudFront cookie valid for about two
 * minutes is typical — so a value captured once goes stale within the session
 * and every later request 403s. Reading them per request also means a quality
 * switch no longer has to rebuild the player just to change a header.
 */
@UnstableApi
object PlayerFactory {

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }

    /**
     * Builds a player whose media requests carry whatever [headerProvider]
     * returns at the moment each request is made.
     *
     * [onMediaSourceFactory] hands the assembled factory back to the caller. Building a
     * merged source needs the very same chain the player uses - cache wrapper, per-request
     * headers and all - and rebuilding it separately would read a sideloaded audio track
     * without the Referer its CDN checks, or straight past a downloaded copy.
     */
    fun create(
        context: Context,
        headerProvider: () -> Map<String, String> = ::emptyMap,
        /**
         * Wraps the network chain so a downloaded stream is read from disk first.
         *
         * Supplied rather than reached for, so the player has no dependency on the download
         * engine: given nothing, it behaves exactly as it did before downloads existed.
         */
        cacheWrapper: ((androidx.media3.datasource.DataSource.Factory) -> androidx.media3.datasource.DataSource.Factory)? = null,
        onMediaSourceFactory: (DefaultMediaSourceFactory) -> Unit = {},
    ): ExoPlayer {
        val httpFactory = OkHttpDataSource.Factory { request -> okHttp.newCall(request) }
            .setUserAgent(DEFAULT_USER_AGENT)

        // Applied per request. The User-Agent is set through the factory above,
        // so it is dropped here to avoid sending the header twice, and a source
        // that specifies its own still wins because the resolver runs last.
        val resolver = ResolvingDataSource.Resolver { dataSpec ->
            val headers = headerProvider()
            if (headers.isEmpty()) {
                dataSpec
            } else {
                dataSpec.withRequestHeaders(headers)
            }
        }

        val networkFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(context, httpFactory),
            resolver,
        )

        // Cache in front of the network where downloads exist. A stream that was downloaded
        // then plays from disk with no extension call and no request, which is what makes a
        // download an offline copy rather than a warm cache.
        val dataSourceFactory = cacheWrapper?.invoke(networkFactory) ?: networkFactory

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 120_000,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000,
            )
            .build()

        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setPreferredTextLanguage(null)
                .setSelectUndeterminedTextLanguage(true)
                .build()
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        onMediaSourceFactory(mediaSourceFactory)

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply { playWhenReady = true }
    }

    /**
     * Builds a [MediaItem] with side-loaded subtitles.
     *
     * The MIME type is inferred from the URL rather than declared: sources hand
     * back a mix of `.vtt`, `.srt` and `.ass`, and forcing one type makes the
     * others silently fail to render.
     */
    fun buildMediaItem(
        stream: StreamOption,
        subtitles: List<SubtitleOption>,
        title: String,
        /**
         * The key a download of this episode was cached under, when there is one.
         *
         * Without it the cache is keyed by URL, and these URLs are signed and expire, so a
         * downloaded episode could never be matched back to its bytes - playback looked past a
         * file already on disk and fetched it again.
         */
        cacheKey: String? = null,
    ): MediaItem {
        val subtitleConfigs = subtitles.map { subtitle ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle.url))
                .setMimeType(subtitleMimeType(subtitle.url))
                .setLanguage(subtitle.language.takeIf { it.isNotBlank() })
                .setLabel(subtitle.label)
                .build()
        }

        return MediaItem.Builder()
            .setUri(stream.url)
            .setMimeType(
                when {
                    stream.isHls -> MimeTypes.APPLICATION_M3U8
                    // Without this a manifest is handed to the progressive
                    // extractor as MP4, which cannot parse XML and fails before
                    // a single request is made. DASH is checked first because
                    // isHls is a substring test.
                    stream.isDash -> MimeTypes.APPLICATION_MPD
                    // Anything else is a plain file, and the container is the
                    // extractor's business. Declaring a type here overrides
                    // ExoPlayer's own sniffing, which previously sent every
                    // .mkv to the MP4 extractor and failed before playback.
                    else -> null
                },
            )
            .setSubtitleConfigurations(subtitleConfigs)
            .setCustomCacheKey(cacheKey)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(title)
                    .build(),
            )
            .build()
    }

    /**
     * Wraps [item] so the stream's separate audio URLs play alongside it.
     *
     * Some sources deliver audio as its own playlist rather than muxed into the video -
     * an HLS master naming `#EXT-X-MEDIA:TYPE=AUDIO` with a `URI` of its own. The video
     * rendition then carries no audio at all, and playing the item by itself is silent.
     * Merging attaches each audio URL as a parallel source, which both restores sound and
     * makes the tracks selectable like any embedded one.
     *
     * Returns null when there is nothing to merge, so the caller keeps the plain
     * `setMediaItem` path for the overwhelming majority of streams.
     */
    fun buildMergedSource(
        item: MediaItem,
        audioTracks: List<SubtitleOption>,
        factory: DefaultMediaSourceFactory,
    ): MediaSource? {
        val usable = audioTracks.filter { it.url.isNotBlank() }
        if (usable.isEmpty()) return null

        val video = factory.createMediaSource(item)

        val audio = usable.map { track ->
            // No MIME type: an audio playlist arrives as a bare URL and the extension does
            // not say what it is, so declaring one would override ExoPlayer's sniffing the
            // way it used to for .mkv. The title is carried for diagnostics only - the
            // picker names these from the source's own list, because a merged rendition's
            // media usually has no label or language of its own.
            val audioItem = MediaItem.Builder()
                .setUri(android.net.Uri.parse(track.url))
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(track.label)
                        .build(),
                )
                .build()
            factory.createMediaSource(audioItem)
        }

        // adjustPeriodTimeOffsets is off: these renditions describe the same timeline as the
        // video, so shifting them would desync the audio. clipDurations is off as well,
        // because an audio playlist can report a slightly different duration and clipping to
        // the shortest would cut the end off the episode.
        return MergingMediaSource(
            /* adjustPeriodTimeOffsets = */ false,
            /* clipDurations = */ false,
            *(listOf(video) + audio).toTypedArray(),
        )
    }
}

/** Picks a subtitle MIME type from the file extension. */
private fun subtitleMimeType(url: String): String {
    val path = url.substringBefore('?').lowercase()
    return when {
        path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
        path.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
        path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
        path.endsWith(".ttml") || path.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
        // Most sources serve WebVTT without an extension.
        else -> MimeTypes.TEXT_VTT
    }
}
