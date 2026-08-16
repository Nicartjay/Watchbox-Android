package space.nicart.watchbox.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.nicart.watchbox.cast.CastManager
import space.nicart.watchbox.cast.CastProtocol
import space.nicart.watchbox.core.ui.LocalLayoutMetrics
import space.nicart.watchbox.cast.CastPermissions
import space.nicart.watchbox.cast.ExternalCast
import space.nicart.watchbox.cast.CastMedia
import space.nicart.watchbox.cast.CastSubtitle
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import space.nicart.watchbox.R
import space.nicart.watchbox.domain.EpisodeEntry
import space.nicart.watchbox.domain.StreamOption
import space.nicart.watchbox.ui.components.WbEmptyState
import space.nicart.watchbox.ui.components.WbLoading

/**
 * Full-screen player.
 *
 * Structure follows NuvioMobile `features/player/PlayerScreenContent.kt`: a black
 * full-size surface locked to landscape with immersive system bars, the video
 * behind, and the control overlay in an `AnimatedVisibility` gated on
 * `controlsVisible && !locked`.
 *
 * Gestures (`PlayerSurfaceGestures.kt`): single tap toggles controls, double tap
 * seeks +/-10s, horizontal drag scrubs with duration-scaled sensitivity.
 */
private const val CONTROLS_AUTO_HIDE_MS = 3_000L

/** Frames to spend chasing focus into the freshly composed controls. */
private const val CONTROL_FOCUS_ATTEMPTS = 12
private const val CONTROL_FOCUS_RETRY_MS = 60L
private const val TAG = "WbPlayer"

/**
 * How long the double-tap seek readout stays after the last tap.
 *
 * Longer than the tap window itself, so a run of taps shows one settled total rather than
 * flickering, and short enough not to sit over the picture once seeking has stopped.
 */
private const val SEEK_TAP_VISIBLE_MS = 800L

/**
 * Height of the control rows below the scrubber: the timecode row and the pill row.
 *
 * Measured from ProgressControls rather than guessed - the timecode row is 4dp vertical padding
 * plus 8dp bottom plus its text, and the pill row is a 48dp target with its own padding. Not in
 * PlayerMetrics because nothing else needs it, but kept named so the reason for the number is
 * findable.
 */
private val CONTROL_STACK_HEIGHT = 92.dp

/**
 * Gap between the skip button and the top of the control stack.
 *
 * Without it the two sit flush: the arithmetic puts the button's bottom edge exactly on the
 * stack's top edge, which is touching rather than clear.
 */
private val SKIP_CLEARANCE = 12.dp

