package space.nicart.watchbox.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.LocalIndication
import space.nicart.watchbox.core.ui.adaptiveFocus
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocalMovies
import androidx.compose.material.icons.rounded.Reviews
import androidx.compose.material.icons.rounded.StarRate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.data.remote.ExternalRating
import space.nicart.watchbox.data.remote.RatingSource
import space.nicart.watchbox.data.remote.Trailer
import space.nicart.watchbox.domain.Studio
import space.nicart.watchbox.ui.extensions.ExtensionIcon
import space.nicart.watchbox.domain.AnimeDetail
import space.nicart.watchbox.ui.components.WbAsyncImage
import space.nicart.watchbox.ui.components.WbBackButton
import android.widget.Toast
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.ui.platform.LocalContext
import space.nicart.watchbox.ui.components.openInBrowser

/**
 * Detail-screen building blocks.
 * Ported from NuvioMobile `features/details/components/`.
 */

/** Hero height: phone `width * 1.33` clamped 420..760dp (`DetailHero.kt:270-275`). */
fun detailHeroHeight(maxWidth: Dp, isTablet: Boolean): Dp = if (isTablet) {
    (maxWidth.value * 0.42f).dp.coerceIn(300.dp, 420.dp)
} else {
    (maxWidth.value * 1.33f).dp.coerceIn(420.dp, 760.dp)
}

/**
 * Hero: full-bleed backdrop with a 7-stop scrim, then the logo/title and genre
 * line bottom-centred inside it (`DetailHero.kt`).
 */
@UnstableApi
@Composable
fun DetailHero(
    detail: AnimeDetail,
    heroHeight: Dp,
    scrollOffset: Float,
    isTablet: Boolean,
    contentMaxWidth: Dp,
    modifier: Modifier = Modifier,
    /** Hero trailer, when one resolved and the setting allows it. */
    trailer: Trailer? = null,
) {
    val tokens = MaterialTheme.wb
    val background = MaterialTheme.colorScheme.background
    val horizontalPadding = if (isTablet) 32.dp else 18.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heroHeight)
            // Required: the backdrop is parallaxed with translationY, which draws
            // outside these bounds unless clipped, bleeding the image through the
            // action row and metadata below.
            .clipToBounds(),
    ) {
        WbAsyncImage(
            url = detail.heroImage,
            contentDescription = detail.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.08f
                    scaleY = 1.08f
                    // Parallax at half scroll speed.
                    translationY = scrollOffset * 0.5f
                },
        )

        // Over the backdrop, under the scrim. Above the image so it can cover it once
        // playing; below the scrim so the title and buttons stay legible against
        // whatever the video happens to be showing.
        //
        // Parallaxed with the same transform as the backdrop, or the two would
        // separate as the page scrolls.
        HeroTrailerLayer(
            trailer = trailer,
            enabled = true,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.08f
                    scaleY = 1.08f
                    translationY = scrollOffset * 0.5f
                },
        )

        // 7-stop scrim, 320dp tall on phones (`DetailHero.kt:207-225`).
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(if (isTablet) 360.dp else 320.dp)
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        0.16f to background.copy(alpha = 0.04f),
                        0.32f to background.copy(alpha = 0.14f),
                        0.50f to background.copy(alpha = 0.34f),
                        0.68f to background.copy(alpha = 0.62f),
                        0.84f to background.copy(alpha = 0.84f),
                        1.00f to background,
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // TMDB title logo when we have one, else bold display text.
            if (detail.logoUrl != null) {
                WbAsyncImage(
                    url = detail.logoUrl,
                    contentDescription = detail.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth(if (isTablet) 0.56f else 0.6f)
                        .widthIn(max = contentMaxWidth)
                        .height(if (isTablet) 72.dp else 80.dp),
                )
            } else {
                Text(
                    text = detail.title,
                    style = if (isTablet) {
                        MaterialTheme.typography.displaySmall
                    } else {
                        MaterialTheme.typography.displayLarge
                    },
                    fontWeight = FontWeight.Bold,
                    color = tokens.colors.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = contentMaxWidth),
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = detail.genres.take(3).joinToString(" • "),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Collapsed header that fades in once the hero scrolls away
 * (`DetailFloatingHeader.kt`). 56dp bar, back + centred logo + save toggle.
 */
@Composable
fun DetailFloatingHeader(
    detail: AnimeDetail,
    progress: Float,
    inWatchlist: Boolean,
    onBack: () -> Unit,
    onToggleWatchlist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb
    val animated by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(
            durationMillis = if (progress > 0f) 150 else 100,
            easing = LinearOutSlowInEasing,
        ),
        label = "headerProgress",
    )

    if (animated <= 0.01f) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp)
            .graphicsLayer {
                alpha = animated
                translationY = (1f - animated) * -20.dp.toPx()
            }
            .background(tokens.colors.background.copy(alpha = 0.92f * animated))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        WbBackButton(onClick = onBack, size = 40.dp)

        if (detail.logoUrl != null) {
            WbAsyncImage(
                url = detail.logoUrl,
                contentDescription = detail.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .padding(horizontal = 12.dp),
            )
        } else {
            Text(
                text = detail.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
        }

        DetailCircleButton(
            icon = if (inWatchlist) Icons.Filled.Check else Icons.Filled.Add,
            active = inWatchlist,
            onClick = onToggleWatchlist,
        )
    }
}

