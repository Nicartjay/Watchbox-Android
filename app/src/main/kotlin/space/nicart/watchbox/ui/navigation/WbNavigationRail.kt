package space.nicart.watchbox.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.tvFocusOutline
import space.nicart.watchbox.core.ui.wb

/**
 * Left navigation rail for TV and large tablets.
 *
 * Replaces the floating bottom pill, which is wrong on a television for two
 * reasons: the bottom edge is inside the overscan region where a set can physically
 * crop it, and reaching a bottom bar with a D-pad means traversing the entire
 * content area downward every time.
 *
 * The rail expands on focus rather than showing labels permanently. Collapsed it is
 * an icon strip that leaves the content nearly full width; focused it widens to
 * reveal labels, which is the pattern every TV interface converges on because icons
 * alone are ambiguous and permanent labels waste horizontal space on a 16:9 screen.
 */
@Composable
fun WbNavigationRail(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
) {
    val tokens = MaterialTheme.wb

    val width by animateDpAsState(
        targetValue = if (expanded) EXPANDED_WIDTH else COLLAPSED_WIDTH,
        animationSpec = tween(180),
        label = "railWidth",
    )

    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            // Transparent so the artwork behind it reads through, with a short
            // left-edge gradient instead of a panel. A flat fill cuts a hard vertical
            // seam across the backdrop; a gradient keeps the icons legible over bright
            // artwork without announcing itself as a bar.
            //
            // Not fully transparent: the rail sits over arbitrary imagery, and on a
            // pale frame white icons on nothing are unreadable.
            .background(
                Brush.horizontalGradient(
                    0f to tokens.colors.background.copy(alpha = 0.85f),
                    0.6f to tokens.colors.background.copy(alpha = 0.45f),
                    1f to Color.Transparent,
                ),
            )
            .padding(vertical = 24.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Pushes the items to vertical centre, which is where the eye rests on a
        // large screen rather than the top corner.
        Spacer(Modifier.height(24.dp))

        AppTab.entries.forEach { tab ->
            RailItem(
                tab = tab,
                selected = tab == selected,
                showLabel = expanded,
                onClick = { onSelect(tab) },
            )
        }
    }
}

@Composable
private fun RailItem(
    tab: AppTab,
    selected: Boolean,
    showLabel: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    val background by animateColorAsState(
        targetValue = if (selected) tokens.colors.accent else Color.Transparent,
        animationSpec = tween(150),
        label = "railItemBg",
    )

    Row(
        modifier = Modifier
            .fillMaxWidthOrIcon(showLabel)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .tvFocusOutline(interaction, RoundedCornerShape(12.dp))
            // clickable only - no separate focusable(). clickable already makes the
            // node focusable, and adding focusable() *before* it inserts a focus
            // target that consumes the D-pad centre key before clickable can act on
            // it, so pressing OK moved focus instead of selecting the tab.
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = stringResource(tab.labelRes),
            tint = if (selected) tokens.colors.onAccent else tokens.colors.textSecondary,
            modifier = Modifier.size(24.dp),
        )

        if (showLabel) {
            Text(
                text = stringResource(tab.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) tokens.colors.onAccent else tokens.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Square while collapsed, full width once labels are shown. */
private fun Modifier.fillMaxWidthOrIcon(showLabel: Boolean): Modifier =
    if (showLabel) then(Modifier.fillMaxWidth()) else then(Modifier.size(48.dp))

private val COLLAPSED_WIDTH = 72.dp
private val EXPANDED_WIDTH = 220.dp
