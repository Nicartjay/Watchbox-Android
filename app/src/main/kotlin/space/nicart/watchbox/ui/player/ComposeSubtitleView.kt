package space.nicart.watchbox.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay

/**
 * Subtitle renderer drawn with Compose.
 *
 * Media3's own [androidx.media3.ui.SubtitleView] is not used because it offers no
 * control over outline width: `SubtitlePainter` hardcodes it to 2dp, computed from
 * display density, and `CaptionStyleCompat` has no parameter for it. Honouring a
 * width setting therefore means drawing the cues here.
 *
 * The outline is a genuine stroke via [Stroke], not a blurred shadow standing in for
 * one, so widening it stays crisp instead of turning into a smear. It is drawn as a
 * second text layer beneath the fill because a single pass can stroke or fill but not
 * both.
 *
 * Only the cue text is rendered. Positioning, alignment and vertical placement
 * carried by the cue are deliberately ignored: honouring them properly means
 * reimplementing a large part of Media3's layout, and the overwhelmingly common case
 * is bottom-centred dialogue.
 */
@UnstableApi
@Composable
fun ComposeSubtitleView(
    player: Player,
    style: SubtitleStyle,
    modifier: Modifier = Modifier,
    /**
     * Cues parsed from the selected subtitle, used only when an offset is applied.
     *
     * Empty means "render whatever the player reports", which is the normal path and the
     * only one available for embedded tracks or formats this app cannot parse.
     */
    offsetCues: List<SubtitleCue> = emptyList(),
    /** Timing correction in milliseconds; positive delays the subtitles. */
    offsetMs: Long = 0L,
    /** Current playback position, only read when [offsetCues] is in use. */
    positionMs: Long = 0L,
    /**
     * Extra clearance beneath the cues.
     *
     * Raised while the player controls are showing so dialogue is not covered by the
     * scrubber and button rows. Passed in rather than derived here because the control
     * stack's height is the player screen's own metric.
     */
    bottomInset: Dp = 0.dp,
) {
    var playerCues by remember { mutableStateOf<List<String>>(emptyList()) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                playerCues = cueGroup.cues.mapNotNull { it.text?.toString() }
                    .filter { it.isNotBlank() }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // The player's own cues are preferred whenever no correction is in play: they carry
    // the real decoder's timing and cover every format, including the ones this app does
    // not parse.
    //
    // A shift is only possible from a parsed list. `onCues` fires when a cue becomes
    // current, which can delay a line but can never surface one early, so a negative
    // offset is unrepresentable that way.
    val usesOffset = offsetMs != 0L && offsetCues.isNotEmpty()

    // Says why an offset is being ignored, which is otherwise invisible: the correction is
    // set, the panel shows it, and the subtitles do not move. Both causes look the same
    // from the screen - an unparsable format, or an embedded track with no URL to fetch.
    LaunchedEffect(offsetMs, offsetCues.size) {
        if (offsetMs == 0L) return@LaunchedEffect
        android.util.Log.i(
            "WbSubOffset",
            "offset=${offsetMs}ms cues=${offsetCues.size} applied=$usesOffset",
        )
    }

    // Its own clock while shifting, polled far more often than the screen's 500ms
    // progress tick.
    //
    // That tick exists to move a scrubber, where half a second is invisible. Cue
    // boundaries are not: at 500ms a line can appear up to half a second late and
    // truncate by the same amount, which is the very error being corrected. Read from the
    // player rather than derived from [positionMs] so it stays true across a seek, and
    // only while an offset is set - otherwise this is a timer doing nothing.
    var tickMs by remember { mutableLongStateOf(positionMs) }
    LaunchedEffect(usesOffset, player) {
        if (!usesOffset) return@LaunchedEffect
        while (true) {
            tickMs = player.currentPosition
            delay(CUE_POLL_MS)
        }
    }

    // Dropped the moment the app takes over drawing.
    //
    // Disabling the decoder stops new callbacks but does not retract the last one, so the
    // line showing at that instant would stay frozen on screen until the next state change.
    LaunchedEffect(usesOffset) {
        if (usesOffset) playerCues = emptyList()
    }

    val cues = if (usesOffset) {
        // The fine clock is used only while it agrees with the screen's own position.
        //
        // They diverge in two cases, and in both the screen is right: after a seek, until
        // the next poll catches up; and while casting, when the local player is paused and
        // its clock is frozen at wherever it stopped. `maxOf` would pick the stale value in
        // the casting case, leaving subtitles fixed on screen.
        val drift = kotlin.math.abs(tickMs - positionMs)
        val clock = if (drift <= CUE_CLOCK_TRUST_MS) tickMs else positionMs
        offsetCues.activeAt(clock, offsetMs)
    } else {
        playerCues
    }

    if (cues.isEmpty()) return

    BoxWithSubtitleMetrics(modifier = modifier, bottomInset = bottomInset) { fontSize ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            cues.forEach { line ->
                SubtitleLine(text = line, style = style, fontSizeSp = fontSize)
            }
        }
    }
}

