package space.nicart.watchbox.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.delay
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.ui.components.WbAsyncImage
import space.nicart.watchbox.ui.components.WbSkeletonBlock
import kotlin.math.absoluteValue

/**
 * The home hero pager.
 *
 * Ported from NuvioMobile `features/home/components/HomeHeroSection.kt`:
 *  - auto-advances every 8000ms;
 *  - clipped with 28dp bottom corners;
 *  - backdrop base scale 1.14, horizontal parallax 0.055, content parallax 0.18;
 *  - two stacked scrims — a full-height 4-stop gradient plus a 220dp bottom fade;
 *  - logo at 62% width / 2.6 aspect, falling back to bold display text;
 *  - meta line separated by 4dp dots;
 *  - a white "View Details" pill;
 *  - page dots that stretch from 8dp to 32dp when active.
 */
private const val HERO_AUTO_SCROLL_MS = 8_000L
private const val HERO_BACKGROUND_SCALE = 1.14f
private const val HERO_HORIZONTAL_PARALLAX = 0.055f
private const val HERO_CONTENT_PARALLAX = 0.18f

/** Phone hero metrics (`HomeHeroSection.kt:458-537`). */
data class HeroLayout(
    val height: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val bottomFadeHeight: Dp,
    val logoWidthFraction: Float,
)

@Composable
fun rememberHeroLayout(maxWidth: Dp, maxHeight: Dp): HeroLayout = remember(maxWidth, maxHeight) {
    when {
        maxWidth >= 1200.dp -> HeroLayout(
            height = (maxWidth.value * 0.42f).dp.coerceIn(360.dp, 440.dp),
            horizontalPadding = 56.dp,
            verticalPadding = 22.dp,
            bottomFadeHeight = 190.dp,
            logoWidthFraction = 0.58f,
        )

        maxWidth >= 840.dp -> HeroLayout(
            height = (maxWidth.value * 0.46f).dp.coerceIn(340.dp, 420.dp),
            horizontalPadding = 40.dp,
            verticalPadding = 20.dp,
            bottomFadeHeight = 180.dp,
            logoWidthFraction = 0.56f,
        )

        maxWidth >= 600.dp -> HeroLayout(
            height = (maxWidth.value * 0.58f).dp.coerceIn(320.dp, 380.dp),
            horizontalPadding = 32.dp,
            verticalPadding = 18.dp,
            bottomFadeHeight = 170.dp,
            logoWidthFraction = 0.54f,
        )

        else -> HeroLayout(
            // Phone: 82% of viewport height, clamped 360..760dp.
            height = (maxHeight.value * 0.82f).dp.coerceIn(360.dp, 760.dp),
            horizontalPadding = 24.dp,
            verticalPadding = 16.dp,
            bottomFadeHeight = 220.dp,
            logoWidthFraction = 0.62f,
        )
    }
}

