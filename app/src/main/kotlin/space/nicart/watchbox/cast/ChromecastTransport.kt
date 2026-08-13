package space.nicart.watchbox.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.google.android.gms.cast.MediaError
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
 *
 * One rule makes that hold: **never return an SDK object from a hop, only a resolved value.**
 * A `CastSession` fetched on the main thread is no safer to touch afterwards, because the guard
 * is on its own methods - `getCastDevice`, `getRemoteMediaClient` and seven others. 3.5.1 kept
 * crashing for exactly that reason: the session was fetched correctly and then dereferenced
 * back on IO. Every member here therefore resolves down to a String, Long, Boolean or null
 * before the hop returns.
 */
class ChromecastTransport(private val context: Context) : CastTransport {

    private var castContext: CastContext? = null
    private var sessionListener: SessionManagerListener<CastSession>? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Reports playback faults the load call itself cannot.
     *
     * A single instance, registered per load and unregistered on stop: registering a new
     * anonymous callback on every load would accumulate them for the life of the session.
     */
    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onMediaError(error: MediaError) {
            Log.w(
                TAG,
                "receiver media error: reason=${error.reason} " +
                    "detailed=${error.detailedErrorCode} type=${error.type}",
            )
        }
    }

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

        // Also main-thread-guarded. Called from Application.onCreate today, which is already
        // the main thread - the hop makes that a property of this class rather than of its
        // caller, so moving the call later cannot reintroduce the crash.
        onMainAsync {
            castContext?.sessionManager?.addSessionManagerListener(
                listener,
                CastSession::class.java,
            )
        }
    }

    /**
     * Detaches the session listener.
     *
     * Hopped like everything else: `removeSessionManagerListener` is main-thread-guarded too.
     * Nothing calls this off the main thread today, but it is public and the guard throws, so
     * relying on the caller's thread would leave the same latent crash behind.
     */
    fun release() = onMainAsync {
        sessionListener?.let {
            castContext?.sessionManager?.removeSessionManagerListener(it, CastSession::class.java)
        }
        sessionListener = null
    }

    /**
     * Host of the connected device, so the proxy can bind a reachable interface.
     *
     * The whole chain is resolved inside the hop, down to the String. Fetching the session on
     * the main thread and then reading `castDevice` off it was still a violation - the guard is
     * on `CastSession.getCastDevice`, not on obtaining the session - and that is precisely how
     * 3.5.1 still crashed after the first round of this fix.
     */
    override fun connectedDeviceHost(): String? = onMain(null) {
        castContext?.sessionManager?.currentCastSession?.castDevice?.inetAddress?.hostAddress
    }

    override val deviceName: String?
        get() = onMain(null) {
            castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName
        }

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

                // Only the *video* format is declared, and only for video content.
                //
                // setHlsSegmentFormat is the AUDIO segment format - its permitted values are
                // AAC, AC3, MP3, E-AC3 and TS - whereas setHlsVideoSegmentFormat takes the
                // video one. Setting both made the receiver build an audio-only SourceBuffer
                // (`codecs="mp4a.40.2"`), so the H.264 video failed its very first append with
                // "Video stream codec h264 doesn't match SourceBuffer codecs" and the pipeline
                // stopped after ~1s.
                //
                // Without it the receiver assumes MPEG2-TS instead, and a fragmented-MP4 stream
                // buffers segments for ever without rendering a frame. So it does have to be
                // declared - just on the video channel only.
                HlsFormat.videoSegmentFormat(media.hlsSegmentFormat)
                    ?.let { setHlsVideoSegmentFormat(it) }
            }
            .build()

        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .setCurrentTime(positionMs)
            .apply {
                // Whichever track the viewer had on locally, not simply the first.
                //
                // The index is validated against the track list because subtitles that could not
                // be published are dropped on the way here, so a stale index would activate the
                // wrong track or one that does not exist.
                val active = media.selectedSubtitleIndex
                    .takeIf { it >= 0 && it < tracks.size }

                if (active != null) {
                    setActiveTrackIds(longArrayOf(SUBTITLE_TRACK_ID_BASE + active))
                }
            }
            .build()

        // Records the declared format: getting it wrong stalls the receiver silently, so this
        // is the line that makes such a failure diagnosable at all.
        Log.i(
            TAG,
            "loading ${media.subtitles.size} subtitle track(s), " +
                "hlsVideoSegmentFormat=${HlsFormat.videoSegmentFormat(media.hlsSegmentFormat)}",
        )
        return onMain(false) {
            val client = castContext?.sessionManager?.currentCastSession?.remoteMediaClient
                ?: return@onMain false
            // The receiver's own verdict, which used to be discarded. `load()` returning a
            // PendingResult that nobody read is why a stalled cast produced no diagnostic at
            // all: the session connected, the media loaded, and the app had nothing to say.
            client.load(request).setResultCallback { result ->
                val status = result.status
                if (!status.isSuccess) {
                    Log.w(
                        TAG,
                        "receiver rejected load: code=${status.statusCode} " +
                            "${status.statusMessage} mediaError=${result.mediaError?.reason}",
                    )
                }
            }

            // Kept for the whole session so a decode failure after a successful load is
            // reported. This is the case that looks like a hang rather than an error: the
            // receiver buffers segments and never renders a frame.
            client.registerCallback(mediaCallback)

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
        val client = castContext?.sessionManager?.currentCastSession?.remoteMediaClient
        runCatching { client?.unregisterCallback(mediaCallback) }
        client?.stop()
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

    /**
     * Activates one text track, or none when [index] is negative.
     *
     * An empty array is how the Cast protocol expresses "no tracks active"; there is no separate
     * disable call. The id arithmetic mirrors [load], where tracks are numbered from
     * [SUBTITLE_TRACK_ID_BASE] in list order.
     */
    override fun setSubtitleTrack(index: Int) = onMainAsync {
        val client = castContext?.sessionManager?.currentCastSession?.remoteMediaClient
            ?: return@onMainAsync

        val ids = if (index >= 0) {
            longArrayOf(SUBTITLE_TRACK_ID_BASE + index)
        } else {
            longArrayOf()
        }

        client.setActiveMediaTracks(ids)
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