/**
 * Action row: a white Play pill plus circular secondary actions that expand from
 * a "more" button (`DetailActionButtons.kt`). Button height 52dp on phones.
 */
@Composable
fun DetailActionButtons(
    playLabel: String,
    enabled: Boolean,
    watched: Boolean,
    inWatchlist: Boolean,
    onPlay: () -> Unit,
    onToggleWatched: () -> Unit,
    onToggleWatchlist: () -> Unit,
    /** Opens the title on the source's site; null when it has none. */
    onOpenInBrowser: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isTablet: Boolean = false,
    /** True on a wide hero, where buttons size to their content. */
    compactButtons: Boolean = false,
) {
    val tokens = MaterialTheme.wb
    val buttonHeight = if (isTablet) 56.dp else 52.dp
    var expanded by remember { mutableStateOf(false) }
    val playInteraction = rememberFocusInteraction()
    val moreInteraction = rememberFocusInteraction()

    val menuProgress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "menuProgress",
    )

    // With no episodes there is nothing to play, so the pill is inert.
    if (!enabled) {
        Box(
            modifier = modifier
                .widthIn(max = if (isTablet) 520.dp else 420.dp)
                .fillMaxWidth()
                .height(buttonHeight)
                .clip(RoundedCornerShape(40.dp))
                .background(tokens.colors.surfaceCard),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No episodes",
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textSecondary,
            )
        }
        return
    }

    Box(
        modifier = modifier
            // Content-sized on a wide hero: the stretched pill is a phone treatment,
            // where the row owns the full width. Beside a title it reads as a stray
            // banner rather than a button.
            .then(
                if (compactButtons) {
                    // wrapContentWidth, not an empty Modifier: the parent Box in
                    // DetailScreen is fillMaxWidth, and without this the Row inherits
                    // that constraint and the Play button stretches the whole screen.
                    Modifier.wrapContentWidth(Alignment.Start)
                } else {
                    Modifier.widthIn(max = if (isTablet) 520.dp else 420.dp).fillMaxWidth()
                },
            )
            .height(buttonHeight),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .then(if (compactButtons) Modifier else Modifier.fillMaxWidth()),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // --- Play: white pill, dark label
            // The outline is drawn in the background colour, inset inside the pill. The
            // pill is filled with textPrimary, so an outline in white - or in the accent,
            // which is white on the default Monochrome theme - is invisible on it.
            Surface(
                modifier = Modifier
                    .then(
                        if (compactButtons) {
                            Modifier.widthIn(min = 200.dp)
                        } else {
                            Modifier.weight(1f)
                        },
                    )
                    .height(buttonHeight)
                    .adaptiveFocus(
                        playInteraction,
                        RoundedCornerShape(40.dp),
                        scale = false,
                        borderColor = tokens.colors.background,
                    ),
                shape = RoundedCornerShape(40.dp),
                color = tokens.colors.textPrimary,
                contentColor = tokens.colors.background,
                interactionSource = playInteraction,
                onClick = onPlay,
            ) {
                Row(
                    // fillMaxHeight, not fillMaxSize, when content-sized: fillMaxSize
                    // expands to the available width, which is what kept the button
                    // stretched across the screen no matter what the wrappers said.
                    modifier = if (compactButtons) {
                        Modifier.fillMaxHeight().padding(horizontal = 28.dp)
                    } else {
                        Modifier.fillMaxSize()
                    },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(if (isTablet) 20.dp else 18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = playLabel,
                        style = if (isTablet) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.titleSmall
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // --- secondary actions, width-animated so they slide out of the pill
            SecondaryAction(
                icon = if (watched) Icons.Rounded.CheckCircle else Icons.Outlined.CheckCircleOutline,
                active = watched,
                progress = menuProgress,
                size = buttonHeight,
                onClick = onToggleWatched,
            )
            Spacer(Modifier.width(12.dp * menuProgress))
            SecondaryAction(
                icon = if (inWatchlist) Icons.Filled.Check else Icons.Filled.Add,
                active = inWatchlist,
                progress = menuProgress,
                size = buttonHeight,
                onClick = onToggleWatchlist,
            )

            // Opens this title on the source's own site. Only when the source exposes an
            // address - a non-HTTP source has no page, and a button that cannot act is
            // worse than one that is absent.
            if (onOpenInBrowser != null) {
                Spacer(Modifier.width(12.dp * menuProgress))
                SecondaryAction(
                    icon = Icons.Rounded.Language,
                    active = false,
                    progress = menuProgress,
                    size = buttonHeight,
                    onClick = onOpenInBrowser,
                )
            }

            Spacer(Modifier.width(12.dp * menuProgress))

            // --- more
            Surface(
                modifier = Modifier
                    .size(buttonHeight)
                    .adaptiveFocus(moreInteraction, CircleShape, scale = false),
                interactionSource = moreInteraction,
                shape = CircleShape,
                color = if (expanded) {
                    tokens.colors.textPrimary
                } else {
                    tokens.colors.surfaceCard.copy(alpha = 0.82f)
                },
                contentColor = if (expanded) tokens.colors.background else tokens.colors.textPrimary,
                onClick = { expanded = !expanded },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.MoreHoriz,
                        contentDescription = "More",
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer { rotationZ = 90f * menuProgress },
                    )
                }
            }
        }
    }
}

