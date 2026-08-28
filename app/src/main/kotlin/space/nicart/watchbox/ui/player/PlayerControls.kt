package space.nicart.watchbox.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.LocalLayoutMetrics
import space.nicart.watchbox.core.ui.adaptiveFocus
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.core.ui.wbType
import space.nicart.watchbox.domain.formatTimecode

/**
 * Player control overlay.
 *
 * Ported from NuvioMobile `features/player/PlayerControls.kt` +
 * `PlayerLayout.kt`. Phone metrics:
 *   horizontal padding 20dp, top scrim 160dp, bottom scrim 220dp,
 *   centre gap 56dp, slider bottom offset 16dp / touch height 22dp / scaleY 0.82,
 *   header icons 20dp in 36dp circles, play icon 34dp.
 */
data class PlayerMetrics(
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val centerGap: Dp,
    val centerLift: Dp,
    val sliderBottomOffset: Dp,
    val sliderTouchHeight: Dp,
    val sliderScaleY: Float,
    val headerIconSize: Dp,
    val sideIconSize: Dp,
    val playIconSize: Dp,
)

fun playerMetricsFor(width: Dp): PlayerMetrics = when {
    width >= 1440.dp -> PlayerMetrics(
        28.dp, 24.dp, 112.dp, 10.dp, 28.dp, 28.dp, 0.72f, 24.dp, 34.dp, 44.dp,
    )

    width >= 1024.dp -> PlayerMetrics(
        24.dp, 20.dp, 88.dp, 10.dp, 24.dp, 26.dp, 0.74f, 22.dp, 32.dp, 42.dp,
    )

    width >= 768.dp -> PlayerMetrics(
        20.dp, 16.dp, 72.dp, 10.dp, 20.dp, 24.dp, 0.78f, 20.dp, 30.dp, 38.dp,
    )

    else -> PlayerMetrics(
        20.dp, 16.dp, 56.dp, 10.dp, 16.dp, 22.dp, 0.82f, 20.dp, 26.dp, 34.dp,
    )
}

/**
 * Which sheet is open, if any.
 *
 * The server and audio panels do not use an extension API for either - lib-14
 * exposes no host list and no audio-track selection. Both are derived from the
 * stream labels the source already returns, so they switch streams rather than
 * tracks. See [StreamFacets].
 */
enum class PlayerPanel {
    NONE,

    /**
     * Which server to play from.
     *
     * A source returns one flat list of streams that differ by server, resolution
     * and audio track all at once - thirty-odd entries for a well-served film. The
     * three are split into their own panels so each list is short enough to scan,
     * which matters most on a remote. See [StreamFacets].
     */
    SERVER,
    QUALITY,

    /**
     * Which audio track, where the server offers a choice.
     *
     * Not an ExoPlayer track selection: these are separate streams the source
     * happens to label by language, so switching is a stream switch. Servers with
     * one track label no audio at all, and the button is left out for them.
     */
    DUB,
    SUBTITLES,

    /**
     * Subtitle appearance, opened from the subtitle panel.
     *
     * A panel rather than navigation to Settings: leaving the player tears down the
     * surface and loses the position, and appearance is exactly the setting you want
     * to adjust against the video you are watching.
     */
    SUBTITLE_STYLE,

    /**
     * Online subtitle search, opened from the subtitle panel.
     *
     * Separate from [SUBTITLES] because it is a list of candidates to download rather than
     * tracks to switch between: mixing fetchable results into the selectable ones would make
     * it unclear which rows are already playing and which cost a download.
     */
    SUBTITLE_SEARCH,

    /**
     * Subtitle timing correction, opened from the subtitle panel.
     *
     * Its own panel because it has to be used *while* watching: the two-tap measurement
     * needs the video running to mark when a line appears and when it is spoken, so it
     * cannot live in Settings.
     */
    SUBTITLE_SYNC,
    SPEED,
    EPISODES,
}

