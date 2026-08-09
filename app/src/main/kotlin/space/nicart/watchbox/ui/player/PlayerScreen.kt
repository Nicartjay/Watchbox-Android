package space.nicart.watchbox.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
private const val TAG = "WbPlayer"

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

    // Local playback must stop while casting or audio plays from both the phone
    // and the TV; it resumes when the session ends.
    LaunchedEffect(castState.isCasting) {
        if (castState.isCasting) exoPlayer.pause()
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
    var castPanelOpen by remember { mutableStateOf(false) }

    // Brightness is window-scoped, so it is tied to this activity; volume is a
    // system stream and re-synced whenever a gesture starts.
    val brightness = remember(activityForBrightness) {
        BrightnessController(activityForBrightness)
    }
    val volume = remember { VolumeController(context) }
    var activeGesture by remember { mutableStateOf(VerticalGesture.NONE) }
    var gestureLevel by remember { mutableFloatStateOf(0f) }
    var playbackError by remember { mutableStateOf<String?>(null) }

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
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    exoPlayer.pause()
                    viewModel.flushProgress(exoPlayer.currentPosition, exoPlayer.duration)
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // --- load / swap media whenever the selected stream changes
    LaunchedEffect(state.selectedStream?.url) {
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
        exoPlayer.playWhenReady = true
    }

    // --- speed
    LaunchedEffect(state.speed) { exoPlayer.setPlaybackSpeed(state.speed) }

    // --- subtitle selection
    LaunchedEffect(state.selectedSubtitleIndex, state.selectedStream?.url) {
        val subtitle = state.subtitles.getOrNull(state.selectedSubtitleIndex)
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(
                androidx.media3.common.C.TRACK_TYPE_TEXT,
                subtitle == null,
            )
            .apply {
                subtitle?.language?.takeIf { it.isNotBlank() }?.let(::setPreferredTextLanguage)
            }
            .build()
    }

    // --- position ticker + throttled history writes
    LaunchedEffect(exoPlayer, state.selectedStream?.url) {
        while (true) {
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            bufferedMs = exoPlayer.bufferedPosition.coerceAtLeast(0L)
            if (exoPlayer.duration > 0) durationMs = exoPlayer.duration
            if (isPlaying) viewModel.onProgress(positionMs, durationMs)
            delay(500)
        }
    }

    // --- auto-hide controls
    LaunchedEffect(controlsVisible, isPlaying, openPanel) {
        if (controlsVisible && isPlaying && openPanel == PlayerPanel.NONE) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    BackHandler {
        when {
            castPanelOpen -> castPanelOpen = false
            openPanel != PlayerPanel.NONE -> openPanel = PlayerPanel.NONE
            state.locked -> viewModel.setLocked(false)
            else -> {
                viewModel.flushProgress(exoPlayer.currentPosition, exoPlayer.duration)
                onBack()
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
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
                .pointerInput(state.locked) {
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
                            exoPlayer.seekTo(
                                (exoPlayer.currentPosition + if (forward) 10_000L else -10_000L)
                                    .coerceIn(0L, exoPlayer.duration.coerceAtLeast(0L)),
                            )
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
                                        exoPlayer.seekTo(
                                            (exoPlayer.currentPosition + seekDelta.toLong())
                                                .coerceIn(0L, durationMs.coerceAtLeast(0L)),
                                        )
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
        ComposeSubtitleView(
            player = exoPlayer,
            style = subtitleStyle,
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
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                onPlayPause = {
                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                },
                onSeek = { exoPlayer.seekTo(it) },
                onSeekBy = { delta ->
                    exoPlayer.seekTo(
                        (exoPlayer.currentPosition + delta)
                            .coerceIn(0L, durationMs.coerceAtLeast(0L)),
                    )
                },
                onBack = {
                    viewModel.flushProgress(exoPlayer.currentPosition, exoPlayer.duration)
                    onBack()
                },
                onToggleLock = { viewModel.setLocked(true) },
                onCycleAspect = viewModel::cycleAspect,
                onOpenPanel = { openPanel = it },
                isCasting = castState.isCasting,
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
                    media = state.toCastMedia(),
                    positionMs = exoPlayer.currentPosition,
                )
                exoPlayer.pause()
                castPanelOpen = false
            },
            onSendToExternal = {
                val media = state.toCastMedia()
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
                    media = state.toCastMedia(),
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
            onSelectSubtitle = {
                viewModel.selectSubtitle(it)
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
            subtitleStyle = subtitleStyle,
            // The panel stays open after each change so the effect can be seen on
            // the video behind it and adjusted again without reopening.
            onSetSubtitleSize = onSetSubtitleSize,
            onSetSubtitleBackground = onSetSubtitleBackground,
            onSetSubtitleEdgeWidth = onSetSubtitleEdgeWidth,
            onSetSubtitleColor = onSetSubtitleColor,
        )
    }
}

/**
 * Builds the payload for a receiver from the current playback state.
 *
 * The upstream headers are carried through deliberately: [CastManager] uses them
 * to decide whether the stream has to be relayed through the local proxy, since a
 * receiver cannot send a `Referer` itself.
 */
private fun PlayerUiState.toCastMedia(): CastMedia {
    val stream = selectedStream
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
        durationMs = 0L,
        subtitles = subtitles.map {
            CastSubtitle(url = it.url, label = it.label, language = it.language)
        },
        isMovie = episodes.size <= 1,
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
