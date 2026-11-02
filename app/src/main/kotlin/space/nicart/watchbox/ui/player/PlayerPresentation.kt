package space.nicart.watchbox.ui.player

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.core.ui.FormFactor

/** TV always keeps the established landscape player, including Arvin content. */
internal fun usesPortraitPlayer(isShortReels: Boolean, formFactor: FormFactor): Boolean =
    isShortReels && formFactor != FormFactor.TV

/**
 * Keep a portrait stage even when a large-screen device declines an orientation
 * request. A tall phone uses its full width; a landscape tablet gets side gutters.
 */
internal fun portraitPlayerWidth(width: Dp, height: Dp): Dp =
    minOf(width, height * (9f / 16f))

/** Lower the taller icon rail on short screens without covering the header or timeline. */
internal fun shortReelsRailBottomPadding(
    availableHeight: Dp,
    hasEpisodes: Boolean,
    hasQualityChoices: Boolean = true,
    hasSubtitles: Boolean = true,
): Dp {
    val buttons = 1 + (if (hasEpisodes) 3 else 0) +
        (if (hasQualityChoices) 1 else 0) + (if (hasSubtitles) 1 else 0)
    val railHeight = 48.dp * buttons + 8.dp * (buttons - 1)
    return minOf(160.dp, (availableHeight - 72.dp - railHeight).coerceAtLeast(72.dp))
}

/** Full-window input/background with an optionally narrower portrait video stage. */
@Composable
internal fun PlayerViewport(
    portrait: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val stageWidth = if (portrait) portraitPlayerWidth(maxWidth, maxHeight) else maxWidth
        BoxWithConstraints(
            modifier = Modifier.align(Alignment.Center).width(stageWidth).fillMaxHeight(),
            content = content,
        )
    }
}

internal enum class ReelSwipeAction { NONE, NEXT, PREVIOUS }

/** Called once at release, so a long swipe cannot skip several episodes. */
internal fun reelSwipeAction(
    dragY: Float,
    threshold: Float,
    hasNext: Boolean,
    hasPrevious: Boolean,
): ReelSwipeAction = when {
    threshold <= 0f -> ReelSwipeAction.NONE
    dragY <= -threshold && hasNext -> ReelSwipeAction.NEXT
    dragY >= threshold && hasPrevious -> ReelSwipeAction.PREVIOUS
    else -> ReelSwipeAction.NONE
}

/** Shared by both player presentations; left rewinds and right advances exactly 10s. */
internal fun doubleTapSeekDelta(x: Float, width: Int): Long =
    if (x > width / 2f) 10_000L else -10_000L
