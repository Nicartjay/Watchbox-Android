package space.nicart.watchbox.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.tvFocusOutline
import space.nicart.watchbox.core.ui.wb

/**
 * A search field stand-in for TV.
 *
 * Not a real text field. A `TextField` on a television captures the D-pad the moment
 * it takes focus: arrow keys become caret movement, so the user can neither leave the
 * field nor reach anything else - which is exactly how the D-pad died here, with focus
 * pinned to an `EditText` spanning the top of the screen.
 *
 * Instead this is a focusable button showing the current query. Pressing it opens the
 * system voice/keyboard input, which is how TV interfaces handle text: nobody types a
 * search term with a remote if they can avoid it.
 */
@Composable
fun TvSearchTrigger(
    query: String,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surfaceCard)
            .tvFocusOutline(interaction, RoundedCornerShape(12.dp))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = tokens.colors.textMuted,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = query.ifBlank { placeholder },
            style = MaterialTheme.typography.titleMedium,
            color = if (query.isBlank()) tokens.colors.textMuted else tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