@UnstableApi
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    castManager: CastManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Subtitle appearance, observed live.
     *
     * Passed in rather than read once in the ViewModel so a change in Settings takes
     * effect on a player that is already open.
     */
    subtitleStyle: SubtitleStyle = SubtitleStyle(
        size = SubtitleSize.MEDIUM,
        background = SubtitleBackground.OUTLINE,
        textColor = 0xFFFFFFFF.toInt(),
        backgroundOpacity = 0.6f,
        bold = false,
    ),
    onSetSubtitleSize: (SubtitleSize) -> Unit = {},
    onSetSubtitleBackground: (SubtitleBackground) -> Unit = {},
    onSetSubtitleEdgeWidth: (SubtitleEdgeWidth) -> Unit = {},
    onSetSubtitleColor: (Int) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activityForBrightness = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    // --- landscape lock + keep-awake + immersive, restored on exit
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val originalOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val insetsController = activity?.window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, it.decorView)
        }
        insetsController?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        insetsController?.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat
                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            activity?.requestedOrientation =
                originalOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    // Rebuilt when the header set changes: OkHttpDataSource takes its default
    // request properties at construction, and a source's Referer is per-stream.
    val streamHeaders = state.selectedStream?.headers.orEmpty()
    val exoPlayer = remember(streamHeaders) { PlayerFactory.create(context, streamHeaders) }

    val castState by castManager.state.collectAsStateWithLifecycle()

    // Discovery is retried on grant, so the list fills in without the user having
    // to close and reopen the panel.
    val nearbyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) castManager.discover()
    }

    // While casting, this screen is a remote control and nothing else: the phone must not
    // play or make a sound.
    //
    // A one-shot pause was not enough. Anything that rebuilds the media item - a quality
    // switch, a downloaded subtitle - sets playWhenReady = true again, and PlayerFactory
    // builds every new instance with it set, so local audio came back mid-session. The
    // volume is therefore zeroed as well as paused, and both are restored when the session
    // ends. Muting is what actually guarantees silence; the pause just stops it buffering
    // a stream nobody is watching.
    //
    // Playback is not resumed automatically when the session ends - stopping a cast is as
    // likely to mean "I am done" as "I will carry on here" - but the local player is moved to
    // where the receiver got to, so pressing play continues from there instead of from
    // wherever the phone was when casting started, which could be an hour earlier.
    var lastCastPositionMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(castState.isCasting, exoPlayer) {
        if (castState.isCasting) {
            exoPlayer.volume = 0f
            exoPlayer.playWhenReady = false
            exoPlayer.pause()
        } else {
            exoPlayer.volume = 1f

            // Captured before the session cleared it, so it is still meaningful here.
            if (lastCastPositionMs > 0) {
                exoPlayer.seekTo(lastCastPositionMs)
                lastCastPositionMs = 0L
            }
        }
    }

    // The PlayerView must be re-bound whenever the instance above is replaced.
    // AndroidView's factory runs only once, so binding there alone left video
    // attached to the discarded player while audio came from the new one -- the
    // "sound but black screen" symptom.
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    LaunchedEffect(exoPlayer, playerView) {
        playerView?.player = exoPlayer
    }

    // Media3's own subtitle view is hidden and cues are drawn by
    // ComposeSubtitleView instead. SubtitlePainter hardcodes the outline width to
    // 2dp with no API to change it, so honouring an outline-width setting is only
    // possible by rendering the cues ourselves.
    LaunchedEffect(playerView) {
        playerView?.subtitleView?.visibility = android.view.View.GONE
    }

    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var openPanel by remember { mutableStateOf(PlayerPanel.NONE) }

    /**
     * Bumped on every remote press, to re-arm the auto-hide timer.
     *
     * Counts presses this screen did *not* consume too: moving focus between the
     * controls is exactly the case where they must stay up, and those events are
     * deliberately left for the focus system.
     */
    var lastInteraction by remember { mutableIntStateOf(0) }

    val playerFocusRequester = remember { FocusRequester() }
    val playFocusRequester = remember { FocusRequester() }
    val metricsForFocus = LocalLayoutMetrics.current

    /** Observed focus, not assumed - see the retry below. */
    var playHasFocus by remember { mutableStateOf(false) }

    /**
     * Whether the on-screen controls are the D-pad's target.
     *
     * Also gates the video surface's focusability. The surface fills the window, so
     * every control sits geometrically *inside* it: while it is focusable, a
     * directional search from a control finds no candidate beyond the surface's own
     * bounds and focus never leaves the centre row. Dropping it out of the focus
     * order while the controls are up is what lets Up and Down reach the header and
     * the pill row.
     */
    val controlsOwnFocus = controlsVisible &&
        !state.locked &&
        !state.isResolving &&
        openPanel == PlayerPanel.NONE

    // Focus follows the controls, on TV only.
    //
    // The controls are the D-pad's target while they are up, so something in them has
    // to hold focus or the first press is spent moving off the video surface. When they
    // hide, focus returns to the surface, which is what keeps the reveal-first key
    // handling working.
    //
    // A panel takes precedence over both. It covers the controls and is the only thing
    // the user can act on, so it claims focus itself - sending focus back to the video
    // surface here would leave the panel unreachable, since the surface is not adjacent
    // to it in any direction the D-pad can travel.
    //
    // The retry mirrors tvInitialFocus, including why it watches playHasFocus rather
    // than the call: AnimatedVisibility composes the controls over the frames after
    // this runs, and requestFocus reports success even when its target has no node yet,
    // so trusting the return value exits the loop having moved nothing.
    //
    // `skipButtonOwnsFocus` keeps this effect out of the skip button's way. Both would
    // otherwise claim focus the moment the controls hide - this one for the video surface,
    // the button for itself - and whichever ran second would win, non-deterministically.
    // The button is the useful target while it is showing, so it takes precedence.
    val skipButtonOwnsFocus = metricsForFocus.isFocusDriven &&
        !controlsOwnFocus &&
        !state.locked &&
        state.skipIntervals.any { it.contains(positionMs) }

    LaunchedEffect(
        controlsVisible,
        openPanel,
        state.locked,
        state.isResolving,
        skipButtonOwnsFocus,
    ) {
        if (!metricsForFocus.isFocusDriven) return@LaunchedEffect

        if (openPanel != PlayerPanel.NONE) return@LaunchedEffect

        if (skipButtonOwnsFocus) return@LaunchedEffect

        if (!controlsOwnFocus) {
            runCatching { playerFocusRequester.requestFocus() }
            return@LaunchedEffect
        }

        repeat(CONTROL_FOCUS_ATTEMPTS) {
            withFrameNanos { }
            runCatching { playFocusRequester.requestFocus() }
            if (playHasFocus) return@LaunchedEffect
            delay(CONTROL_FOCUS_RETRY_MS)
        }
    }

    // ----------------------------------------------------------- transport routing
    //
    // Every playback control goes through these three, so casting is handled in one place
    // rather than at each call site. There are six of those - the buttons, the remote keys,
    // the slider, double-tap and drag-to-scrub - and adding an isCasting branch to each is
    // how one of them ends up forgotten and still driving the paused local player.
    //
    // `transportPosition` is the position the controls should act on: the receiver's while
    // casting, the local player's otherwise.

    val transportPosition: () -> Long = {
        if (castState.isCasting) castState.positionMs else exoPlayer.currentPosition
    }

    val transportIsPlaying: () -> Boolean = {
        if (castState.isCasting) castState.isRemotePlaying else exoPlayer.isPlaying
    }

    val transportPlay: () -> Unit = {
        if (castState.isCasting) castManager.play() else exoPlayer.play()
    }

    val transportPause: () -> Unit = {
        if (castState.isCasting) castManager.pause() else exoPlayer.pause()
    }

    val transportTogglePlay: () -> Unit = {
        if (transportIsPlaying()) transportPause() else transportPlay()
    }

    val transportSeekTo: (Long) -> Unit = { target ->
        // Clamped against whichever duration is known. A receiver rejects a seek past the end
        // outright, and DLNA renderers in particular can drop the session over it.
        val limit = durationMs.coerceAtLeast(0L)
        val clamped = if (limit > 0) target.coerceIn(0L, limit) else target.coerceAtLeast(0L)

        if (castState.isCasting) castManager.seekTo(clamped) else exoPlayer.seekTo(clamped)
    }

    val transportSeekBy: (Long) -> Unit = { delta ->
        transportSeekTo(transportPosition() + delta)
    }

    /**
     * Accumulated double-tap seek, for the on-screen feedback.
     *
     * Accumulated rather than reset per tap so a rapid run reads as one total - four taps show
     * "+40s" instead of flashing "+10s" four times. [seekTapBump] restarts the decay on every
     * tap; without a separate counter, a second tap of the same size would not re-trigger the
     * effect and the indicator would vanish mid-run.
     */
    var seekTapAccumulatedMs by remember { mutableLongStateOf(0L) }
    var seekTapOnLeft by remember { mutableStateOf(false) }
    var seekTapBump by remember { mutableIntStateOf(0) }

    /**
     * Applies a mapped remote action.
     *
     * Returns true when the key was consumed, so unhandled keys still fall through to
     * the system - Back in particular must keep working.
     */
    fun handlePlayerKey(action: PlayerKeyAction): Boolean {
        // A seek from the hidden state stays hidden.
        //
        // Revealing here would undo the point of seeking with the controls down: the
        // overlay would cover the picture on every skip, and the next press would go to
        // the focus system rather than the transport. The seek readout provides the
        // feedback instead.
        val seeksBlind = !controlsVisible &&
            (action == PlayerKeyAction.SEEK_BACK || action == PlayerKeyAction.SEEK_FORWARD)

        // Any other handled press re-arms the auto-hide timer, so the controls do not
        // vanish mid-interaction.
        if (action != PlayerKeyAction.NONE && action != PlayerKeyAction.DISMISS && !seeksBlind) {
            controlsVisible = true
        }

        return when (action) {
            PlayerKeyAction.SHOW_CONTROLS -> true

            PlayerKeyAction.TOGGLE_PLAY -> {
                transportTogglePlay()
                true
            }

            PlayerKeyAction.PLAY -> { transportPlay(); true }
            PlayerKeyAction.PAUSE -> { transportPause(); true }

            PlayerKeyAction.SEEK_BACK, PlayerKeyAction.SEEK_FORWARD -> {
                val delta = if (action == PlayerKeyAction.SEEK_FORWARD) {
                    PLAYER_KEY_SEEK_MS
                } else {
                    -PLAYER_KEY_SEEK_MS
                }
                transportSeekBy(delta)

                // The same readout the double-tap gesture draws, so a remote seek is
                // acknowledged on screen even with the controls hidden.
                seekTapAccumulatedMs = accumulateSeekTap(seekTapAccumulatedMs, delta)
                seekTapOnLeft = delta < 0
                seekTapBump++
                true
            }

            PlayerKeyAction.NEXT_EPISODE -> {
                state.nextEpisode?.let(viewModel::goToEpisode) != null
            }

            PlayerKeyAction.PREVIOUS_EPISODE -> {
                state.previousEpisode?.let(viewModel::goToEpisode) != null
            }

            // Returns false so the system handles Back when there is nothing of ours
            // left to dismiss, which is what leaves the player.
            PlayerKeyAction.DISMISS -> when {
                openPanel != PlayerPanel.NONE -> { openPanel = PlayerPanel.NONE; true }
                state.locked -> { viewModel.setLocked(false); true }
                controlsVisible -> { controlsVisible = false; true }
                else -> false
            }

            PlayerKeyAction.NONE -> false
        }
    }

    // Claimed once the surface exists, so the first remote press does something.
    // On TV the effect above takes over from here, moving focus onto the controls
    // whenever they are up; this still matters for the phone and for the window
    // between composition and that effect's first frame.
    LaunchedEffect(Unit) {
        runCatching { playerFocusRequester.requestFocus() }
    }
    var castPanelOpen by remember { mutableStateOf(false) }

    // Brightness is window-scoped, so it is tied to this activity; volume is a
    // system stream and re-synced whenever a gesture starts.
    val brightness = remember(activityForBrightness) {
        BrightnessController(activityForBrightness)
    }

    // Released when the player closes, so the dim level does not follow the user out.
    //
    // The window belongs to the Activity, not to this screen, and this app runs every screen in
    // one Activity - so nothing destroys the window on leaving the player and the override
    // persisted across the whole app. There is no brightness gesture outside the player, which
    // left no way to undo it short of killing the app.
    DisposableEffect(brightness) {
        onDispose { brightness.release() }
    }
    val volume = remember { VolumeController(context) }
    var activeGesture by remember { mutableStateOf(VerticalGesture.NONE) }
    var gestureLevel by remember { mutableFloatStateOf(0f) }
    var playbackError by remember { mutableStateOf<String?>(null) }

    // Clears the indicator once tapping stops. Keyed on the bump so each tap restarts the wait.
    LaunchedEffect(seekTapBump) {
        if (seekTapAccumulatedMs == 0L) return@LaunchedEffect
        delay(SEEK_TAP_VISIBLE_MS)
        seekTapAccumulatedMs = 0L
    }

    // The player's current track list, mirrored into state so the subtitle-selection effect
    // re-runs when a sideloaded track finishes parsing.
    var tracks by remember { mutableStateOf<androidx.media3.common.Tracks?>(null) }

    // --- player listener
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                    playbackError = null
                }
                if (playbackState == Player.STATE_ENDED) {
                    viewModel.onPlaybackEnded(exoPlayer.currentPosition, exoPlayer.duration)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = error.errorCodeName
                android.util.Log.e(TAG, "playback error: ${error.errorCodeName}", error)
            }

            // Text tracks arrive asynchronously - a sideloaded subtitle is fetched and parsed
            // after preparation - so the selection effect has to re-run when they land rather
            // than read them once.
            override fun onTracksChanged(newTracks: androidx.media3.common.Tracks) {
                tracks = newTracks
            }

            // Video-surface diagnostics. A black screen with working audio means
            // the renderer never got a surface, so these two are the signals that
            // actually distinguish "not decoding" from "decoding into nowhere".
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                android.util.Log.i(TAG, "video size ${videoSize.width}x${videoSize.height}")

            }

            override fun onRenderedFirstFrame() {
                android.util.Log.i(TAG, "first frame rendered")
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            // Unbind before releasing: a released player left attached keeps the
            // surface and renders black.
            playerView?.player = null
            exoPlayer.release()
        }
    }

    // --- pause on background, flush progress
    //
    // Re-keyed on the player and the setting, not just the owner. The observer closes over both,
    // and an ExoPlayer instance is replaced whenever the stream's headers change - so an effect
    // keyed on the owner alone would keep pausing the discarded player, and would ignore the
    // setting being toggled mid-session. Same class of bug as the tap detector below.
    DisposableEffect(lifecycleOwner, exoPlayer, state.backgroundPlayback) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    // Paused unless the user asked for background playback. Progress is written
                    // either way: leaving the app is exactly when the position must survive, and
                    // a background session can still be killed by the system at any point.
                    if (!state.backgroundPlayback) exoPlayer.pause()
                    viewModel.flushProgress(exoPlayer.currentPosition, exoPlayer.duration)
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // --- load / swap media whenever the selected stream or the subtitle set changes
    //
    // Keyed on the subtitle URLs as well as the stream. External subtitles are attached to the
    // MediaItem, so a newly downloaded one only becomes selectable if the item is rebuilt -
    // keyed on the stream alone it silently would not appear until the quality changed.
    //
    // Rebuilding restarts playback from zero, so the current position is captured and restored.
    // `positionMs` is already the live ticker value, which makes this a seek back to where the
    // viewer was rather than a jump to the resume point they had passed.
    LaunchedEffect(state.selectedStream?.url, state.subtitles.map { it.url }) {
        val stream = state.selectedStream ?: return@LaunchedEffect
        val resumeFrom = if (positionMs > 0) positionMs else state.resumeMs

        exoPlayer.setMediaItem(
            PlayerFactory.buildMediaItem(
                stream = stream,
                subtitles = state.subtitles,
                title = state.title,
            ),
        )
        exoPlayer.prepare()
        if (resumeFrom > 0) exoPlayer.seekTo(resumeFrom)

        // Not while casting. This effect re-runs on a quality switch or a subtitle download,
        // and starting local playback then is what previously brought the phone's audio back
        // in the middle of a cast session.
        exoPlayer.playWhenReady = !castState.isCasting
    }

    // --- skip intervals
    //
    // Requested once the runtime is known rather than when the episode is chosen: AniSkip checks
    // the episode length against the interval it holds, so asking with a duration of zero returns
    // nothing at all. Keyed on the episode too, so a new episode re-asks.
    LaunchedEffect(state.episode?.url, durationMs) {
        if (durationMs > 0) viewModel.loadSkipTimes(durationMs)
    }

    // --- speed
    LaunchedEffect(state.speed) { exoPlayer.setPlaybackSpeed(state.speed) }

    // Closes the search once a download has succeeded. Keyed on the dedicated Applied state
    // rather than Idle: the search begins from Idle, so testing for that would dismiss the
    // panel in the same frame it opened.
    LaunchedEffect(state.subtitleSearch) {
        if (state.subtitleSearch is SubtitleSearchState.Applied) {
            if (openPanel == PlayerPanel.SUBTITLE_SEARCH) openPanel = PlayerPanel.NONE

            // A subtitle downloaded mid-session is not on the receiver at all: its track list is
            // fixed at load time, so there is nothing to switch to. Reloading is the only way to
            // introduce one, and it resumes from the receiver's own position so the viewer keeps
            // their place.
            //
            // Both protocols, not just Chromecast: DLNA needs it more, since it cannot switch
            // tracks at all and the DIDL metadata is only read when the media is set.
            if (castState.isCasting) {
                castManager.reloadCurrent(
                    state.toCastMedia(
                        runtimeMs = durationMs,
                        preferProgressive = castState.protocol == CastProtocol.DLNA,
                    ),
                )
            }

            viewModel.onSubtitleApplied()
        }
    }

    // --- subtitle selection
    //
    // Selected by track rather than by language. Language is not a unique key: two downloaded
    // English releases both report "en", so `setPreferredTextLanguage` would always resolve to
    // whichever Media3 saw first and the second could never be chosen. An override names the
    // exact track group, which is the only way to tell same-language tracks apart.
    //
    // Text groups are matched positionally against `state.subtitles` because that is the order
    // they were handed to the MediaItem in. Anything already embedded in the stream itself is
    // skipped, since it has no entry in our list to correspond to.
    //
    // The player's own text track is switched off entirely while a timing offset is in
    // effect. This is what actually makes the offset visible: the app draws its own shifted
    // cues, but leaving the decoder enabled meant it went on emitting the same lines at
    // their original times through `onCues`, so the unshifted copy kept appearing and the
    // correction looked like it did nothing.
    //
    // Only when there are cues to draw instead. With an unparsable subtitle - ASS/SSA, or an
    // embedded track with no URL - disabling the track would leave no subtitles at all,
    // which is far worse than ones that are slightly out.
    val rendersOwnCues = state.subtitleOffsetMs != 0L && state.offsetCues.isNotEmpty()

    LaunchedEffect(
        state.selectedSubtitleIndex,
        state.selectedStream?.url,
        tracks,
        rendersOwnCues,
    ) {
        val wanted = state.selectedSubtitleIndex.takeIf { it >= 0 }

        val textGroups = tracks?.groups.orEmpty()
            .filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }

        val builder = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(
                androidx.media3.common.C.TRACK_TYPE_TEXT,
                wanted == null || rendersOwnCues,
            )

        // Nothing further to select when the app is drawing the cues itself.
        if (rendersOwnCues) {
            exoPlayer.trackSelectionParameters = builder.build()
            return@LaunchedEffect
        }

        // Sideloaded configurations are appended after the stream's own text tracks, so the
        // tail of the list lines up with ours.
        val offset = (textGroups.size - state.subtitles.size).coerceAtLeast(0)
        val group = wanted?.let { textGroups.getOrNull(it + offset) }

        if (group != null) {
            builder.addOverride(
                androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, 0),
            )
        } else if (wanted != null) {
            // The chosen track has not been parsed yet - a remote subtitle is fetched lazily.
            // Falling back to the language keeps it working; the override lands once the
            // track list arrives and this effect re-runs.
            state.subtitles.getOrNull(wanted)?.language
                ?.takeIf { it.isNotBlank() }
                ?.let(builder::setPreferredTextLanguage)
        }

        exoPlayer.trackSelectionParameters = builder.build()
    }

    // --- position ticker + throttled history writes
    //
    // While casting the clock comes from the receiver instead of the local player. The local
    // one is paused then, so reading it left the seek bar frozen at the moment casting began
    // while the television played on.
    //
    // History is still written, from whichever clock is authoritative. Keyed on isCasting so
    // the loop switches sources when a session starts or ends.
    LaunchedEffect(exoPlayer, state.selectedStream?.url, castState.isCasting) {
        while (true) {
            if (castState.isCasting) {
                positionMs = castState.positionMs.coerceAtLeast(0L)

                // Kept for when the session ends: stopCasting clears the mirrored clock, so
                // reading it after the fact would give zero.
                if (positionMs > 0) lastCastPositionMs = positionMs

                // The receiver's own duration is preferred but not always offered - some DLNA
                // renderers report zero - so the local player's is the fallback. It describes
                // the same stream either way.
                val remoteDuration = castState.durationMs
                if (remoteDuration > 0) {
                    durationMs = remoteDuration
                } else if (exoPlayer.duration > 0) {
                    durationMs = exoPlayer.duration
                }

                // Nothing is buffering locally, and leaving a stale value would draw a
                // buffered bar that has no meaning for the receiver.
                bufferedMs = 0L

                // Recorded even though the phone is not playing: the episode is being
                // watched, and stopping would lose the position and leave Continue Watching
                // pointing at wherever the cast happened to start.
                if (castState.isRemotePlaying) viewModel.onProgress(positionMs, durationMs)
            } else {
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                bufferedMs = exoPlayer.bufferedPosition.coerceAtLeast(0L)
                if (exoPlayer.duration > 0) durationMs = exoPlayer.duration
                if (isPlaying) viewModel.onProgress(positionMs, durationMs)
            }
            delay(500)
        }
    }

    // --- auto-hide controls
    //
    // Keyed on lastInteraction as well so navigating the controls keeps them up.
    // Directional keys are no longer consumed here - they move focus instead - so
    // without this the controls would hide three seconds in while the user was still
    // moving between buttons, taking the focused button with them.
    LaunchedEffect(controlsVisible, isPlaying, openPanel, lastInteraction) {
        if (controlsVisible && isPlaying && openPanel == PlayerPanel.NONE) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    // Back closes panels first, then leaves.
    //
    // The controls are only a Back layer on a television. There, they are the focus target -
    // dismissing them is a real action, and exiting playback because someone put the scrubber
    // away would be wrong. On a phone they are a transient overlay that a tap already toggles,
    // so treating them as a Back layer means the first press appears to do nothing and the
    // gesture has to be repeated to leave.
    //
    // Panels and the lock stay layered on both: those are things the user opened deliberately,
    // and dismissing them is what Back is for.
    //
    // This lives here rather than in mapPlayerKey because the manifest opts into
    // enableOnBackInvokedCallback, so on API 33+ the system consumes KEYCODE_BACK before the
    // view tree sees it and the DISMISS branch of the key mapping never runs.
    BackHandler {
        val controlsAreABackLayer = metricsForFocus.isFocusDriven && controlsVisible

        when {
            castPanelOpen -> castPanelOpen = false
            openPanel != PlayerPanel.NONE -> openPanel = PlayerPanel.NONE
            state.locked -> viewModel.setLocked(false)
            controlsAreABackLayer -> controlsVisible = false
            else -> {
                viewModel.flushProgress(exoPlayer.currentPosition, exoPlayer.duration)
                onBack()
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // Remote input, handled at the root.
            //
            // Compose routes a key event up the focused node's ancestors, so this has
            // to sit above both the video surface and the controls: once focus moves
            // onto a control button the surface is no longer an ancestor, and a handler
            // attached there would stop seeing media keys entirely.
            //
            // onKeyEvent rather than onPreviewKeyEvent so the focused control gets
            // first refusal - OK must activate the focused button, not be swallowed
            // here - and unhandled directions still reach the focus system.
            .onKeyEvent { event ->
                // KeyUp only. A held direction repeats KeyDown, which would seek
                // dozens of times from one press.
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false

                // Counted before the mapping, so a press that moves focus rather
                // than acting still keeps the controls on screen.
                lastInteraction++

                val action = mapPlayerKey(
                    keyCode = event.nativeKeyEvent.keyCode,
                    controlsVisible = controlsVisible,
                    panelOpen = openPanel != PlayerPanel.NONE,
                    isLocked = state.locked,
                )
                handlePlayerKey(action)
            },
    ) {
        val metrics = remember(maxWidth) { playerMetricsFor(maxWidth) }

        // --- video surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    playerView = this
                }
            },
            update = { view ->
                // Re-asserted here too: on configuration change Compose may reuse
                // the view while the effect above has not re-run yet.
                if (view.player !== exoPlayer) view.player = exoPlayer
                view.resizeMode = when (state.aspectMode) {
                    AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // --- gesture layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Remote input. Focusable so key events arrive at all, and focus is
                // claimed on entry: without it the first press is spent establishing
                // focus and the remote appears dead. Key handling itself lives on the
                // root, which stays an ancestor once focus moves onto a control.
                //
                // Focusable only while the controls are down: it covers the whole
                // window, so leaving it in the focus order traps directional movement
                // inside its bounds - see controlsOwnFocus.
                .focusRequester(playerFocusRequester)
                .focusable(enabled = !controlsOwnFocus)
                // Re-keyed on the player instance, not just the lock.
                //
                // PlayerFactory builds a new ExoPlayer whenever the stream's headers change, and
                // an episode switch brings freshly signed URLs - so the instance is replaced. A
                // detector keyed only on `locked` kept its original closure and went on seeking
                // the discarded player, which is why double-tap stopped working after changing
                // episode from the player's own episode list.
                //
                // durationMs is included for the same reason as the drag detector below: the
                // clamp inside transportSeekTo reads it.
                .pointerInput(state.locked, exoPlayer, durationMs) {
                    detectTapGestures(
                        onTap = {
                            if (state.locked) {
                                // Reveal the locked overlay briefly rather than
                                // toggling controls.
                                controlsVisible = false
                            } else {
                                controlsVisible = !controlsVisible
                            }
                        },
                        onDoubleTap = { offset ->
                            if (state.locked) return@detectTapGestures
                            val forward = offset.x > size.width / 2f
                            val delta = if (forward) 10_000L else -10_000L

                            transportSeekBy(delta)

                            seekTapAccumulatedMs =
                                accumulateSeekTap(seekTapAccumulatedMs, delta)
                            seekTapOnLeft = !forward
                            seekTapBump++
                        },
                    )
                }
                // One detector for both axes. Two separate pointerInput
                // modifiers would compete for the same pointer, so the axis is
                // decided once per drag and then locked: a seek never turns into
                // a volume change halfway through.
                .pointerInput(state.locked, durationMs) {
                    if (state.locked) return@pointerInput

                    val slop = viewConfiguration.touchSlop
                    val edgePx = SYSTEM_EDGE_EXCLUSION_DP.dp.toPx()

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)

                        var axis = DragAxis.UNDECIDED
                        var seekAccumulated = 0f
                        var totalDx = 0f
                        var totalDy = 0f

                        // Left half is brightness, right half is volume, matching
                        // the convention every other video player uses.
                        val isLeftHalf = down.position.x < size.width / 2f
                        val nearSystemEdge =
                            isInSystemEdgeZone(down.position.y, size.height, edgePx)

                        if (isLeftHalf) brightness.apply(brightness.currentOrDefault())
                        else volume.sync()

                        var level = if (isLeftHalf) brightness.currentOrDefault() else volume.level

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) break

                            val delta = change.positionChange()
                            totalDx += delta.x
                            totalDy += delta.y

                            if (axis == DragAxis.UNDECIDED) {
                                val moved = kotlin.math.hypot(totalDx, totalDy)
                                if (moved < slop) continue

                                axis = if (isVerticalDrag(totalDx, totalDy)) {
                                    // Drags beginning at the very top or bottom
                                    // belong to the system shade and nav gesture.
                                    if (nearSystemEdge) DragAxis.IGNORED else DragAxis.VERTICAL
                                } else {
                                    DragAxis.HORIZONTAL
                                }
                            }

                            when (axis) {
                                DragAxis.VERTICAL -> {
                                    change.consume()
                                    level = (level + verticalDragToDelta(delta.y, size.height))
                                        .coerceIn(0f, 1f)

                                    if (isLeftHalf) {
                                        brightness.apply(level)
                                        activeGesture = VerticalGesture.BRIGHTNESS
                                    } else {
                                        volume.apply(level)
                                        activeGesture = VerticalGesture.VOLUME
                                    }
                                    gestureLevel = level
                                }

                                DragAxis.HORIZONTAL -> {
                                    change.consume()
                                    seekAccumulated += delta.x

                                    // Full-width drag spans 60s/90s/120s by runtime.
                                    val window = when {
                                        durationMs >= 3_600_000L -> 120_000f
                                        durationMs >= 1_800_000L -> 90_000f
                                        else -> 60_000f
                                    }
                                    val seekDelta = (seekAccumulated / size.width) * window
                                    if (kotlin.math.abs(seekDelta) >= 1_000f) {
                                        transportSeekBy(seekDelta.toLong())
                                        seekAccumulated = 0f
                                    }
                                    controlsVisible = true
                                }

                                else -> Unit
                            }
                        }

                        activeGesture = VerticalGesture.NONE
                    }
                }
        )

        // Above the video, below the controls: cues must not be covered by the
        // scrubber, but must not sit above a dialog either.
        // How far the bottom-edge overlays clear the control stack.
        //
        // One value shared by the subtitles and the skip button, derived from the metrics
        // the controls are built from rather than hardcoded: the stack is the slider plus
        // its touch height, the timecode row and the pill row, all of which scale with
        // screen width. Animated so both rise and settle with the overlay instead of
        // jumping between two positions.
        val controlsUp = controlsVisible && !state.isResolving
        // The subtitles clear the same stack but need no button clearance, and they have
        // their own resting gap, so they animate on a slightly smaller value.
        val subtitleLift by animateDpAsState(
            targetValue = if (controlsUp) {
                metrics.sliderBottomOffset + metrics.sliderTouchHeight + CONTROL_STACK_HEIGHT
            } else {
                0.dp
            },
            animationSpec = tween(durationMillis = 220),
            label = "subtitle-lift",
        )

        val bottomOverlayLift by animateDpAsState(
            targetValue = if (controlsUp) {
                metrics.sliderBottomOffset +
                    metrics.sliderTouchHeight +
                    CONTROL_STACK_HEIGHT +
                    SKIP_CLEARANCE
            } else {
                metrics.verticalPadding
            },
            animationSpec = tween(durationMillis = 220),
            label = "bottom-overlay-lift",
        )

        ComposeSubtitleView(
            player = exoPlayer,
            style = subtitleStyle,
            offsetCues = state.offsetCues,
            offsetMs = state.subtitleOffsetMs,
            positionMs = positionMs,
            // Lifted clear of the controls so dialogue is not hidden behind the scrubber
            // while they are on screen.
            //
            // Uses the stack height without the skip button's extra clearance, and drops
            // the resting inset entirely: the subtitle view already carries a 32dp gap of
            // its own, and stacking all three pushed cues into the middle of the picture
            // on a phone.
            bottomInset = if (controlsUp) subtitleLift else 0.dp,
            modifier = Modifier.fillMaxSize(),
        )

        // --- states
        when {
            state.isResolving -> WbLoading()

            state.errorMessage != null && state.selectedStream == null -> WbEmptyState(
                title = stringResource(R.string.error_no_source),
                body = state.errorMessage,
                actionLabel = stringResource(R.string.action_retry),
                onAction = viewModel::retry,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // --- casting overlay
        //
        // Declared before the controls so they paint over it: in a Box, later children are drawn
        // on top. The overlay's own centred block hides while the controls are up, so the two do
        // not compete for the middle of the screen even though both are centred.
        CastingOverlay(
            isCasting = castState.isCasting,
            controlsVisible = controlsVisible && !state.locked && !state.isResolving,
            deviceName = castState.deviceName,
            title = state.title,
        )

        // --- skip intro / outro
        //
        // Outside the controls overlay on purpose: the controls auto-hide after three seconds and
        // an opening runs for ninety, so a button inside them would vanish for most of its window.
        // Position-driven, so it appears and leaves on its own.
        //
        // Shown while casting too. The earlier objection - that the local clock is not what the
        // receiver plays - no longer applies: the ticker feeds positionMs from
        // castState.positionMs during a session, so the button already tracks the receiver, and
        // transportSeekTo already routes the seek to it.
        //
        // Still hidden while locked, where input is deliberately ignored.
        if (!state.locked) {
            // Shares bottomOverlayLift with the subtitles, so the two never disagree about
            // where the control stack ends.
            SkipSegmentButton(
                intervals = state.skipIntervals,
                positionMs = positionMs,
                onSkip = { target -> transportSeekTo(target) },
                // On TV, and only while the controls are down: the button is then the one
                // actionable thing on screen, so OK should skip without the viewer having
                // to aim at it. With the controls up the play button owns focus, and taking
                // it would move the selection out from under a press in progress.
                autoFocus = skipButtonOwnsFocus,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = metrics.horizontalPadding, bottom = bottomOverlayLift),
            )
        }

        // --- double-tap seek readout
        //
        // On the side that was tapped, so the feedback appears under the finger that caused it.
        // Declared before the controls so they paint over it, and outside the controls overlay so
        // it works whether or not the controls happen to be up - a double-tap does not reveal
        // them, which is exactly when the picture alone gives no confirmation.
        SeekTapIndicator(
            accumulatedMs = seekTapAccumulatedMs,
            positionMs = positionMs,
            modifier = Modifier
                .align(if (seekTapOnLeft) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 40.dp),
        )

        GestureLevelIndicator(
            gesture = activeGesture,
            level = gestureLevel,
            modifier = Modifier.align(Alignment.Center),
        )

        // --- controls
        AnimatedVisibility(
            visible = controlsVisible && !state.locked && !state.isResolving,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            PlayerControlsOverlay(
                state = state,
                metrics = metrics,
                // The receiver's state while casting. The local player is paused then, so the
                // button would otherwise offer "play" for something already playing on the
                // television - and pressing it would pause the receiver, the opposite of what
                // the icon promised.
                isPlaying = transportIsPlaying(),
                // Nothing buffers locally while casting, and a spinner over a picture that is
                // playing elsewhere reads as a fault.
                isBuffering = isBuffering && !castState.isCasting,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                onPlayPause = transportTogglePlay,
                onSeek = transportSeekTo,
                onSeekBy = transportSeekBy,
                onBack = {
                    viewModel.flushProgress(exoPlayer.currentPosition, exoPlayer.duration)
                    onBack()
                },
                onToggleLock = { viewModel.setLocked(true) },
                onCycleAspect = viewModel::cycleAspect,
                onOpenPanel = { openPanel = it },
                isCasting = castState.isCasting,
                castDeviceName = castState.deviceName,
                playFocusRequester = playFocusRequester,
                onPlayFocusChanged = { playHasFocus = it },
                onOpenCast = {
                    castPanelOpen = true
                    // Requested here rather than at startup: it is only needed for
                    // discovery, and asking before the user shows any interest in
                    // casting is the kind of prompt people reflexively deny.
                    CastPermissions.required
                        ?.takeIf { !CastPermissions.isGranted(context) }
                        ?.let(nearbyPermission::launch)

                    // Rescan on open: renderers come and go, so a list cached
                    // from a previous session is usually stale.
                    castManager.discover()
                },
            )
        }

        AnimatedVisibility(
            visible = state.locked,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            LockedPlayerOverlay(onUnlock = { viewModel.setLocked(false) })
        }

        // --- side panels
        CastPanel(
            state = castState,
            visible = castPanelOpen,
            onSelectDevice = { device ->
                castManager.castTo(
                    device = device,
                    // DLNA renderers get a progressive stream where one exists. Chromecast
                    // decodes HLS itself, so it is left on whatever is playing locally.
                    media = state.toCastMedia(
                        runtimeMs = durationMs,
                        preferProgressive = device.protocol == CastProtocol.DLNA,
                    ),
                    positionMs = exoPlayer.currentPosition,
                )
                // Silenced now rather than when the session reports itself connected, which
                // can take several seconds - the phone should go quiet the moment the user
                // picks a device, not once the handshake finishes.
                exoPlayer.pause()
                castPanelOpen = false
            },
            onSendToExternal = {
                val media = state.toCastMedia(durationMs)
                ExternalCast.sendToWebVideoCaster(
                    context = context,
                    url = media.url,
                    headers = media.headers,
                    title = media.title,
                )
                castPanelOpen = false
            },
            onCastToChromecast = {
                castManager.castToConnectedChromecast(
                    media = state.toCastMedia(durationMs),
                    positionMs = exoPlayer.currentPosition,
                )
                exoPlayer.pause()
                castPanelOpen = false
            },
            onStopCasting = {
                castManager.stopCasting()
                castPanelOpen = false
            },
            onRescan = castManager::discover,
            onSetForceProxy = { enabled ->
                // Applied now and remembered for next time. Both are needed: the manager holds
                // the value the next cast reads, the store makes it survive a restart.
                castManager.setForceProxy(enabled)
                viewModel.setCastForceProxy(enabled)
            },
            onDismiss = { castPanelOpen = false },
        )

        PlayerPanels(
            panel = openPanel,
            state = state,
            onDismiss = { openPanel = PlayerPanel.NONE },
            onSelectStream = { stream: StreamOption ->
                viewModel.selectStream(stream)
                openPanel = PlayerPanel.NONE
            },
            onSelectSubtitle = { index ->
                viewModel.selectSubtitle(index)

                // Forwarded to the receiver as well while casting. The local player's track
                // selection is invisible to it - the receiver holds its own copy of the tracks -
                // so without this, changing subtitles mid-session changed nothing on the TV.
                //
                // Identified by URL rather than index: subtitles that could not be published are
                // dropped on the way to the receiver, so the two lists do not line up.
                if (castState.isCasting) {
                    if (castState.protocol == CastProtocol.DLNA) {
                        // DLNA fixes its subtitle in the DIDL metadata at load time, so there is
                        // no track to switch - the media has to be set again with the new choice.
                        castManager.reloadCurrent(
                            state.toCastMedia(runtimeMs = durationMs, preferProgressive = true),
                        )
                    } else {
                        castManager.setSubtitleTrack(state.subtitles.getOrNull(index)?.url)
                    }
                }

                openPanel = PlayerPanel.NONE
            },
            onSelectSpeed = {
                viewModel.setSpeed(it)
                openPanel = PlayerPanel.NONE
            },
            onSelectEpisode = { episode: EpisodeEntry ->
                viewModel.goToEpisode(episode)
                openPanel = PlayerPanel.NONE
            },
            onOpenSubtitleSettings = { openPanel = PlayerPanel.SUBTITLE_STYLE },
            onSearchSubtitles = {
                // Opened before the search starts so the panel can show it running, rather
                // than the user pressing a row and seeing nothing happen for a second.
                openPanel = PlayerPanel.SUBTITLE_SEARCH
                viewModel.searchSubtitles()
            },
            onApplySubtitle = { result ->
                viewModel.applySubtitle(result)
                // Left open: the download can fail, and this panel is where that is reported.
                // It closes itself when the subtitle is playing.
            },
            subtitleStyle = subtitleStyle,
            // The panel stays open after each change so the effect can be seen on
            // the video behind it and adjusted again without reopening.
            onSetSubtitleSize = onSetSubtitleSize,
            onSetSubtitleBackground = onSetSubtitleBackground,
            onSetSubtitleEdgeWidth = onSetSubtitleEdgeWidth,
            onSetSubtitleColor = onSetSubtitleColor,
            // Marks are taken against the transport position, so a measurement made while
            // casting is measured against what the receiver is actually showing.
            onMarkSync = { mark -> viewModel.markSync(mark, transportPosition()) },
            onCancelSync = viewModel::cancelSync,
            onNudgeSubtitleOffset = viewModel::nudgeSubtitleOffset,
            onResetSubtitleOffset = { viewModel.setSubtitleOffset(0L) },
            onOpenSubtitleSync = { openPanel = PlayerPanel.SUBTITLE_SYNC },
        )
    }
}

