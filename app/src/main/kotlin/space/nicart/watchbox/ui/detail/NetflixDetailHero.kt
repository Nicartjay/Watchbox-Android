package space.nicart.watchbox.ui.detail

import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.domain.AnimeDetail
import space.nicart.watchbox.ui.components.WbAsyncImage
import space.nicart.watchbox.ui.extensions.ExtensionIconSlot
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon

/**
 * Netflix-style hero for large screens.
 *
 * Structurally different from the phone hero rather than a scaled version of it. The
 * phone stacks a portrait image above the text because there is no width to work with;
 * a television or tablet in landscape has the opposite problem - a full-bleed backdrop
 * with the text overlaid on the left, so the artwork is visible and the copy stays in
 * one readable column.
 *
 * The source badge takes the place of Netflix's "N SERIES" line. Netflix uses it to say
 * whose catalogue you are looking at, which here is the extension - so it pairs the
 * extension's own icon with its name, and the same word ("Series" or "Film") that
 * distinguishes the two content shapes.
 *
 * The title falls back to text when TMDB has no logo. That is the common case for
 * anime, where many titles have no official wordmark, so the text form is the default
 * path rather than an edge case.
 */
@Composable
fun NetflixDetailHero(
    detail: AnimeDetail,
    extensionIcon: Drawable?,
    heroHeight: Dp,
    modifier: Modifier = Modifier,
    /** Left inset. On TV this has to clear the navigation rail. */
    contentPadding: Dp = 48.dp,
) {
    val tokens = MaterialTheme.wb

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heroHeight),
    ) {
        WbAsyncImage(
            url = detail.heroImage,
            contentDescription = detail.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Two gradients. A single diagonal scrim either over-darkens the artwork or
        // leaves the text unreadable, depending on the image - the horizontal one
        // protects the copy, the vertical one blends into the content below.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to tokens.colors.background.copy(alpha = 0.94f),
                        0.45f to tokens.colors.background.copy(alpha = 0.68f),
                        0.85f to Color.Transparent,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to tokens.colors.background.copy(alpha = 0.55f),
                        0.4f to Color.Transparent,
                        0.82f to tokens.colors.background.copy(alpha = 0.85f),
                        1f to tokens.colors.background,
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                // Capped rather than proportional: a line of body text wider than about
                // 60 characters is measurably harder to read, and on a 1920px panel a
                // percentage width blows straight past that.
                .width(TEXT_COLUMN_WIDTH)
                .padding(start = contentPadding, top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            SourceBadge(
                icon = extensionIcon,
                sourceName = detail.sourceName,
                isMovie = detail.isMovie,
            )

            Spacer(Modifier.height(14.dp))

            // The logo when TMDB has one, the title otherwise.
            if (detail.logoUrl != null) {
                WbAsyncImage(
                    url = detail.logoUrl,
                    contentDescription = detail.title,
                    // Fit, never Crop: a logo is mostly transparent and cropping cuts
                    // the wordmark.
                    contentScale = ContentScale.Fit,
                    // Left, not the default centre: Fit letterboxes inside the box, so
                    // a wide logo in a 120dp-tall slot would sit centred and read as an
                    // indent against the left-aligned badge, metadata and buttons.
                    alignment = Alignment.CenterStart,
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .height(LOGO_HEIGHT),
                )
            } else {
                Text(
                    text = detail.title,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = tokens.colors.textPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 52.sp,
                )
            }

            Spacer(Modifier.height(14.dp))

            MetaRow(detail = detail)

            // Gated on the extracted summary, not the raw description: a
            // metadata-only description yields nothing to show, and an empty Text
            // would still take the spacer above it.
            detail.description.firstParagraph().takeIf { it.isNotBlank() }?.let { summary ->
                Spacer(Modifier.height(14.dp))
                Text(
                    // Source descriptions frequently carry markdown and embedded
                    // images; the hero shows a plain summary and leaves the full text
                    // to the body below.
                    text = summary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Extension icon plus name, in place of Netflix's "N SERIES".
 *
 * The icon is the extension's own launcher icon, which is the only artwork a source
 * has - it identifies the catalogue faster than the name at TV viewing distance.
 */
@Composable
private fun SourceBadge(
    icon: Drawable?,
    sourceName: String,
    isMovie: Boolean,
) {
    val tokens = MaterialTheme.wb

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            ExtensionIconSlot(
                drawable = icon,
                iconUrl = null,
                modifier = Modifier.size(BADGE_ICON_SIZE),
            )
        }

        if (sourceName.isNotBlank()) {
            Text(
                text = sourceName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            // Letter-spaced like Netflix's, which is what makes it read as a label
            // rather than part of the title.
            text = if (isMovie) "F I L M" else "S E R I E S",
            style = MaterialTheme.typography.titleSmall,
            color = tokens.colors.textMuted,
            maxLines = 1,
        )
    }
}

/** Year, status, rating and genres on one line. */
@Composable
private fun MetaRow(detail: AnimeDetail) {
    val tokens = MaterialTheme.wb

    val parts = buildList {
        detail.year?.takeIf { it.isNotBlank() }?.let(::add)
        // Episode count is meaningless for a film.
        if (!detail.isMovie) add("${detail.episodes.size} episodes")
        detail.genres.take(3).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(" · ")) }
    }

    if (parts.isEmpty() && detail.rating <= 0.0) return

    // The rating is drawn separately from the rest of the line so it can be gold.
    //
    // It was previously concatenated into the same string, which meant it inherited the
    // muted grey of the surrounding metadata - a rating reads as a value, not as another
    // dot-separated fact, and gold is what one means everywhere else in the app.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (detail.rating > 0.0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    tint = tokens.colors.warning,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "%.1f".format(detail.rating),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = tokens.colors.warning,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        if (parts.isEmpty()) return@Row

        Text(
            text = parts.joinToString("   ·   "),
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * First real paragraph of a description.
 *
 * Source descriptions often begin with markdown metadata blocks and embedded images.
 * Taking everything up to the first blank line yields the human-written summary without
 * needing to parse the markdown.
 *
 * Returns empty rather than falling back to the whole string when every paragraph is
 * markdown: a description that is only metadata has no summary to show, and printing
 * the raw `**Type:** ...` block reads as broken text.
 */
private fun String.firstParagraph(): String =
    split("\n\n", "\\n\\n")
        .firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("*") }
        ?.replace("\\n", " ")
        ?.trim()
        .orEmpty()

private val TEXT_COLUMN_WIDTH = 620.dp
private val LOGO_HEIGHT = 120.dp
private val BADGE_ICON_SIZE = 30.dp
