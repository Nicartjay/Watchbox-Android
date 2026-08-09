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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi

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
) {
    var cues by remember { mutableStateOf<List<String>>(emptyList()) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues.mapNotNull { it.text?.toString() }
                    .filter { it.isNotBlank() }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    if (cues.isEmpty()) return

    BoxWithSubtitleMetrics(modifier = modifier) { fontSize ->
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

        Box(modifier = Modifier.padding(bottom = 32.dp)) {
            content(fontSizeSp)
        }
    }
}

/** Multiplied by the style's fraction; keeps the maths in one place. */
private const val SUBTITLE_FRACTION_UNIT = 1f

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