@Composable
private fun SecondaryAction(
    icon: ImageVector,
    active: Boolean,
    progress: Float,
    size: Dp,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()
    Box(modifier = Modifier.width(size * progress)) {
        if (progress > 0.01f) {
            Surface(
                modifier = Modifier
                    .size(size)
                    .adaptiveFocus(interaction, CircleShape, scale = false)
                    .graphicsLayer {
                        alpha = progress
                        val s = 0.86f + 0.14f * progress
                        scaleX = s
                        scaleY = s
                    },
                shape = CircleShape,
                color = if (active) tokens.colors.textPrimary else tokens.colors.surfaceCard,
                contentColor = if (active) tokens.colors.background else tokens.colors.textPrimary,
                interactionSource = interaction,
                onClick = onClick,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailCircleButton(
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()
    Box(
        modifier = modifier
            .size(40.dp)
            .adaptiveFocus(interaction, CircleShape, scale = false)
            .clip(CircleShape)
            .background(if (active) tokens.colors.textPrimary else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) tokens.colors.background else tokens.colors.textPrimary,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * Meta + synopsis (`DetailMetaInfo.kt`). Description clamps to 3 lines with a
 * show-more toggle; line height 22sp.
 */
@Composable
fun DetailMetaInfo(
    detail: AnimeDetail,
    modifier: Modifier = Modifier,
    /**
     * The owning extension's icon, shown beside its name.
     *
     * A Drawable rather than a URL: an extension's icon comes from its own APK through the
     * package manager, so there is nothing to fetch.
     */
    sourceIcon: android.graphics.drawable.Drawable? = null,
) {
    val tokens = MaterialTheme.wb
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            detail.metaLine.takeIf { it.isNotBlank() }?.let { meta ->
                Text(
                    text = meta,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = tokens.colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }

            detail.rating.takeIf { it > 0.0 }?.let { rating ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    // Never shrink: squeezing this is what wrapped "8.8" onto
                    // three lines.
                    modifier = Modifier.width(IntrinsicSize.Max),
                ) {
                    // The label keeps TMDB's brand blue; the score is gold.
                    //
                    // Two colours on purpose: the label attributes the number to a source,
                    // and TMDB's blue is how that source is recognised - recolouring it
                    // would misattribute the score to the app. The number itself is gold,
                    // which is what a rating means everywhere else here.
                    Text(
                        text = "TMDB",
                        style = MaterialTheme.typography.labelMedium,
                        color = TMDB_BLUE,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "%.1f".format(rating),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = tokens.colors.warning,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        if (detail.sourceName.isNotBlank() || detail.extras.ratings.isNotEmpty()) {
            // The icon earns its place here: with several extensions installed the name
            // alone is a word the user has to read and match, while the icon is the same
            // mark they picked in the extension list.
            //
            // External scores share the row rather than taking one of their own: they are
            // attribution, the same kind of thing as the source mark, and a row of their
            // own would push the synopsis further down for two short numbers. The row
            // wraps because three scores plus a source name overruns a narrow phone.
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (detail.sourceName.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        // Reuses the extension list's own icon composable, which caches the
                        // drawable's rasterisation - adaptive-icon conversion is not free, and
                        // this sits in a list that recomposes on scroll.
                        if (sourceIcon != null) {
                            ExtensionIcon(
                                drawable = sourceIcon,
                                iconUrl = null,
                                modifier = Modifier
                                    .size(SOURCE_ICON_SIZE)
                                    .clip(RoundedCornerShape(5.dp)),
                            )
                        }

                        Text(
                            text = detail.sourceName,
                            style = MaterialTheme.typography.labelLarge,
                            color = tokens.colors.textMuted,
                        )
                    }
                }

                detail.extras.ratings.forEach { rating ->
                    ExternalRatingChip(rating)
                }
            }
        }

        // The source's own star rating, gold like every other rating here.
        //
        // Several sources lead their description with one, which the parser pulls out so it
        // is not the opening sentence of the synopsis - it was pushing the real text out of
        // the collapsed three lines and rendering in body grey.
        if (detail.sourceStarRating.isNotBlank()) {
            Text(
                text = detail.sourceStarRating,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = tokens.colors.warning,
                maxLines = 1,
            )
        }

        if (detail.description.isNotBlank()) {
            Text(
                text = detail.description,
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = androidx.compose.ui.unit.TextUnit(
                        22f,
                        androidx.compose.ui.unit.TextUnitType.Sp,
                    ),
                ),
                color = tokens.colors.textSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.animateContentSize(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (expanded) "Show less" else "Show more",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }

        // Studio logos, when TMDB matched the title and has artwork for them.
        if (detail.studios.any { it.logoUrl != null }) {
            Spacer(Modifier.height(4.dp))
            StudioLogoRow(studios = detail.studios)
        }

        // Facts the source packed into its description as markdown. Shown as labelled
        // rows rather than left in the synopsis, where the markers rendered verbatim.
        //
        // Two columns, because these are short label/value pairs - a studio name, an air
        // date - and one per full-width row left most of each line empty while pushing the
        // episode list further down the page.
        if (detail.infoFields.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            InfoFieldGrid(fields = detail.infoFields.take(MAX_INFO_FIELDS))
        }

        // Tappable, because a database reference is only useful if it opens. These were
        // previously flattened into the text, leaving "MAL | AniList | AniDB" visible but
        // inert.
        if (detail.infoLinks.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            InfoLinkRow(links = detail.infoLinks)
        }
    }
}

/**
 * Studio logos in a row, capped and scrollable.
 *
 * Only studios TMDB has a logo for are shown. A name-only fallback would mix text and
 * images in one row for no gain - the studio name, when it matters, is already in the
 * info fields below.
 *
 * Logos are white-on-transparent PNGs, so they need no tinting but do need a light
 * backing to stay visible on the dark surface.
 */
@Composable
private fun StudioLogoRow(studios: List<Studio>) {
    val tokens = MaterialTheme.wb

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        studios.filter { it.logoUrl != null }
            .take(MAX_STUDIO_LOGOS)
            .forEach { studio ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.colors.textPrimary.copy(alpha = 0.90f))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    WbAsyncImage(
                        url = studio.logoUrl,
                        contentDescription = studio.name,
                        contentScale = ContentScale.Fit,
                        // Over a light plate, so a filled placeholder would flash a
                        // dark block on every load.
                        transparentPlaceholder = true,
                        modifier = Modifier.height(STUDIO_LOGO_HEIGHT),
                    )
                }
            }
    }
}

/**
 * Info fields in two columns.
 *
 * Paired into rows rather than laid out with a grid: the list is a handful of items, and a
 * lazy grid inside an already-lazy column needs a fixed height, which would either clip the
 * values or leave a gap.
 *
 * An odd final item takes the left column and leaves the right empty, so labels stay aligned
 * down the page instead of the last one centring itself.
 */
@Composable
private fun InfoFieldGrid(fields: List<Pair<String, String>>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        fields.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { (label, value) ->
                    InfoFieldRow(
                        label = label,
                        value = value,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keeps a lone item to the left half rather than letting it stretch.
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** One `Label  Value` row from a source's markdown metadata. */
@Composable
private fun InfoFieldRow(label: String, value: String, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.wb

    // Label above value rather than beside it. At half width a side-by-side pair left the
    // value two or three words per line; stacking gives it the whole column.
    Column(modifier = modifier.padding(top = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.textSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The source's own links as chips.
 *
 * Scrollable rather than wrapped: a title can list half a dozen databases, and a wrapping
 * row would push the episode list down the page for something most people never tap.
 */
@Composable
private fun InfoLinkRow(links: List<Pair<String, String>>) {
    val tokens = MaterialTheme.wb
    val context = LocalContext.current

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        links.take(MAX_INFO_LINKS).forEach { (label, url) ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(40.dp))
                    .background(tokens.colors.surfaceCard)
                    .clickable {
                        if (!context.openInBrowser(url)) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.source_open_site_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Link,
                    contentDescription = null,
                    tint = tokens.colors.accent,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Enough for the databases a title actually lists. */
private const val MAX_INFO_LINKS = 6

/** Matches the text beside it rather than the studio logos, which are larger. */
private val SOURCE_ICON_SIZE = 18.dp

/** Logos render small; taller would dominate the synopsis above them. */
private val STUDIO_LOGO_HEIGHT = 18.dp

/** Enough for the studios that matter without a scrolling wall of co-producers. */
private const val MAX_STUDIO_LOGOS = 5

/** Sources can emit a long tail of labels; the useful ones come first. */
private const val MAX_INFO_FIELDS = 5


/** TMDB's brand blue, for the label that attributes a score to them. */
private val TMDB_BLUE = Color(0xFF01B4E4)

/**
 * One external score: a mark for the publisher, then the figure.
 *
 * The publisher is shown as an icon rather than its name. Three names plus three
 * numbers plus a source row is a lot of text for one line on a phone, and the
 * icons carry the publisher's own colour, which is what actually distinguishes
 * them at a glance.
 *
 * Generic Material glyphs, deliberately, not the publishers' logos: those are
 * trademarks with their own usage terms, and bundling them in a released APK is a
 * licensing question rather than a design one. The colour does the attributing.
 *
 * The value keeps its units (`81%`, `8.8/10`, `67/100`) rather than being
 * normalised to one scale. A Tomatometer percentage and an IMDb mean out of ten
 * measure different things, and rendering both as a bare number would invite a
 * comparison that does not hold.
 */
@Composable
private fun ExternalRatingChip(rating: ExternalRating) {
    val tokens = MaterialTheme.wb
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        // Never shrink: the same reason the TMDB score above sets this - squeezing
        // it wraps a three-character number onto three lines.
        modifier = Modifier.width(IntrinsicSize.Max),
    ) {
        Icon(
            imageVector = rating.source.icon,
            // Named, because the glyph alone does not say which publisher it is -
            // the colour does, and a screen reader cannot see colour.
            contentDescription = rating.source.label,
            tint = rating.source.brandColor,
            modifier = Modifier.size(RATING_ICON_SIZE),
        )
        Text(
            text = rating.display,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = tokens.colors.warning,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Publisher mark. See [ExternalRatingChip] for why these are generic glyphs. */
internal val RatingSource.icon: ImageVector
    get() = when (this) {
        // A star: IMDb's score is a user rating out of ten.
        RatingSource.IMDB -> Icons.Rounded.StarRate
        // Rotten Tomatoes' Tomatometer is the share of favourable *critic* reviews.
        RatingSource.ROTTEN_TOMATOES -> Icons.Rounded.LocalMovies
        // Metacritic is a weighted aggregate of published reviews.
        RatingSource.METACRITIC -> Icons.Rounded.Reviews
    }

/** Publisher colour, which is what identifies the score now the name is gone. */
internal val RatingSource.brandColor: Color
    get() = when (this) {
        RatingSource.IMDB -> IMDB_YELLOW
        RatingSource.ROTTEN_TOMATOES -> RT_RED
        RatingSource.METACRITIC -> METACRITIC_GREEN
    }

/** Slightly larger than the text cap height, so the mark reads as an icon not a bullet. */
private val RATING_ICON_SIZE = 16.dp

/** IMDb's brand yellow. */
private val IMDB_YELLOW = Color(0xFFF5C518)

/** Rotten Tomatoes' brand red. */
private val RT_RED = Color(0xFFFA320A)

/** Metacritic's brand green. */
private val METACRITIC_GREEN = Color(0xFF66CC33)

/** Section title: 20sp SemiBold on phones (`DetailSection.kt`). */
@Composable
fun DetailSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    isTablet: Boolean = false,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge.copy(
            fontSize = androidx.compose.ui.unit.TextUnit(
                if (isTablet) 22f else 20f,
                androidx.compose.ui.unit.TextUnitType.Sp,
            ),
            fontWeight = FontWeight.SemiBold,
        ),
        color = MaterialTheme.wb.colors.textPrimary,
        modifier = modifier,
    )
}

