package space.nicart.watchbox.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.data.remote.SkipInterval
import space.nicart.watchbox.data.remote.SkipKind

/**
 * Bands on the timeline showing where the opening and ending sit.
 *
 * Drawn behind the scrubber rather than replacing it. Material3's `Slider` has no marker API, and
 * reimplementing it would mean redoing its focus handling, D-pad keys and touch semantics - all of
 * which took real work to get right on a television.
 *
 * The bands answer a question the button alone cannot: *where* the skippable parts are, and how
 * long they run. That matters when scrubbing manually, and it also makes the AniSkip data visible
 * before playback reaches it - so a viewer can tell the feature is working rather than wondering
 * whether the button will ever appear.
 *
 * Nothing is drawn until the runtime is known: without a duration there is no scale to place a
 * band against, and guessing would put it in the wrong place.
 */
@Composable
fun SkipSegmentMarkers(
    intervals: List<SkipInterval>,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    if (intervals.isEmpty() || durationMs <= 0) return

    Canvas(modifier = modifier) {
        val trackHeight = MARKER_HEIGHT.toPx()
        // Centred on the track, so the band sits under the slider's own line rather than
        // floating above or below it.
        val top = (size.height - trackHeight) / 2f
        val radius = CornerRadius(trackHeight / 2f)

        intervals.forEach { interval ->
            val startFraction = (interval.startMs.toFloat() / durationMs).coerceIn(0f, 1f)
            val endFraction = (interval.endMs.toFloat() / durationMs).coerceIn(0f, 1f)

            val left = startFraction * size.width
            // Floored to a visible minimum: a short interval on a long episode rounds to well
            // under a pixel, and an invisible marker is indistinguishable from a missing one.
            val width = ((endFraction - startFraction) * size.width)
                .coerceAtLeast(MARKER_MIN_WIDTH.toPx())

            drawPath(
                path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = androidx.compose.ui.geometry.Rect(
                                offset = Offset(left, top),
                                size = Size(width, trackHeight),
                            ),
                            topLeft = radius,
                            topRight = radius,
                            bottomLeft = radius,
                            bottomRight = radius,
                        ),
                    )
                },
                // Distinct colours, because the two are not interchangeable: skipping an opening
                // is routine, skipping an ending often means the episode is effectively over.
                color = when (interval.kind) {
                    SkipKind.OPENING -> OPENING_COLOR
                    SkipKind.ENDING -> ENDING_COLOR
                },
            )
        }
    }
}

/**
 * Amber for the opening.
 *
 * Deliberately not the accent colour, which the slider already uses for progress - a band in the
 * same colour would read as played rather than as a marker. Opaque enough to see through the
 * slider's translucent inactive track without competing with the white thumb.
 */
private val OPENING_COLOR = Color(0xCCFFB300)

/** Teal for the ending, clearly separable from the opening at a glance and for most colour blindness. */
private val ENDING_COLOR = Color(0xCC26C6DA)

/** Matches the visual weight of the slider's own track. */
private val MARKER_HEIGHT = 4.dp

/** Smallest drawn width, so a brief segment on a long episode stays visible. */
private val MARKER_MIN_WIDTH = 3.dp