@Composable
fun HomeHeroSection(
    items: List<AnimeCard>,
    onOpen: (AnimeCard) -> Unit,
    modifier: Modifier = Modifier,
    onMoreInfo: ((AnimeCard) -> Unit)? = null,
) {
    if (items.isEmpty()) return

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val layout = rememberHeroLayout(maxWidth, maxHeight)
        val pagerState = rememberPagerState(pageCount = { items.size })

        // Captured here: BoxWithConstraints' receiver is not in scope inside the pager's
        // page lambda, and the panel's own shape is what decides which artwork fits it.
        val heroIsPortrait = layout.height > maxWidth

        // How far through the current slide's dwell time we are, 0..1.
        //
        // Animated rather than sampled on a timer: one animation drives the fill for
        // the whole dwell, so the indicator cannot drift out of step with the pager
        // the way a separately-ticking progress value would.
        val dwellProgress = remember { Animatable(0f) }

        // Auto-advance, restarted whenever the user lands on a new page.
        //
        // The fill and the page turn are the same effect, so the bar always completes
        // exactly as the slide changes. Keyed on `settledPage`, so a manual swipe
        // restarts the dwell instead of inheriting the previous slide's remainder.
        LaunchedEffect(pagerState.settledPage, items.size) {
            if (items.size <= 1) return@LaunchedEffect
            dwellProgress.snapTo(0f)
            dwellProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = HERO_AUTO_SCROLL_MS.toInt(),
                    easing = LinearEasing,
                ),
            )

            // Cleared before the page turns, not after it lands.
            //
            // `settledPage` only changes once the scroll has finished, so this effect
            // cannot restart until then. Leaving the value at 1f across the transition
            // made the incoming pill grow fully white and then drop to its empty track
            // the moment the scroll settled. Zeroing it here means nothing is filled
            // while the slide is moving.
            dwellProgress.snapTo(0f)

            val next = (pagerState.settledPage + 1) % items.size
            pagerState.animateScrollToPage(next)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(layout.height)
                .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                HeroPage(
                    item = items[page],
                    layout = layout,
                    pageOffset = pagerState.pageOffsetFor(page),
                    onOpen = { onOpen(items[page]) },
                    onMoreInfo = onMoreInfo?.let { handler -> { handler(items[page]) } },
                    isPortrait = heroIsPortrait,
                )
            }

            if (items.size > 1) {
                HeroPageIndicators(
                    pagerState = pagerState,
                    count = items.size,
                    progress = { dwellProgress.value },
                    // Bottom-start, inset by the same horizontal padding as the
                    // content column so the dots line up with the logo above them.
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(
                            start = layout.horizontalPadding,
                            bottom = layout.verticalPadding,
                        ),
                )
            }
        }
    }
}

/** Signed distance of [page] from the current scroll position. */
private fun PagerState.pageOffsetFor(page: Int): Float =
    (currentPage - page) + currentPageOffsetFraction

@Composable
private fun HeroPage(
    item: AnimeCard,
    layout: HeroLayout,
    pageOffset: Float,
    onOpen: () -> Unit,
    onMoreInfo: (() -> Unit)? = null,
    /**
     * True when the panel is taller than it is wide.
     *
     * Decides which artwork fills it. Measured rather than inferred from form factor: a
     * tablet in portrait wants the same treatment as a phone, and a phone in landscape
     * wants the backdrop.
     */
    isPortrait: Boolean = true,
) {
    val tokens = MaterialTheme.wb
    val background = MaterialTheme.colorScheme.background
    val fade = (1f - pageOffset.absoluteValue).coerceIn(0f, 1f)

    Box(modifier = Modifier.fillMaxSize()) {
        // A textless portrait poster in a portrait panel, a wide backdrop in a landscape
        // one. A 16:9 backdrop in the phone's ~0.55:1 hero loses about 69% of its width to
        // the crop, usually including whatever the shot was framed around.
        //
        // Over-scaled either way so parallax never exposes an edge.
        WbAsyncImage(
            url = if (isPortrait) item.portraitHeroImage else item.heroImage,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = HERO_BACKGROUND_SCALE
                    scaleY = HERO_BACKGROUND_SCALE
                    translationX = -pageOffset * size.width * HERO_HORIZONTAL_PARALLAX
                    alpha = 0.35f + 0.65f * fade
                },
        )

        // --- scrim 1: full-height 4-stop wash (`HomeHeroSection.kt:216-229`)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to background.copy(alpha = 0.02f),
                        0.35f to background.copy(alpha = 0.12f),
                        0.65f to background.copy(alpha = 0.34f),
                        1.0f to background.copy(alpha = 0.78f),
                    ),
                ),
        )

        // --- scrim 2: bottom fade to solid so rails blend in
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(layout.bottomFadeHeight)
                .background(
                    Brush.verticalGradient(
                        listOf(background.copy(alpha = 0f), background),
                    ),
                ),
        )

        // --- content
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(
                    horizontal = layout.horizontalPadding,
                    vertical = layout.verticalPadding,
                )
                .padding(bottom = 28.dp)
                .graphicsLayer {
                    translationX = -pageOffset * size.width * HERO_CONTENT_PARALLAX
                    alpha = fade
                },
            // Left-aligned rather than centred: the logo, meta and synopsis read as a
            // block this way, and a centred multi-line synopsis is noticeably harder
            // to scan.
            horizontalAlignment = Alignment.Start,
        ) {
            // Nuvio shows a transparent title logo at 62% width / 2.6 aspect and
            // falls back to bold display text when none exists.
            if (item.logoUrl != null) {
                WbAsyncImage(
                    url = item.logoUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    // Fit letterboxes inside the 2.6 box, and a centred logo reads as
                    // an indent next to the left-aligned text below it.
                    alignment = Alignment.CenterStart,
                    // The logo sits over the backdrop: a filled placeholder would
                    // cover the artwork with a solid block while it loads.
                    transparentPlaceholder = true,
                    modifier = Modifier
                        .fillMaxWidth(layout.logoWidthFraction)
                        .aspectRatio(2.6f),
                )
            } else {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = tokens.colors.textPrimary,
                    textAlign = TextAlign.Start,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Pill-shaped facts: score, year, type. Each is omitted when unknown
            // rather than shown as a placeholder - a row of "N/A" chips is worse
            // than a shorter row.
            HeroFactRow(card = item)

            if (item.genres.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HeroGenreRow(genres = item.genres)
            }

            if (item.overview.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = item.overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textPrimary.copy(alpha = 0.82f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // White pill CTA: onBackground fill with background-coloured label.
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(40.dp))
                        .background(tokens.colors.textPrimary)
                        .clickable(onClick = onOpen)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = tokens.colors.background,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.hero_watch_now),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = tokens.colors.background,
                    )
                }

                // Secondary action, only when the caller wired one up.
                onMoreInfo?.let { moreInfo ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(40.dp))
                            .background(tokens.colors.textPrimary.copy(alpha = 0.18f))
                            .clickable(onClick = moreInfo)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = tokens.colors.textPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(R.string.hero_more_info),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = tokens.colors.textPrimary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Score / year / type pills, mirroring Anikage's hero.
 *
 * The score carries a star and the accent colour because it is the one fact a
 * viewer scans for; the rest are neutral.
 */
