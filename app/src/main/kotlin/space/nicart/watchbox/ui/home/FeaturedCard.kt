package space.nicart.watchbox.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.ui.components.WbAsyncImage

/**
 * A wide "featured" card: backdrop behind, poster and text in front.
 *
 * Mirrors Anikage's Featured rail. Distinct from [WbPosterCard] in what it needs
 * rather than only how it looks - it shows a synopsis and a score, so it is only
 * worth using for cards that have been TMDB-enriched. Rails of raw source entries
 * would render an empty text column.
 */
@Composable
fun FeaturedCard(
    card: AnimeCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = FEATURED_CARD_WIDTH,
) {
    val tokens = MaterialTheme.wb

    Box(
        modifier = modifier
            .width(width)
            .aspectRatio(FEATURED_ASPECT)
            .clip(RoundedCornerShape(18.dp))
            .background(tokens.colors.surface)
            .clickable(onClick = onClick),
    ) {
        // The backdrop sits behind everything at low contrast: it sets the mood
        // without competing with the text on top of it.
        card.backdropUrl?.let { backdrop ->
            WbAsyncImage(
                url = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            // Left-weighted scrim: the text column needs the contrast, the right
            // edge can keep more of the image.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0.0f to tokens.colors.background.copy(alpha = 0.92f),
                            0.55f to tokens.colors.background.copy(alpha = 0.72f),
                            1.0f to tokens.colors.background.copy(alpha = 0.35f),
                        ),
                    ),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WbAsyncImage(
                url = card.displayPoster,
                contentDescription = card.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(POSTER_ASPECT)
                    .clip(RoundedCornerShape(10.dp)),
            )

            Column(modifier = Modifier.weight(1f)) {
                card.ratingPercent?.let { percent ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(40.dp))
                            .background(tokens.colors.textPrimary.copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            tint = tokens.colors.warning,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = "$percent%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.colors.warning,
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                }

                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = tokens.colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                // The synopsis is the one flexible element: it gives up its space so a
                // long title never pushes the year/genre/source line out of the card.
                Spacer(Modifier.weight(1f, fill = false))

                if (card.overview.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = card.overview,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val meta = listOfNotNull(
                    card.year,
                    card.genres.firstOrNull(),
                    card.sourceName.takeIf { it.isNotBlank() },
                ).joinToString(" · ")

                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Card width: wide enough for a readable synopsis at body-small. */
val FEATURED_CARD_WIDTH: Dp = 320.dp

/**
 * Landscape, but taller than Anikage's card.
 *
 * At 2.35 the inner height was ~112dp against a ~148dp text stack, so the meta line
 * was clipped. The ratio is derived from the content rather than copied: score pill
 * + two title lines + two synopsis lines + meta, at this theme's line heights.
 */
private const val FEATURED_ASPECT = 1.9f

/** Standard poster ratio for the inset artwork. */
private const val POSTER_ASPECT = 2f / 3f
