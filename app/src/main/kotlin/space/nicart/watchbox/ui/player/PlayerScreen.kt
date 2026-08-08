package space.nicart.watchbox.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import space.nicart.watchbox.R
import space.nicart.watchbox.domain.PlayableStream
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

@UnstableApi
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
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

    val exoPlayer = remember { PlayerFactory.create(context) }

    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var openPanel by remember { mutableStateOf(PlayerPanel.NONE) }
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
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
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
    LaunchedEffect(state.selectedStream?.url, state.source?.subtitles) {
        val stream = state.selectedStream ?: return@LaunchedEffect
        val resumeFrom = if (positionMs > 0) positionMs else state.resumeMs

        exoPlayer.setMediaItem(
            PlayerFactory.buildMediaItem(
                stream = stream,
                subtitles = state.source?.subtitles.orEmpty(),
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
    LaunchedEffect(state.selectedSubtitleIndex, state.source) {
        val subtitle = state.source?.subtitles?.getOrNull(state.selectedSubtitleIndex)
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
                    player = exoPlayer
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
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
                .pointerInput(state.locked, durationMs) {
                    if (state.locked) return@pointerInput
                    var accumulated = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { accumulated = 0f },
                        onDragEnd = { accumulated = 0f },
                    ) { _, delta ->
                        accumulated += delta
                        // Full-width drag = 60s / 90s / 120s depending on runtime.
                        val window = when {
                            durationMs >= 3_600_000L -> 120_000f
                            durationMs >= 1_800_000L -> 90_000f
                            else -> 60_000f
                        }
                        val seekDelta = (accumulated / size.width) * window
                        if (kotlin.math.abs(seekDelta) >= 1_000f) {
                            exoPlayer.seekTo(
                                (exoPlayer.currentPosition + seekDelta.toLong())
                                    .coerceIn(0L, durationMs.coerceAtLeast(0L)),
                            )
                            accumulated = 0f
                        }
                        controlsVisible = true
                    }
                },
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
            )
        }

        AnimatedVisibility(
            visible = state.locked,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            LockedPlayerOverlay(onUnlock = { viewModel.setLocked(false) })
        }

        // --- skip intro / outro
        SkipSegmentButton(
            state = state,
            positionMs = positionMs,
            onSkip = { targetMs -> exoPlayer.seekTo(targetMs) },
            modifier = Modifier.align(Alignment.BottomStart),
        )

        // --- side panels
        PlayerPanels(
            panel = openPanel,
            state = state,
            onDismiss = { openPanel = PlayerPanel.NONE },
            onSelectStream = { stream: PlayableStream ->
                viewModel.selectStream(stream)
                openPanel = PlayerPanel.NONE
            },
            onSelectSubtitle = {
                viewModel.selectSubtitle(it)
                openPanel = PlayerPanel.NONE
            },
            onSelectAudio = {
                viewModel.selectAudio(it)
                openPanel = PlayerPanel.NONE
            },
            onSelectHost = {
                viewModel.selectHost(it)
                openPanel = PlayerPanel.NONE
            },
            onSelectSpeed = {
                viewModel.setSpeed(it)
                openPanel = PlayerPanel.NONE
            },
            onSelectEpisode = {
                viewModel.goToEpisode(it)
                openPanel = PlayerPanel.NONE
            },
        )
    }
}
