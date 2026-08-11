package space.nicart.watchbox.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.tvFocusOutline
import space.nicart.watchbox.core.ui.tvFocusable
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.ui.browse.SourceEntry
import space.nicart.watchbox.ui.extensions.ExtensionIconSlot

/**
 * Source button for the bottom of the navigation rail.
 *
 * Mirrors the rail's own items: an icon while collapsed, icon plus name once the rail
 * expands. The source is the closest thing this app has to a channel, so it belongs
 * with navigation rather than floating over the home artwork - and putting it in the
 * rail means search can share it without growing a second control.
 */
@Composable
fun TvRailSourceButton(
    source: SourceEntry?,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()
    if (source == null) return

    Row(
        modifier = Modifier
            .then(if (expanded) Modifier.fillMaxWidth() else Modifier.size(48.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surface.copy(alpha = 0.85f))
            .tvFocusOutline(interaction, RoundedCornerShape(12.dp))
            // clickable only, and after the outline: a separate focusable() placed
            // before it swallows the D-pad centre key, so the button would highlight
            // but never open.
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = if (expanded) 12.dp else 0.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) {
            Arrangement.spacedBy(12.dp)
        } else {
            Arrangement.Center
        },
    ) {
        ExtensionIconSlot(
            drawable = source.icon,
            iconUrl = null,
            modifier = Modifier.size(24.dp),
        )

        if (expanded) {
            Text(
                text = source.name,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Source list, in the same right-edge drawer as the player and filter panels.
 *
 * Shown even for a single source, unlike the old home-screen picker: from the rail it is
 * a permanent control, and a button that opens nothing reads as broken.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvSourcePickerPanel(
    sources: List<SourceEntry>,
    selected: SourceEntry?,
    visible: Boolean,
    onSelect: (SourceEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.wb
    val panelFocus = remember { FocusRequester() }

    // Closes the drawer instead of leaving the app. The picker opens from the rail on
    // the root Tabs destination, where nothing is left to pop, so an unhandled Back
    // here exits WatchBox entirely. See the note in SourceFilterPanel for why this
    // cannot be a key handler.
    BackHandler(enabled = visible, onBack = onDismiss)

    // Pull focus in when the drawer opens, so the first D-pad press acts on a source
    // rather than the rail behind it, which is still laid out and focusable.
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        // Retried because requestFocus reports success even when no focusable node is
        // attached yet: the drawer is still animating in on the first frames.
        repeat(20) {
            runCatching { panelFocus.requestFocus() }
            delay(25)
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(160)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(onClick = onDismiss),
        )
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(tween(250)) { it },
            exit = slideOutHorizontally(tween(200)) { it },
        ) {
            Column(
                modifier = Modifier
                    .width(420.dp)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .background(tokens.colors.surfaceElevated)
                    // Keeps D-pad focus inside the drawer: the rail behind is still laid
                    // out and focusable, so without this Up and Down drive the hidden
                    // tabs and the picker can never be reached with a remote.
                    .focusRequester(panelFocus)
                    // Cancels focus exit outright: a remote has no pointer to dismiss
                    // with, so focus escaping the drawer leaves no way back into it.
                    // Back closes it instead.
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .focusGroup()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.tv_source_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                )
                Spacer(Modifier.height(8.dp))

                sources.forEach { source ->
                    TvSourceRow(
                        source = source,
                        isSelected = source.id == selected?.id,
                        onClick = { onSelect(source) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvSourceRow(
    source: SourceEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Before clip: clipping first would cut the scaled edge and the outline.
            .tvFocusable(interaction, RoundedCornerShape(10.dp), focusedScale = 1.02f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) tokens.colors.accent else tokens.colors.surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ExtensionIconSlot(
            drawable = source.icon,
            iconUrl = null,
            modifier = Modifier.size(30.dp),
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = source.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isSelected) tokens.colors.onAccent else tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (source.lang.isNotBlank()) {
                Text(
                    text = source.lang.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) {
                        tokens.colors.onAccent.copy(alpha = 0.8f)
                    } else {
                        tokens.colors.textMuted
                    },
                )
            }
        }
    }
}
