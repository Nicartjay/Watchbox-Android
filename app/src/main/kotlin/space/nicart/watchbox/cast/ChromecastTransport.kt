package space.nicart.watchbox.cast

import android.content.Context
import android.util.Log
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage

/**
 * Chromecast transport.
 *
 * Thin wrapper over the Cast SDK: it owns the session lifecycle and translates
 * our [CastMedia] into a `MediaLoadRequestData`. It deliberately knows nothing
 * about proxying — [CastManager] decides whether a URL needs to be relayed and
 * hands over the final URL.
 *
 * Play Services may be missing entirely (emulators, de-Googled devices), so every
 * entry point tolerates a null [CastContext] rather than assuming availability.
 */
class ChromecastTransport(private val context: Context) : CastTransport {

    private var castContext: CastContext? = null
    private var sessionListener: SessionManagerListener<CastSession>? = null

    private val session: CastSession?
        get() = castContext?.sessionManager?.currentCastSession

    private val remote: RemoteMediaClient?
        get() = session?.remoteMediaClient

    override val isAvailable: Boolean get() = castContext != null

    override val isConnected: Boolean
        get() = castContext?.castState == CastState.CONNECTED

    /**
     * Initialises the SDK.
     *
     * Guarded because `CastContext.getSharedInstance` throws when Play Services
     * is absent or out of date, and that must degrade to "casting unavailable"
     * rather than taking down the player.
     */
    fun initialise(onStateChange: (Boolean) -> Unit) {
        val available = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context)

        if (available != ConnectionResult.SUCCESS) {
            Log.i(TAG, "Play Services unavailable ($available); Chromecast disabled")
            return
        }

        castContext = runCatching { CastContext.getSharedInstance(context) }
            .onFailure { Log.w(TAG, "Cast unavailable: ${it.message}") }
            .getOrNull()

        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) =
                onStateChange(true)

            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) =
                onStateChange(true)

            override fun onSessionEnded(session: CastSession, error: Int) =
                onStateChange(false)

            override fun onSessionSuspended(session: CastSession, reason: Int) =
                onStateChange(false)

            override fun onSessionStartFailed(session: CastSession, error: Int) {
                Log.w(TAG, "session start failed: $error")
                onStateChange(false)
            }

            override fun onSessionResumeFailed(session: CastSession, error: Int) =
                onStateChange(false)

            override fun onSessionStarting(session: CastSession) = Unit
            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
            override fun onSessionEnding(session: CastSession) = Unit
        }

        sessionListener = listener
        castContext?.sessionManager?.addSessionManagerListener(
            listener,
            CastSession::class.java,
        )
    }

    fun release() {
        sessionListener?.let {
            castContext?.sessionManager?.removeSessionManagerListener(it, CastSession::class.java)
        }
        sessionListener = null
    }

    /** Host of the connected device, so the proxy can bind a reachable interface. */
    override fun connectedDeviceHost(): String? =
        session?.castDevice?.inetAddress?.hostAddress

    override val deviceName: String?
        get() = session?.castDevice?.friendlyName

    override suspend fun load(media: CastMedia, positionMs: Long): Boolean {
        val client = remote ?: return false

        val metadata = MediaMetadata(
            if (media.isMovie) {
                MediaMetadata.MEDIA_TYPE_MOVIE
            } else {
                MediaMetadata.MEDIA_TYPE_TV_SHOW
            },
        ).apply {
            putString(MediaMetadata.KEY_TITLE, media.title)
            media.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            media.artworkUrl?.let { addImage(WebImage(android.net.Uri.parse(it))) }
        }

        // Subtitles must be WebVTT for the Cast receiver; SRT is silently ignored.
        val tracks = media.subtitles.mapIndexed { index, subtitle ->
            MediaTrack.Builder(SUBTITLE_TRACK_ID_BASE + index, MediaTrack.TYPE_TEXT)
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(subtitle.url)
                .setContentType(SUBTITLE_MIME)
                .setName(subtitle.label)
                .setLanguage(subtitle.language.ifBlank { null })
                .build()
        }

        val info = MediaInfo.Builder(media.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(media.mimeType)
            .setMetadata(metadata)
            .apply {
                if (tracks.isNotEmpty()) setMediaTracks(tracks)
                media.durationMs.takeIf { it > 0 }?.let { setStreamDuration(it) }
            }
            .build()

        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .setCurrentTime(positionMs)
            .apply {
                // Activate the first track so subtitles are on by default when
                // the user had them enabled locally.
                if (tracks.isNotEmpty()) {
                    setActiveTrackIds(longArrayOf(SUBTITLE_TRACK_ID_BASE))
                }
            }
            .build()

        return runCatching { client.load(request); true }
            .onFailure { Log.w(TAG, "load failed: ${it.message}") }
            .getOrDefault(false)
    }

    override fun play() { remote?.play() }

    override fun pause() { remote?.pause() }

    override fun stop() {
        runCatching { remote?.stop() }
        runCatching { castContext?.sessionManager?.endCurrentSession(true) }
    }

    override fun seekTo(positionMs: Long) {
        remote?.seek(MediaSeekOptions.Builder().setPosition(positionMs).build())
    }

    override fun positionMs(): Long = remote?.approximateStreamPosition ?: 0L

    override fun durationMs(): Long = remote?.streamDuration ?: 0L

    override fun isPlaying(): Boolean =
        remote?.mediaStatus?.playerState == MediaStatus.PLAYER_STATE_PLAYING

    private companion object {
        const val TAG = "Chromecast"
        const val SUBTITLE_MIME = "text/vtt"
        const val SUBTITLE_TRACK_ID_BASE = 1L
    }
}
