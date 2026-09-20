package space.nicart.watchbox.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.nicart.watchbox.core.ui.WbTokens
import space.nicart.watchbox.core.ui.wb

/**
 * Update count over the Browse navigation icon.
 *
 * Kept in the shared navigation package so the phone bar, tablet rail and TV rail show the
 * same state and styling.
 */
@Composable
internal fun NavigationUpdateBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    val text = navigationBadgeText(count) ?: return
    val tokens = MaterialTheme.wb

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
            .clip(RoundedCornerShape(WbTokens.Radius.full))
            .background(tokens.colors.danger)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 9.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** Null hides the badge; large counts are capped so they cannot cover the adjacent tab. */
internal fun navigationBadgeText(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}
