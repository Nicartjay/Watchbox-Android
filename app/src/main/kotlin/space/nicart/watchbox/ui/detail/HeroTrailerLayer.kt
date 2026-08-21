package space.nicart.watchbox.ui.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.adaptiveFocus
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.data.remote.Trailer
import space.nicart.watchbox.ui.components.WbOverlayButtonSize

/**
 * A muted trailer that fades in over the hero backdrop.
 *
 * Drawn *on top of* the backdrop rather than instead of it, and only faded in once
 * a frame has actually rendered. The backdrop therefore stays visible while the
 * video buffers, and remains the whole hero if it never plays - so every failure
 * (no trailer, an expired URL, a codec the device lacks) resolves to the still
 * image with nothing flashing black in between.
 *
 * Silent unless told otherwise, and with no controls of its own. This is decoration
 * behind a title, and audio from a page opened to read a synopsis is intrusive. The
 * mute toggle lives in the screen's top overlay beside the back button - it cannot be
 * drawn here, because the heroes layer their scrims *over* this and a control under a
 * scrim is dimmed by it.
 *
 * The player is torn down whenever the composable leaves, the screen is
 * backgrounded, or [enabled] goes false. A detail page is transient - it is
 * scrolled past and navigated away from constantly - and a leaked ExoPlayer holds
 * a codec the real player then cannot get.
 */
@UnstableApi
@Composable
fun HeroTrailerLayer(
    trailer: Trailer?,
    /**
     * Whether playback may start at all.
     *
     * Separate from [trailer] being present so a caller can hold playback back -
     * while the hero is scrolled out of view, for instance - without discarding the
     * resolved trailer and re-requesting it.
     */
    enabled: Boolean,
    modifier: Modifier = Modifier,
    /**
     * Transform applied to the video.
     *
     * Its own parameter rather than folded into [modifier] because the phone hero
     * parallaxes the picture, and [onFirstFrame] reports upward from a composable whose
     * bounds the caller may have moved.
     */
    videoModifier: Modifier = Modifier,
    /**
     * Whether the audio track is silenced.
     *
     * Hoisted so the toggle can live outside the hero. Defaults to true, so a caller
     * that does not offer a control gets the silent trailer without opting in.
     */
    muted: Boolean = true,
    /**
     * Reports the first rendered frame.
     *
     * The signal the mute button waits on: before a frame exists there is no video to
     * silence, so a control for it would act on nothing.
     */
    onFirstFrame: () -> Unit = {},
) {
    if (trailer == null || !enabled) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Set on the first rendered frame, which is the only signal that the video is
    // really playing. Buffering state is not enough: a stream can report ready and
    // then fail on the first decode, which would fade out the backdrop and leave
    // nothing behind it.
    var hasFrame by remember(trailer.url) { mutableStateOf(false) }

    // Failure is silent by design. There is no message and no retry: a trailer is
    // decoration, and the backdrop it falls back to is a complete hero on its own.
    var failed by remember(trailer.url) { mutableStateOf(false) }

    val player = remember(trailer.url) {
        ExoPlayer.Builder(context).build().apply {
            // Muted before anything is prepared, so no frame of audio can escape
            // between preparation and the first mute call.
            volume = 0f
            // Loops. A trailer is shorter than the time spent reading a synopsis, and
            // ending on a frozen last frame reads as a stall rather than as a finish.
            //
            // It does mean a codec and a stream stay live for as long as the page is
            // open, which is why the setting to turn this off exists.
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = false
        }
    }

    // Applied as an effect rather than at the call site so the player follows this
    // state instead of holding a second copy of it: the instance is keyed on the URL,
    // and a replacement has to arrive already carrying the current choice.
    LaunchedEffect(player, muted) {
        player.volume = if (muted) 0f else 1f
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                hasFrame = true
                onFirstFrame()
            }

            override fun onPlayerError(error: PlaybackException) {
                // Includes the case that matters most in practice: the signed URL
                // expired between being resolved and being played.
                failed = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Deliberately delayed. Starting with the page means the trailer competes with
    // the poster, logo and episode list for bandwidth at the moment they are all
    // being fetched, and a viewer who opens a page and immediately leaves should
    // not have started a video at all.
    LaunchedEffect(trailer.url, failed) {
        if (failed) return@LaunchedEffect
        delay(AUTOPLAY_DELAY_MS)
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(trailer.url)
                .setMimeType(trailer.mimeType.takeIf { it.isNotBlank() })
                .build(),
        )
        player.prepare()
        player.play()
    }

    // Paused rather than released on backgrounding: the surface survives, so
    // returning to the page resumes instead of buffering from the start again.
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> if (hasFrame && !failed) player.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Crossfade in only, and only after a frame exists. Fading back out on failure
    // would draw attention to it; the backdrop underneath is already correct.
    val videoAlpha by animateFloatAsState(
        targetValue = if (hasFrame && !failed) 1f else 0f,
        animationSpec = tween(durationMillis = FADE_IN_MS),
        label = "heroTrailerFade",
    )

    if (failed) return

    Box(modifier = modifier.fillMaxSize().alpha(videoAlpha).then(videoModifier)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    // Cropped to fill, matching the backdrop it covers. A trailer is
                    // 16:9 and the phone hero is taller than it is wide, so fitting
                    // would letterbox a decorative video inside a full-bleed image.
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    // Nothing here is interactive, and leaving it focusable would put
                    // a stop on the D-pad's way to the action buttons.
                    isFocusable = false
                    isClickable = false
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    this.player = player
                }
            },
            // Rebound rather than recreated: the view is expensive and the player
            // instance is keyed on the URL above, so a new trailer brings a new view
            // through the factory anyway.
            update = { view -> view.player = player },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Mute toggle for the hero trailer.
 *
 * Lives in the detail screen's top overlay, in the row the back button sits in, rather
 * than inside the hero. Two reasons: the heroes draw their scrims over the trailer, so a
 * control placed with the video is dimmed along with it; and the row across the top is
 * already where this screen puts the actions that apply to the page as a whole.
 *
 * Sized and shaped to match [WbBackButton] beside it - same 40dp circle, same translucent
 * black - so the two read as one set rather than as a control that happens to be nearby.
 */
@Composable
fun TrailerMuteButton(
    muted: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = WbOverlayButtonSize,
    background: Color? = null,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Box(
        modifier = modifier
            .size(size)
            .adaptiveFocus(interaction, CircleShape, scale = false)
            .clip(CircleShape)
            .then(background?.let { Modifier.background(it) } ?: Modifier)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (muted) {
                Icons.AutoMirrored.Rounded.VolumeOff
            } else {
                Icons.AutoMirrored.Rounded.VolumeUp
            },
            // Names the action, not the state: a screen reader announcing "muted" leaves
            // the listener to work out what activating it would do.
            contentDescription = stringResource(
                if (muted) R.string.detail_trailer_unmute else R.string.detail_trailer_mute,
            ),
            tint = tokens.colors.textPrimary,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * How long the backdrop is shown before the trailer starts.
 *
 * Long enough that scrolling straight past a title costs no video, short enough
 * that a viewer who stopped to read is not left waiting on a still.
 */
private const val AUTOPLAY_DELAY_MS = 1_800L

/** Slow enough to read as a dissolve rather than a cut. */
private const val FADE_IN_MS = 550
