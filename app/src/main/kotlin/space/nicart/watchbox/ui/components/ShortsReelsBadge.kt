package space.nicart.watchbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.wb

/** Content-type badge, separate from update, obsolete and 18+ status badges. */
@Composable
fun ShortsReelsBadge(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.wb.colors.accent
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(accent.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text = stringResource(R.string.extensions_short_reels),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
