package space.nicart.watchbox.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 *
 * ## Threading
 *
 * Every member here hops to the main thread, because the Cast SDK requires it and enforces
 * it: 56 methods on `RemoteMediaClient` call `Preconditions.checkMainThread`, and so do
 * `CastContext.getCastState` and `SessionManager.getCurrentCastSession`. Violating it throws
 * `IllegalStateException` rather than misbehaving quietly.
 *
 * That caught the app out: [CastManager] runs on `Dispatchers.IO`, which is right for DLNA's
 * blocking SOAP calls and fatal here. Picking a Chromecast crashed the app outright, because
 * even reading [isConnected] on the way to loading was a violation.
 *
 * The hop lives in this class rather than at the call sites so the mistake cannot be made
 * again by adding a method: there is nowhere in here that touches the SDK directly.
 */
class ChromecastTransport(private val context: Context) : CastTransport {

    private var castContext: CastContext? = null
    private var sessionListener: SessionManagerListener<CastSession>? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Runs [block] on the main thread and waits for its result.
     *
     * Blocking is acceptable because these are local, in-process calls that return immediately
     * - `load` hands off a request and answers with a PendingResult rather than waiting for the
     * receiver - and because the callers are already off the main thread on IO.
     *
     * Failures are swallowed to a fallback: a receiver going away mid-call must degrade to
     * "casting stopped working", not take the player down with it.
     */
    private fun <T> onMain(fallback: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching(block).getOrDefault(fallback)
        }

        val latch = CountDownLatch(1)
        var result: T = fallback

        mainHandler.post {
            result = runCatching(block).getOrDefault(fallback)
            latch.countDown()
        }

        // Bounded so a wedged main thread cannot hang the caller for ever. Overshooting the
        // deadline just returns the fallback, which reads as "not connected".
        return if (latch.await(MAIN_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) result else fallback
    }

    /** Fire-and-forget variant, for the calls whose result nothing reads. */
    private fun onMainAsync(block: () -> Unit) {
        mainHandler.post { runCatching(block) }
    }

    private val session: CastSession?
        get() = onMain(null) { castContext?.sessionManager?.currentCastSession }

    override val isAvailable: Boolean get() = castContext != null

    override val isConnected: Boolean
        get() = onMain(false) { castContext?.castState == CastState.CONNECTED }

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
        // Built off the main thread - these are plain data objects - then handed over in one
        // hop, so only the SDK call itself occupies the main thread.

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

        return onMain(false) {
            val client = castContext?.sessionManager?.currentCastSession?.remoteMediaClient
                ?: return@onMain false
            client.load(request)
            true
        }.also { if (!it) Log.w(TAG, "load failed or no session") }
    }

    override fun play() = onMainAsync {
        castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.play()
    }

    override fun pause() = onMainAsync {
        castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.pause()
    }

    override fun stop() = onMainAsync {
        castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.stop()
        castContext?.sessionManager?.endCurrentSession(true)
    }

    override fun seekTo(positionMs: Long) = onMainAsync {
        castContext?.sessionManager?.currentCastSession?.remoteMediaClient
            ?.seek(MediaSeekOptions.Builder().setPosition(positionMs).build())
    }

    override fun positionMs(): Long = onMain(0L) {
        castContext?.sessionManager?.currentCastSession
            ?.remoteMediaClient?.approximateStreamPosition ?: 0L
    }

    override fun durationMs(): Long = onMain(0L) {
        castContext?.sessionManager?.currentCastSession
            ?.remoteMediaClient?.streamDuration ?: 0L
    }

    override fun isPlaying(): Boolean = onMain(false) {
        castContext?.sessionManager?.currentCastSession
            ?.remoteMediaClient?.mediaStatus?.playerState == MediaStatus.PLAYER_STATE_PLAYING
    }

    private companion object {
        const val TAG = "Chromecast"
        const val SUBTITLE_MIME = "text/vtt"
        const val SUBTITLE_TRACK_ID_BASE = 1L

        /**
         * How long a main-thread hop may take before the caller gives up.
         *
         * These calls only marshal a request, so they return in microseconds; the timeout
         * exists purely so a blocked main thread cannot wedge the cast coroutine.
         */
        const val MAIN_CALL_TIMEOUT_MS = 2_000L
    }
}