/**
 * Picks which stream to hand a receiver.
 *
 * [preferProgressive] is set for DLNA, which is effectively HLS-blind: a television handed an
 * `.m3u8` answers "file not supported", naming neither the format nor the app's part in it.
 * A plain-file stream is chosen instead when the source published one.
 *
 * Falls back to [selected] when every stream is HLS, so casting is attempted rather than
 * silently refused - a handful of renderers do manage it, and the receiver's own complaint is
 * more use than a button that does nothing.
 *
 * Top-level so the choice can be tested without a player: it is the difference between a
 * working cast and an error message with no explanation.
 */
internal fun castStreamFor(
    streams: List<StreamOption>,
    selected: StreamOption?,
    preferProgressive: Boolean,
): StreamOption? {
    if (!preferProgressive) return selected

    // Keeps the current stream when it already is progressive, so the quality the user chose
    // is not swapped for a different one unnecessarily.
    if (selected != null && !selected.isHls) return selected

    return streams.firstOrNull { !it.isHls } ?: selected
}

/**
 * Builds the payload for a receiver from the current playback state.
 *
 * The upstream headers are carried through deliberately: [CastManager] uses them
 * to decide whether the stream has to be relayed through the local proxy, since a
 * receiver cannot send a `Referer` itself.
 *
 * [preferProgressive] swaps an HLS stream for a plain-file one when the source offers both.
 * DLNA renderers are the reason: nearly none decode HLS, and a television handed an
 * `.m3u8` reports "file not supported" with nothing to say which part failed. Chromecast
 * handles HLS natively, so it keeps whatever the player is using.
 */