@Composable
fun PlayerControlsOverlay(
    state: PlayerUiState,
    metrics: PlayerMetrics,
    isPlaying: Boolean,
    isBuffering: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    /** Advances to the next episode. Null on the last one, and on a film. */
    onNextEpisode: (() -> Unit)? = null,
    onBack: () -> Unit,
    onToggleLock: () -> Unit,
    onCycleAspect: () -> Unit,
    onOpenPanel: (PlayerPanel) -> Unit,
    isCasting: Boolean = false,
    castDeviceName: String? = null,
    onOpenCast: () -> Unit = {},
    /**
     * Hides the centre transport row.
     *
     * Set while a playback error is on screen: that message is centred too, so
     * the play button and the seek arrows would sit on top of it. None of the
     * three does anything useful for a stream that failed to load, so the row
     * gives way rather than being rearranged around the message.
     */
    hideTransport: Boolean = false,
    /**
     * Claims focus for the play button when the controls appear.
     *
     * Supplied by the caller because the controls are wrapped in an
     * `AnimatedVisibility` that composes them afresh on every reveal, so the request
     * has to be re-made from outside rather than once on first composition.
     */
    playFocusRequester: FocusRequester? = null,
    /** Reports the play button's focus, so the caller's retry can stop guessing. */
    onPlayFocusChanged: (Boolean) -> Unit = {},
    /**
     * How many audio tracks the stream carries, used only to decide whether the audio
     * pill is worth showing.
     */
    audioTrackCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // --- scrims
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(160.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(220.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.horizontalPadding),
        ) {
            PlayerHeader(
                state = state,
                metrics = metrics,
                onBack = onBack,
                onToggleLock = onToggleLock,
                isCasting = isCasting,
                castDeviceName = castDeviceName,
                onOpenCast = onOpenCast,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = metrics.verticalPadding / 4),
            )

            if (!hideTransport) {
                CenterControls(
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    metrics = metrics,
                    onPlayPause = onPlayPause,
                    onSeekBy = onSeekBy,
                    onNextEpisode = onNextEpisode,
                    playFocusRequester = playFocusRequester,
                    onPlayFocusChanged = onPlayFocusChanged,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = metrics.centerLift),
                )
            }

            ProgressControls(
                state = state,
                metrics = metrics,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                onSeek = onSeek,
                onCycleAspect = onCycleAspect,
                onOpenPanel = onOpenPanel,
                audioTrackCount = audioTrackCount,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = metrics.sliderBottomOffset),
            )
        }
    }
}

