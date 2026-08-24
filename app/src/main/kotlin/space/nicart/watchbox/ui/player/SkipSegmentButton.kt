package space.nicart.watchbox.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.adaptiveFocus
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.data.remote.SkipInterval
import space.nicart.watchbox.data.remote.SkipKind
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay

/**
 * "Skip Intro" / "Skip Outro", shown only while playback is inside a known interval.
 *
 * Deliberately **not** part of [PlayerControlsOverlay]. The controls hide themselves three seconds
 * after the last press, and an opening runs for ninety - so a button living inside them would
 * disappear for most of the window it exists to cover. This is driven by playback position alone.
 *
 * Manual rather than automatic. AniSkip's timestamps are crowd-submitted and can be a few seconds
 * out, and an unrequested jump is not undoable mid-seek; a button that a viewer chooses to press
 * is honest about the uncertainty.
 */
@Composable
fun SkipSegmentButton(
    intervals: List<SkipInterval>,
    positionMs: Long,
    onSkip: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Claim focus while the button is showing.
     *
     * Set on TV when the controls are hidden, so OK skips without the viewer aiming at
     * anything. It is the only thing on screen at that moment, and hunting for it with the
     * D-pad is not possible - the video surface holds focus and the button is not adjacent
     * to it in any direction.
     *
     * Off while the controls are up: the play button owns focus there, and stealing it
     * would move the selection out from under someone mid-press.
     */
    autoFocus: Boolean = false,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()
    val focusRequester = remember { FocusRequester() }

    // The first match wins. Openings and endings cannot overlap in practice, and picking one
    // keeps the button single-purpose rather than ambiguous.
    val active = intervals.firstOrNull { it.contains(positionMs) }

    // Claimed when the button appears, not on every position tick.
    //
    // Keyed on whether an interval is active rather than on `active` itself, so the request
    // fires once per appearance; keying on the interval would re-request every 500ms tick
    // and fight any focus the viewer moved elsewhere.
    //
    // Retried because requestFocus reports success even when its target has no node yet,
    // which is the case while the button is still animating in.
    val isShowing = active != null
    LaunchedEffect(isShowing, autoFocus) {
        if (!isShowing || !autoFocus) return@LaunchedEffect
        repeat(SKIP_FOCUS_ATTEMPTS) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
            delay(SKIP_FOCUS_RETRY_MS)
        }
    }

    AnimatedVisibility(
        visible = active != null,
        // Slides in from the trailing edge, which is where it sits: appearing by fade alone in
        // the corner of a moving picture is easy to miss entirely.
        enter = slideInHorizontally(tween(220)) { it } + fadeIn(tween(220)),
        exit = slideOutHorizontally(tween(180)) { it } + fadeOut(tween(180)),
        modifier = modifier,
    ) {
        // Captured so the label and the target survive the exit animation: `active` is null by
        // the time the button animates out, and reading it there would blank the text mid-exit.
        val shown = active ?: return@AnimatedVisibility

        Row(
            modifier = Modifier
                .focusRequester(focusRequester)
                // A rounded rectangle rather than a pill: 999.dp made the ends fully circular,
                // which read as a floating chip. 12.dp keeps it a button.
                .adaptiveFocus(interaction, RoundedCornerShape(SKIP_CORNER), scale = false)
                .clip(RoundedCornerShape(SKIP_CORNER))
                .background(tokens.colors.surfaceElevated.copy(alpha = 0.92f))
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                ) { onSkip(shown.endMs) }
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    when (shown.kind) {
                        SkipKind.OPENING -> R.string.player_skip_intro
                        SkipKind.ENDING -> R.string.player_skip_outro
                        SkipKind.RECAP -> R.string.player_skip_recap
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
            )
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = null,
                tint = tokens.colors.textPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Corner radius for the skip button.
 *
 * Enough to match the surrounding chrome without becoming a pill - a fully circular end reads as
 * a floating chip rather than something to press.
 */
private val SKIP_CORNER = 12.dp

/** Matches the panel and control focus retries; see [SkipSegmentButton]. */
private const val SKIP_FOCUS_ATTEMPTS = 12
private const val SKIP_FOCUS_RETRY_MS = 60L