private fun PlayerUiState.toCastMedia(
    runtimeMs: Long = 0L,
    preferProgressive: Boolean = false,
): CastMedia {
    val stream = castStreamFor(streams, selectedStream, preferProgressive)

    return CastMedia(
        url = stream?.url.orEmpty(),
        headers = stream?.headers.orEmpty(),
        mimeType = when {
            stream?.isHls == true -> "application/vnd.apple.mpegurl"
            else -> "video/mp4"
        },
        title = title,
        subtitle = episodeLabel,
        artworkUrl = detail?.posterUrl,
        // The runtime the local player worked out, not zero. DLNA builds its metadata from
        // this, and a renderer told a stream is zero-length can refuse to seek in it at all.
        durationMs = runtimeMs.coerceAtLeast(0L),
        // Downloaded subtitles are cast too, marked so CastManager can republish them.
        //
        // They were previously dropped, on the reasoning that a receiver cannot open a file://
        // path and would ignore SubRip anyway. Both are true and neither is a reason to give up:
        // the proxy already serves the video from this device, so it can serve the subtitle file
        // as well, and converting SubRip to WebVTT on the way out is a small transformation.
        subtitles = subtitles.map {
            CastSubtitle(
                url = it.url,
                label = it.label,
                language = it.language,
                isLocalFile = it.url.startsWith("file:"),
            )
        },
        isMovie = episodes.size <= 1,
        selectedSubtitleIndex = selectedSubtitleIndex,
    )
}

/** Which axis a drag was locked to, decided once per gesture. */
private enum class DragAxis { UNDECIDED, HORIZONTAL, VERTICAL, IGNORED }

/**
 * Height at each end of the screen where vertical drags are left to the system.
 *
 * The top is where the notification shade is pulled from and the bottom is the
 * navigation-gesture area; claiming those makes the player fight the system UI.
 */
private const val SYSTEM_EDGE_EXCLUSION_DP = 48