/**
 * Resolves the text size from the view height.
 *
 * Fractional sizing is what keeps a choice made on a phone looking right on a TV, so
 * the fraction is resolved against the actual rendered height rather than a
 * hardcoded assumption.
 */
@Composable
private fun BoxWithSubtitleMetrics(
    modifier: Modifier,
    bottomInset: Dp,
    content: @Composable (fontSizeSp: Float) -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val density = LocalDensity.current
        val heightPx = with(density) { maxHeight.toPx() }
        // Converted to sp through density so the value is independent of the
        // device's font scale, which the player pins anyway.
        val fontSizeSp = with(density) { (heightPx * SUBTITLE_FRACTION_UNIT).toSp().value }

        // The resting gap plus whatever the controls need. Animated by the caller, so
        // the cues rise and settle with the overlay rather than jumping.
        Box(modifier = Modifier.padding(bottom = SUBTITLE_BOTTOM_GAP + bottomInset)) {
            content(fontSizeSp)
        }
    }
}

/** Multiplied by the style's fraction; keeps the maths in one place. */
private const val SUBTITLE_FRACTION_UNIT = 1f

/** Resting gap between the cues and the bottom edge. */
private val SUBTITLE_BOTTOM_GAP = 32.dp

@Composable
private fun SubtitleLine(
    text: String,
    style: SubtitleStyle,
    fontSizeSp: Float,
) {
    val density = LocalDensity.current
    val textColor = Color(style.textColor)
    val size = (fontSizeSp * style.size.fraction).sp

    val base = TextStyle(
        fontSize = size,
        fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
    )

    val strokeWidthPx = with(density) { style.edgeWidth.outlineDp.dp.toPx() }

    val rowModifier = when (style.background) {
        SubtitleBackground.FULL_BACKGROUND -> Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = style.backgroundOpacity))

        else -> Modifier
    }

    val textModifier = when (style.background) {
        SubtitleBackground.BACKGROUND -> Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = style.backgroundOpacity))
            .padding(horizontal = 8.dp, vertical = 2.dp)

        else -> Modifier
    }

    Box(modifier = rowModifier, contentAlignment = Alignment.Center) {
        Box(modifier = textModifier) {
            // Stroke first, underneath: a single draw pass cannot both stroke and
            // fill, so the outline is a separate layer behind the solid text.
            if (style.background == SubtitleBackground.OUTLINE && strokeWidthPx > 0f) {
                androidx.compose.material3.Text(
                    text = text,
                    style = base.copy(
                        color = Color.Black,
                        drawStyle = Stroke(width = strokeWidthPx * 2f),
                    ),
                )
            }

            androidx.compose.material3.Text(
                text = text,
                style = base.copy(
                    color = textColor,
                    shadow = if (style.background == SubtitleBackground.DROP_SHADOW) {
                        Shadow(
                            color = Color.Black.copy(alpha = 0.85f),
                            offset = Offset(
                                with(density) { style.edgeWidth.shadowDp.dp.toPx() },
                                with(density) { style.edgeWidth.shadowDp.dp.toPx() },
                            ),
                            blurRadius = with(density) {
                                style.edgeWidth.shadowBlurDp.dp.toPx()
                            },
                        )
                    } else {
                        null
                    },
                ),
            )
        }
    }
}

/**
 * Cue polling interval while an offset is applied.
 *
 * 100ms keeps a cue boundary within a tenth of a second, which is below the threshold at
 * which a subtitle reads as mistimed, without running a tight loop.
 */
private const val CUE_POLL_MS = 100L

/**
 * How far the fine clock may differ from the screen's position before it is distrusted.
 *
 * Comfortably above one poll interval plus the screen's own 500ms tick, so ordinary lag
 * between the two is tolerated, while a seek or a frozen local clock during casting is not.
 */
private const val CUE_CLOCK_TRUST_MS = 1_500L