@Composable
private fun PlayerHeader(
    state: PlayerUiState,
    metrics: PlayerMetrics,
    onBack: () -> Unit,
    onToggleLock: () -> Unit,
    isCasting: Boolean,
    castDeviceName: String?,
    onOpenCast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = MaterialTheme.wbType

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = state.title,
                style = type.titleLg.copy(fontSize = type.titleSm.fontSize),
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            state.episodeLabel?.let {
                Text(
                    text = it,
                    style = type.bodyMd,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Inside the header's Column, so it is laid out with the title rather than floated
            // over the screen. The overlay's centred block hides while the controls are up, so
            // this is what keeps the receiver named during scrubbing - and being part of the
            // normal layout, it cannot collide with anything.
            if (isCasting && !castDeviceName.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CastConnected,
                        contentDescription = null,
                        tint = MaterialTheme.wb.colors.accent,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = stringResource(R.string.cast_now_casting, castDeviceName),
                        style = type.labelSm,
                        color = MaterialTheme.wb.colors.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            state.detail?.sourceName?.let { server ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = server,
                        style = type.labelSm,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    state.selectedStream?.label?.let { quality ->
                        Text(
                            text = quality,
                            style = type.labelSm,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CastButton(
                isCasting = isCasting,
                size = metrics.headerIconSize,
                onClick = onOpenCast,
            )
            // Lock is a touch-only affordance: it exists to stop a pocketed phone
            // from scrubbing. A remote cannot generate stray input, and on TV the
            // button is actively harmful -- D-pad RIGHT along the header lands on
            // it, and once locked every key except unlock is swallowed
            // (`PlayerKeys.kt:60`), so the user appears to be stuck.
            if (!LocalLayoutMetrics.current.isFocusDriven) {
                HeaderIconButton(
                    icon = if (state.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    size = metrics.headerIconSize,
                    onClick = onToggleLock,
                )
            }
            HeaderIconButton(
                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                size = metrics.headerIconSize,
                onClick = {},
                enabled = false,
            )
            HeaderIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                size = metrics.headerIconSize,
                onClick = onBack,
            )
        }
    }
}

@Composable
private fun HeaderIconButton(
    icon: ImageVector,
    size: Dp,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    if (!enabled) return
    val interaction = rememberFocusInteraction()
    Box(
        modifier = Modifier
            .size(size + 16.dp)
            .adaptiveFocus(interaction, CircleShape)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size),
        )
    }
}

@Composable
private fun CenterControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    metrics: PlayerMetrics,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    /** Null on the last episode, and on a film, where there is nothing to advance to. */
    onNextEpisode: (() -> Unit)? = null,
    playFocusRequester: FocusRequester? = null,
    onPlayFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(metrics.centerGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val backInteraction = rememberFocusInteraction()
        val playInteraction = rememberFocusInteraction()
        val forwardInteraction = rememberFocusInteraction()
        val nextInteraction = rememberFocusInteraction()

        Box(
            modifier = Modifier
                .adaptiveFocus(backInteraction, CircleShape)
                .clip(CircleShape)
                .clickable(
                    interactionSource = backInteraction,
                    indication = LocalIndication.current,
                ) { onSeekBy(-10_000L) }
                .padding(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Replay10,
                contentDescription = "Back 10 seconds",
                tint = Color.White,
                modifier = Modifier.size(metrics.playIconSize),
            )
        }

        Box(
            modifier = Modifier
                .clip(CircleShape)
                .then(
                    playFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
                )
                .onFocusChanged { onPlayFocusChanged(it.isFocused) }
                .adaptiveFocus(playInteraction, CircleShape)
                .clickable(
                    interactionSource = playInteraction,
                    indication = LocalIndication.current,
                    enabled = !isBuffering,
                    onClick = onPlayPause,
                )
                .padding(13.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isBuffering) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(metrics.playIconSize),
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(metrics.playIconSize),
                )
            }
        }

        Box(
            modifier = Modifier
                .adaptiveFocus(forwardInteraction, CircleShape)
                .clip(CircleShape)
                .clickable(
                    interactionSource = forwardInteraction,
                    indication = LocalIndication.current,
                ) { onSeekBy(10_000L) }
                .padding(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Forward10,
                contentDescription = "Forward 10 seconds",
                tint = Color.White,
                modifier = Modifier.size(metrics.playIconSize),
            )
        }

        // Last in the row, and absent rather than disabled on the final episode: a dead control
        // on a remote is a focus stop that does nothing when pressed.
        if (onNextEpisode != null) {
            Box(
                modifier = Modifier
                    .adaptiveFocus(nextInteraction, CircleShape)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = nextInteraction,
                        indication = LocalIndication.current,
                        onClick = onNextEpisode,
                    )
                    .padding(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "Next episode",
                    tint = Color.White,
                    modifier = Modifier.size(metrics.playIconSize),
                )
            }
        }
    }
}

