package space.nicart.watchbox.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.domain.AnimeDetail
import space.nicart.watchbox.ui.components.WbAsyncImage
import space.nicart.watchbox.ui.components.WbBackButton

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
@Composable
fun DetailHero(
    detail: AnimeDetail,
    heroHeight: Dp,
    scrollOffset: Float,
    isTablet: Boolean,
    contentMaxWidth: Dp,
    modifier: Modifier = Modifier,
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
    modifier: Modifier = Modifier,
    isTablet: Boolean = false,
    /** True on a wide hero, where buttons size to their content. */
    compactButtons: Boolean = false,
) {
    val tokens = MaterialTheme.wb
    val buttonHeight = if (isTablet) 56.dp else 52.dp
    var expanded by remember { mutableStateOf(false) }

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
            Surface(
                modifier = Modifier
                    .then(
                        if (compactButtons) {
                            Modifier.widthIn(min = 200.dp)
                        } else {
                            Modifier.weight(1f)
                        },
                    )
                    .height(buttonHeight),
                shape = RoundedCornerShape(40.dp),
                color = tokens.colors.textPrimary,
                contentColor = tokens.colors.background,
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
            Spacer(Modifier.width(12.dp * menuProgress))

            // --- more
            Surface(
                modifier = Modifier.size(buttonHeight),
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
    Box(modifier = Modifier.width(size * progress)) {
        if (progress > 0.01f) {
            Surface(
                modifier = Modifier
                    .size(size)
                    .graphicsLayer {
                        alpha = progress
                        val s = 0.86f + 0.14f * progress
                        scaleX = s
                        scaleY = s
                    },
                shape = CircleShape,
                color = if (active) tokens.colors.textPrimary else tokens.colors.surfaceCard,
                contentColor = if (active) tokens.colors.background else tokens.colors.textPrimary,
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
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (active) tokens.colors.textPrimary else Color.Transparent)
            .clickable(onClick = onClick),
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
                        color = tokens.colors.textPrimary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        if (detail.sourceName.isNotBlank()) {
            Text(
                text = detail.sourceName,
                style = MaterialTheme.typography.labelLarge,
                color = tokens.colors.textMuted,
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
    }
}

private val TMDB_BLUE = Color(0xFF01B4E4)

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

