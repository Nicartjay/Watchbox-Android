package space.nicart.watchbox.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.ui.components.WbChip
import space.nicart.watchbox.ui.player.SUBTITLE_TEXT_COLORS
import space.nicart.watchbox.ui.player.SubtitleBackground
import space.nicart.watchbox.ui.player.SubtitleEdgeWidth
import space.nicart.watchbox.ui.player.SubtitleSize
import space.nicart.watchbox.ui.player.SubtitleStyle

/**
 * Live preview of the current subtitle appearance.
 *
 * Included because none of these choices can be judged from their names - whether an
 * outline is enough, or a band too heavy, depends entirely on how it looks. Without a
 * preview the only way to evaluate a change is to open a video and find a subtitled
 * moment.
 *
 * This is an approximation drawn with Compose, not Media3's renderer, so it shows
 * the effect rather than the exact pixels. It sits on a checkered-ish backdrop
 * because a preview over flat colour cannot show whether text stays readable.
 */
@Composable
fun SubtitlePreview(
    style: SubtitleStyle,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb
    val textColor = Color(style.textColor)
    val backdropColor = Color(0xFF3A3A3C)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(backdropColor),
        contentAlignment = Alignment.Center,
    ) {
        // A light band mimics a bright scene, which is exactly where subtitles
        // become hard to read.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .align(Alignment.TopCenter)
                .background(Color(0xFFB0B0B4)),
        )

        val bandModifier = when (style.background) {
            SubtitleBackground.FULL_BACKGROUND -> Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = style.backgroundOpacity))

            else -> Modifier
        }

        Box(modifier = bandModifier, contentAlignment = Alignment.Center) {
            val textModifier = when (style.background) {
                SubtitleBackground.BACKGROUND -> Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = style.backgroundOpacity))
                    .padding(horizontal = 6.dp, vertical = 2.dp)

                else -> Modifier
            }

            Text(
                text = "The quick brown fox",
                modifier = textModifier.padding(horizontal = 12.dp),
                textAlign = TextAlign.Center,
                style = TextStyle(
                    color = textColor,
                    // Preview scale is relative to the box, mirroring how Media3
                    // sizes text as a fraction of the video surface.
                    fontSize = (style.size.fraction * PREVIEW_HEIGHT_PX).sp,
                    fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                    shadow = when (style.background) {
                        // Compose has no true outline, so a tight opaque shadow
                        // stands in for one. It reads the same at this size.
                        SubtitleBackground.OUTLINE -> Shadow(
                            Color.Black,
                            Offset(0f, 0f),
                            blurRadius = style.edgeWidth.outlineDp * 3f,
                        )

                        SubtitleBackground.DROP_SHADOW -> Shadow(
                            Color.Black.copy(alpha = 0.85f),
                            Offset(style.edgeWidth.shadowDp, style.edgeWidth.shadowDp),
                            style.edgeWidth.shadowBlurDp,
                        )

                        else -> null
                    },
                ),
            )
        }

        Text(
            text = style.background.label,
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp),
        )
    }
}

/** Nominal preview height in px, used to turn a fraction into a font size. */
private const val PREVIEW_HEIGHT_PX = 300f

/** Horizontally scrolling size chips. */
@Composable
fun SubtitleSizeRow(
    selected: SubtitleSize,
    onSelect: (SubtitleSize) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(SubtitleSize.entries.size) { index ->
            val size = SubtitleSize.entries[index]
            WbChip(
                label = size.label,
                selected = size == selected,
                onClick = { onSelect(size) },
            )
        }
    }
}

/**
 * Background style options, one per row.
 *
 * Listed vertically rather than as chips because the labels are long enough that a
 * horizontal strip would either truncate them or scroll past the selection.
 */
@Composable
fun SubtitleBackgroundColumn(
    selected: SubtitleBackground,
    onSelect: (SubtitleBackground) -> Unit,
) {
    val tokens = MaterialTheme.wb

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SubtitleBackground.entries.forEach { option ->
            val isSelected = option == selected
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) tokens.colors.onAccent else tokens.colors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) tokens.colors.accent else tokens.colors.surface)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

/** Preset colour swatches. */
@Composable
fun SubtitleColorRow(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val tokens = MaterialTheme.wb

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SUBTITLE_TEXT_COLORS.forEach { (_, color) ->
            val isSelected = color == selected
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) {
                            tokens.colors.accent
                        } else {
                            tokens.colors.borderSubtle
                        },
                        shape = CircleShape,
                    )
                    .clickable { onSelect(color) },
            )
        }
    }
}

/**
 * Background opacity, in steps.
 *
 * Stepped rather than a continuous slider: the useful range is narrow, and exact
 * values are meaningless to choose between by eye.
 */
@Composable
fun SubtitleOpacityRow(
    selected: Float,
    onSelect: (Float) -> Unit,
    enabled: Boolean,
) {
    val tokens = MaterialTheme.wb

    Column {
        if (!enabled) {
            // Explained rather than hidden, so the control does not appear and
            // disappear as the background style changes.
            Text(
                text = "Only used with a background or band",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
            )
            Spacer(Modifier.height(6.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OPACITY_STEPS.forEach { step ->
                WbChip(
                    label = "${(step * 100).toInt()}%",
                    selected = step == selected,
                    onClick = { if (enabled) onSelect(step) },
                )
            }
        }
    }
}

private val OPACITY_STEPS = listOf(0.3f, 0.6f, 0.8f, 1f)

/** Outline / shadow weight chips. Only meaningful when an edge is drawn. */
@Composable
fun SubtitleEdgeWidthRow(
    selected: SubtitleEdgeWidth,
    onSelect: (SubtitleEdgeWidth) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(SubtitleEdgeWidth.entries.size) { index ->
            val width = SubtitleEdgeWidth.entries[index]
            WbChip(
                label = width.label,
                selected = width == selected,
                onClick = { onSelect(width) },
            )
        }
    }
}