@Composable
private fun ProgressControls(
    state: PlayerUiState,
    metrics: PlayerMetrics,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onSeek: (Long) -> Unit,
    onCycleAspect: () -> Unit,
    onOpenPanel: (PlayerPanel) -> Unit,
    /** Audio tracks inside the stream; see [PlayerControlsOverlay]. */
    audioTrackCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    val isFocusDriven = LocalLayoutMetrics.current.isFocusDriven
    val sliderInteraction = rememberFocusInteraction()

    Column(modifier = modifier) {
        // Skip segments marked on the timeline, drawn behind the slider.
        //
        // A Box rather than a custom slider: Material3's Slider has no marker API, and replacing
        // it would mean reimplementing its focus handling, D-pad keys and touch semantics - all of
        // which took work to get right. Drawing underneath keeps the slider exactly as it is.
        Box(modifier = Modifier.fillMaxWidth()) {
            SkipSegmentMarkers(
                intervals = state.skipIntervals,
                durationMs = durationMs,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.sliderTouchHeight)
                    // Matches the slider's own inset so a marker lines up with the position the
                    // thumb reports. Material's slider reserves half a thumb at each end.
                    .padding(horizontal = SLIDER_THUMB_INSET),
            )

        Slider(
            value = if (durationMs > 0) {
                (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            } else {
                0f
            },
            onValueChange = { fraction ->
                if (durationMs > 0) onSeek((fraction * durationMs).toLong())
            },
            interactionSource = sliderInteraction,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.30f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(metrics.sliderTouchHeight)
                // Focusable and D-pad driven on a remote. Material3's Slider is built for a
                // pointer: it is not focusable and ignores key events, so on a television the
                // timeline could be seen but never moved - the only way to reach a position was
                // the ±10s buttons, ten seconds at a time.
                //
                // Left/Right are consumed here rather than mapped in mapPlayerKey because they
                // must only seek while this row holds focus; consumed globally they would stop
                // focus ever moving between the buttons, which is the bug the key mapping was
                // rewritten to fix.
                .then(
                    if (isFocusDriven) {
                        Modifier
                            .adaptiveFocus(sliderInteraction, RoundedCornerShape(4.dp), scale = false)
                            .focusable(interactionSource = sliderInteraction)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) {
                                    return@onPreviewKeyEvent false
                                }
                                when (event.key) {
                                    Key.DirectionLeft -> {
                                        onSeek(
                                            (positionMs - SLIDER_SEEK_STEP_MS)
                                                .coerceAtLeast(0L),
                                        )
                                        true
                                    }

                                    Key.DirectionRight -> {
                                        // Clamped to the duration so a press at the end does
                                        // not ask for a position past it.
                                        onSeek(
                                            (positionMs + SLIDER_SEEK_STEP_MS)
                                                .coerceAtMost(durationMs.coerceAtLeast(0L)),
                                        )
                                        true
                                    }

                                    // Everything else falls through, so Up and Down still move
                                    // focus out of the scrubber to the surrounding controls.
                                    else -> false
                                }
                            }
                    } else {
                        Modifier
                    },
                )
                .graphicsLayer { scaleY = metrics.sliderScaleY },
        )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TimePill(formatTimecode(positionMs))
            TimePill(formatTimecode(durationMs))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Color.White.copy(alpha = 0.2f),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionPill(
                        icon = Icons.Rounded.AspectRatio,
                        label = state.aspectMode.label,
                        onClick = onCycleAspect,
                    )
                    ActionPill(
                        icon = Icons.Rounded.Speed,
                        label = "${state.speed.formatSpeed()}x",
                        onClick = { onOpenPanel(PlayerPanel.SPEED) },
                    )
                    // Server, quality and audio, split out of what used to be a
                    // single pill showing the whole stream label. That label runs to
                    // "Art/4k-Hub · 2160p · BluRay · HEVC · 66.39 GB · MKV", which
                    // neither fits the row nor says which part is selectable.
                    //
                    // Each pill appears only when there is a choice to make: one
                    // server means no server pill, and audio needs either several
                    // labelled streams or several tracks in the file. A dual-audio
                    // MKV from a source offering one stream is the second case, and
                    // counting dubs alone left its tracks unreachable.
                    val facets = state.selectedStream?.facets
                    val servers = state.streams.serverOptions()
                    // Counted as rows, not as distinct resolutions: a server
                    // offering several releases at one height has a row for each,
                    // and hiding the pill because they agree on the number left
                    // all but the first unreachable.
                    val qualities = state.streams.qualityChoices(facets?.server, facets?.dub)
                    val dubs = state.streams.dubOptions(facets?.server)

                    // Shown while a progressive source is still reporting backends, so a
                    // server list that is about to grow does not look complete. The pill is
                    // offered even for a single server in that case: a second one may be
                    // seconds away, and hiding the way in until it arrives means the control
                    // appears from nowhere mid-playback.
                    if (servers.size > 1 || (state.isLoadingMoreStreams && servers.isNotEmpty())) {
                        ActionPill(
                            icon = Icons.Rounded.Dns,
                            label = facets?.server ?: stringResource(R.string.player_server),
                            onClick = { onOpenPanel(PlayerPanel.SERVER) },
                            loading = state.isLoadingMoreStreams,
                        )
                    }
                    if (qualities.size > 1) {
                        ActionPill(
                            icon = Icons.Rounded.HighQuality,
                            label = facets?.quality ?: stringResource(R.string.player_quality),
                            onClick = { onOpenPanel(PlayerPanel.QUALITY) },
                        )
                    }
                    if (dubs.size > 1 || audioTrackCount > 1) {
                        ActionPill(
                            icon = Icons.Rounded.Translate,
                            label = facets?.dub ?: stringResource(R.string.player_dub),
                            onClick = { onOpenPanel(PlayerPanel.DUB) },
                        )
                    }
                    // Nothing above could be split into axes - a single unlabelled
                    // stream set, or labels this could not read - but there is still
                    // more than one to choose from, so the flat list stays reachable.
                    if (servers.size <= 1 && qualities.size <= 1 && dubs.size <= 1 &&
                        state.streams.size > 1
                    ) {
                        ActionPill(
                            icon = Icons.Rounded.HighQuality,
                            label = facets?.quality
                                ?: facets?.server
                                ?: stringResource(R.string.player_quality),
                            onClick = { onOpenPanel(PlayerPanel.QUALITY) },
                        )
                    }
                    // Always shown, even with no tracks. The panel is now also the way to
                    // search online, and a source supplying none is precisely when that is
                    // wanted - hiding the pill made it unreachable in that exact case.
                    ActionPill(
                        icon = Icons.Rounded.ClosedCaption,
                        label = stringResource(R.string.player_subtitles),
                        onClick = { onOpenPanel(PlayerPanel.SUBTITLES) },
                        // Amber while a timing correction is set, so it is visible from the
                        // controls rather than only from inside the sub-panel that holds it.
                        highlighted = state.subtitleOffsetMs != 0L,
                    )
                    if (state.episodes.size > 1) {
                        ActionPill(
                            icon = Icons.Filled.VideoLibrary,
                            label = stringResource(R.string.player_episodes),
                            onClick = { onOpenPanel(PlayerPanel.EPISODES) },
                        )
                    }
                }
            }
        }
    }
}