@Composable
private fun HeroFactRow(card: AnimeCard) {
    val tokens = MaterialTheme.wb
    val facts = buildList {
        card.ratingPercent?.let { add(HeroFact.Score(it)) }
        card.year?.let { add(HeroFact.Plain(it, Icons.Rounded.CalendarMonth)) }
        add(
            HeroFact.Plain(
                label = if (card.isMovie) {
                    stringResource(R.string.hero_type_movie)
                } else {
                    stringResource(R.string.hero_type_series)
                },
                icon = Icons.Rounded.Tv,
            ),
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        facts.forEach { fact ->
            when (fact) {
                is HeroFact.Score -> HeroPill(
                    label = "${fact.percent}%",
                    icon = Icons.Rounded.Star,
                    // The fixed amber, not the theme accent: a score is a rating, and
                    // the accent changes per theme - a red or purple star reads as a
                    // warning or a brand mark rather than a rating.
                    tint = tokens.colors.warning,
                )

                is HeroFact.Plain -> HeroPill(label = fact.label, icon = fact.icon)
            }
        }
    }
}

private sealed interface HeroFact {
    data class Score(val percent: Int) : HeroFact
    data class Plain(val label: String, val icon: ImageVector) : HeroFact
}

@Composable
private fun HeroPill(
    label: String,
    icon: ImageVector,
    tint: Color? = null,
) {
    val tokens = MaterialTheme.wb
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(40.dp))
            .background(tokens.colors.textPrimary.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint ?: tokens.colors.textPrimary.copy(alpha = 0.9f),
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = tint ?: tokens.colors.textPrimary.copy(alpha = 0.9f),
            maxLines = 1,
        )
    }
}

/** Up to three genre chips; more than that wraps and pushes the CTA off-screen. */
@Composable
private fun HeroGenreRow(genres: List<String>) {
    val tokens = MaterialTheme.wb
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        genres.take(MAX_HERO_GENRES).forEach { genre ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(40.dp))
                    .background(tokens.colors.textPrimary.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = genre,
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textPrimary.copy(alpha = 0.88f),
                    maxLines = 1,
                )
            }
        }
    }
}

private const val MAX_HERO_GENRES = 3

/** Alpha of a dot that is not the current page. */
private const val INACTIVE_DOT_ALPHA = 0.35f

