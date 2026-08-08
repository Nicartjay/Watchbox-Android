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
import space.nicart.watchbox.core.network.HttpClientFactory
import space.nicart.watchbox.domain.PlayableStream
import space.nicart.watchbox.domain.PlayableSubtitle
import java.util.concurrent.TimeUnit

/**
 * ExoPlayer construction.
 *
 * Streams always arrive already wrapped in the Worker's `/api/stream` proxy, so
 * the CDN `Referer`/`Origin` spoofing happens server-side and nothing sensitive
 * needs to live in the app. A larger-than-default buffer is used because the
 * proxy adds a hop.
 */
@UnstableApi
object PlayerFactory {

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }

    fun create(context: Context): ExoPlayer {
        val httpFactory = OkHttpDataSource.Factory { request -> okHttp.newCall(request) }
            .setUserAgent(HttpClientFactory.USER_AGENT)

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
     * Build a [MediaItem] with side-loaded subtitles.
     *
     * Subtitle URLs point at `/api/subtitle`, which normalises SRT to WebVTT
     * server-side, so a single [MimeTypes.TEXT_VTT] declaration is always correct.
     */
    fun buildMediaItem(
        stream: PlayableStream,
        subtitles: List<PlayableSubtitle>,
        title: String,
    ): MediaItem {
        val subtitleConfigs = subtitles.map { subtitle ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle.url))
                .setMimeType(MimeTypes.TEXT_VTT)
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