/** `Black@50%` pill with a 1dp `White@20%` border (`PlayerControls.kt:686-707`). */
@Composable
private fun TimePill(text: String) {
    val type = MaterialTheme.wbType
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = type.labelSm,
            color = Color.White,
        )
    }
}

@Composable
private fun ActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    /**
     * Marks the pill as carrying a non-default setting.
     *
     * Amber, matching every other measurement in the app. Without it a correction set deep
     * in a sub-panel is invisible from the controls, and the only way to find out whether
     * one is active is to go looking for it.
     */
    highlighted: Boolean = false,
    /**
     * Replaces the icon with a spinner while more options are still arriving.
     *
     * The label is left alone: it names the current choice, which is already valid, and
     * swapping it for "Loading" would suggest nothing is selected.
     */
    loading: Boolean = false,
) {
    val type = MaterialTheme.wbType
    val interaction = rememberFocusInteraction()
    Row(
        modifier = Modifier
            // Before clip: clipping first would cut the outline off at the pill's edge,
            // leaving the pill focusable but with nothing to show it.
            //
            // No scale: these sit in a fixed-width pill row, so growing one shifts
            // its neighbours sideways.
            .adaptiveFocus(interaction, RoundedCornerShape(22.dp), scale = false)
            .clip(RoundedCornerShape(22.dp))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (highlighted) MaterialTheme.wb.colors.warning else Color.White

        if (loading) {
            // Same footprint as the icon it replaces, so the pill does not resize and shift
            // its neighbours each time a backend reports in.
            CircularProgressIndicator(
                color = tint,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = label,
            style = type.labelSm,
            color = tint,
            maxLines = 1,
        )
    }
}

/**
 * Locked overlay (`PlayerControls.kt:587-683`): 78dp circle with a 34dp lock and
 * a "Tap to unlock" caption.
 */
@Composable
fun LockedPlayerOverlay(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = MaterialTheme.wbType
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(220.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                    ),
                ),
        )

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.52f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                    .clickable(onClick = onUnlock),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "Unlock",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.player_locked),
                style = type.bodyMd,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.92f),
            )
        }
    }
}

private fun Float.formatSpeed(): String =
    if (this == this.toInt().toFloat()) this.toInt().toString() else "%.2f".format(this).trimEnd('0').trimEnd('.')

/**
 * How far one directional press moves the scrubber.
 *
 * Coarser than the ±10s buttons on purpose: those exist for a precise nudge, whereas holding a
 * direction on the timeline is how someone crosses a long film, and ten seconds a press makes
 * that unusable.
 */
internal const val SLIDER_SEEK_STEP_MS = 15_000L

/**
 * Horizontal inset matching Material's slider.
 *
 * The slider reserves half a thumb at each end so the thumb stays on screen at 0% and 100%, which
 * means its usable track is narrower than its bounds. The markers have to use the same inset or a
 * band drifts from the position the thumb reports for it - most visibly at the very start and end.
 */
private val SLIDER_THUMB_INSET = 10.dp