/**
 * Alpha of the selected pill's track.
 *
 * Dimmer than an inactive dot on purpose: the white fill drawn over it supplies the
 * contrast, and a brighter track would leave a full pill and an empty one looking
 * nearly identical.
 */
private const val SELECTED_TRACK_ALPHA = 0.28f

/**
 * `2024 • Action • Cineby`, separated by 4dp dots.
 *
 * Year and genre come from TMDB; the source name is always present so it is
 * clear which extension a title came from.
 */
@Composable
private fun HeroMetaRow(card: AnimeCard) {
    val tokens = MaterialTheme.wb
    val parts = buildList {
        card.year?.let(::add)
        card.genres.firstOrNull()?.let(::add)
        card.sourceName.takeIf { it.isNotBlank() }?.let(::add)
    }
    if (parts.isEmpty()) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        parts.forEachIndexed { index, part ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(tokens.colors.textPrimary.copy(alpha = 0.7f)),
                )
            }
            Text(
                text = part,
                style = MaterialTheme.typography.labelLarge,
                color = tokens.colors.textPrimary.copy(alpha = 0.9f),
                maxLines = 1,
            )
        }
    }
}

/**
 * Dots that stretch 8dp -> 32dp for the active page, the active one filling as its
 * dwell time elapses.
 *
 * The fill doubles as a countdown: it shows both which slide is showing and how long
 * is left on it, so an auto-advance is never a surprise.
 *
 * [progress] is read as a lambda so the fill animates without recomposing this
 * function 60 times a second - only the draw phase re-runs.
 */
@Composable
private fun HeroPageIndicators(
    pagerState: PagerState,
    count: Int,
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb
    val fillColor = tokens.colors.textPrimary

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val active = (1f - (pagerState.currentPage - index +
                pagerState.currentPageOffsetFraction).absoluteValue)
                .coerceIn(0f, 1f)
            val width by animateFloatAsState(
                targetValue = 8f + 24f * active,
                animationSpec = tween(220),
                label = "dotWidth",
            )

            // The track's alpha interpolates on `active` instead of switching at a
            // threshold, so the pill dims smoothly as it grows rather than changing
            // appearance partway through the transition.
            val trackAlpha = lerp(INACTIVE_DOT_ALPHA, SELECTED_TRACK_ALPHA, active)

            // Whether to draw the countdown, kept separate from the track's
            // appearance. It requires the pager to be at rest: mid-scroll the dwell
            // has not restarted, so the fill would show the outgoing slide's
            // remaining time on the incoming pill.
            //
            // Tying the fill and the track to one flag is what produced the reported
            // flash - the track brightened at the same moment the fill vanished, so
            // the pill flared white before dropping back to grey.
            val showsFill = active > 0.5f && !pagerState.isScrollInProgress

            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(width.dp)
                    .clip(CircleShape)
                    .background(tokens.colors.textPrimary.copy(alpha = trackAlpha))
                    .drawWithContent {
                        drawContent()
                        if (!showsFill) return@drawWithContent
                        // Clipped to the pill by the parent's `clip`, so the fill
                        // keeps the rounded ends without a second shape.
                        drawRect(
                            color = fillColor,
                            size = size.copy(
                                width = size.width * progress().coerceIn(0f, 1f),
                            ),
                        )
                    },
            )
        }
    }
}

/** Hero-shaped shimmer used while the feed loads. */
@Composable
fun HomeHeroSkeleton(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val layout = rememberHeroLayout(maxWidth, maxHeight)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(layout.height)
                .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            WbSkeletonBlock(modifier = Modifier.fillMaxSize(), cornerRadius = 0.dp)
            Column(
                modifier = Modifier.padding(bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WbSkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth(layout.logoWidthFraction)
                        .aspectRatio(2.6f),
                    cornerRadius = 12.dp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(52.dp, 72.dp, 40.dp).forEach {
                        WbSkeletonBlock(
                            modifier = Modifier.width(it).height(14.dp),
                            cornerRadius = 999.dp,
                        )
                    }
                }
                WbSkeletonBlock(
                    modifier = Modifier.width(160.dp).height(48.dp),
                    cornerRadius = 40.dp,
                )
            }
        }
    }
}
