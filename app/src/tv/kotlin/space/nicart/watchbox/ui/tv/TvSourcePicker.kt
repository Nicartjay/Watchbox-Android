package space.nicart.watchbox.ui.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
@Composable
fun TvSourcePickerPanel(
    sources: List<SourceEntry>,
    selected: SourceEntry?,
    visible: Boolean,
    onSelect: (SourceEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.wb

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
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) tokens.colors.accent else tokens.colors.surface)
            .tvFocusable(interaction, RoundedCornerShape(10.dp), focusedScale = 1.02f)
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
