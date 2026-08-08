package space.nicart.watchbox.ui.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
 * requests too — without them most sources return 403. Those headers travel with
 * the [StreamOption] and are attached in [buildMediaItem].
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

    fun create(context: Context, headers: Map<String, String> = emptyMap()): ExoPlayer {
        val httpFactory = OkHttpDataSource.Factory { request -> okHttp.newCall(request) }
            .setUserAgent(headers["User-Agent"] ?: DEFAULT_USER_AGENT)
            .apply {
                // Referer and friends are what most source CDNs gate on.
                headers.filterKeys { !it.equals("User-Agent", true) }
                    .takeIf { it.isNotEmpty() }
                    ?.let(::setDefaultRequestProperties)
            }

        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

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

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
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
                if (stream.isHls) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4,
            )
            .setSubtitleConfigurations(subtitleConfigs)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(title)
                    .build(),
            )
            .build()
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
