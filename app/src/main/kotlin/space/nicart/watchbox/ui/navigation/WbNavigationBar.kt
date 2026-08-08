package space.nicart.watchbox.ui.navigation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.nicart.watchbox.core.ui.WbTokens
import space.nicart.watchbox.core.ui.wb

/**
 * The floating pill bottom navigation.
 *
 * Ported from NuvioMobile `core/ui/NavigationBar.kt`:
 *  - fully rounded pill, `#1C1C1E` at 82% (Nuvio drops to 55% when its blur
 *    plugin is active; without Haze the opaque value is the correct match);
 *  - horizontal padding animates 28dp expanded -> 58dp collapsed, so the pill
 *    physically narrows once labels hide;
 *  - icons 28dp, tint animates accent <-> textMuted;
 *  - labels 11sp/14sp, SemiBold when selected;
 *  - collapse is scroll-driven past a 60px threshold, `tween(300, standard)`.
 */
private const val SCROLL_THRESHOLD = 60f
private val PILL_COLOR = Color(0xFF1C1C1E)

/**
 * Accumulates scroll delta and exposes whether labels should show.
 * Mirrors `NuvioNavBarScrollState` (`NavigationBar.kt:50-125`).
 */
@Stable
class WbNavBarScrollState {
    var labelsVisible by mutableStateOf(true)
        private set

    private var accumulated by mutableFloatStateOf(0f)

    /** [delta] is the raw scroll delta; negative means scrolling down. */
    fun onScroll(delta: Float) {
        if (delta == 0f) return

        // Reset the accumulator whenever direction flips.
        if (delta < 0f && accumulated > 0f) accumulated = 0f
        if (delta > 0f && accumulated < 0f) accumulated = 0f

        accumulated += delta

        when {
            accumulated <= -SCROLL_THRESHOLD && labelsVisible -> {
                labelsVisible = false
                accumulated = 0f
            }

            accumulated >= SCROLL_THRESHOLD && !labelsVisible -> {
                labelsVisible = true
                accumulated = 0f
            }
        }
    }

    fun reveal() {
        labelsVisible = true
        accumulated = 0f
    }
}

@Composable
fun rememberWbNavBarScrollState(): WbNavBarScrollState = remember { WbNavBarScrollState() }

@Composable
fun WbNavigationBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: WbNavBarScrollState = rememberWbNavBarScrollState(),
) {
    val tokens = MaterialTheme.wb
    val labelsVisible = scrollState.labelsVisible

    val labelFraction by animateFloatAsState(
        targetValue = if (labelsVisible) 1f else 0f,
        animationSpec = tween(WbTokens.Motion.SHEET_ENTER, easing = WbTokens.Motion.standard),
        label = "navLabelFraction",
    )
    val horizontalPadding by animateDpAsState(
        targetValue = if (labelsVisible) 28.dp else 58.dp,
        animationSpec = tween(WbTokens.Motion.SHEET_ENTER, easing = WbTokens.Motion.standard),
        label = "navHorizontalPadding",
    )

    val navBarInset = WindowInsets.navigationBars.asPaddingValues()
        .calculateBottomPadding()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = horizontalPadding,
                end = horizontalPadding,
                // 6dp platform extra + 8dp breathing room (`PlatformInsets.android.kt:13`)
                bottom = navBarInset + 6.dp + 8.dp,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(WbTokens.Radius.full))
                .background(PILL_COLOR.copy(alpha = WbTokens.Opacity.OVERLAY_HEAVY))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTab.entries.forEach { tab ->
                WbNavItem(
                    tab = tab,
                    selected = tab == selected,
                    labelFraction = labelFraction,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun WbNavItem(
    tab: AppTab,
    selected: Boolean,
    labelFraction: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb

    val tint by animateColorAsState(
        targetValue = if (selected) tokens.colors.accent else tokens.colors.textMuted,
        animationSpec = tween(WbTokens.Motion.NORMAL),
        label = "navTint",
    )

    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(WbTokens.Radius.full))
            .background(
                if (selected) {
                    tokens.colors.accent.copy(alpha = WbTokens.Opacity.SELECTED)
                } else {
                    Color.Transparent
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = stringResource(tab.labelRes),
            tint = tint,
            modifier = Modifier.size(28.dp),
        )

        // 14dp-tall slot scaled by the collapse fraction, so hiding the label
        // shrinks the pill instead of leaving a gap.
        Box(
            modifier = Modifier
                .height(14.dp * labelFraction)
                .graphicsLayer {
                    alpha = labelFraction
                    scaleY = labelFraction
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(tab.labelRes),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = tint,
                maxLines = 1,
            )
        }
    }
}
