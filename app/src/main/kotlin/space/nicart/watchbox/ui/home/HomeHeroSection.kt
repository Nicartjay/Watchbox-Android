package space.nicart.watchbox.ui.home

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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
) {
    if (items.isEmpty()) return

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val layout = rememberHeroLayout(maxWidth, maxHeight)
        val pagerState = rememberPagerState(pageCount = { items.size })

        // Auto-advance, restarted whenever the user lands on a new page.
        LaunchedEffect(pagerState.settledPage, items.size) {
            if (items.size <= 1) return@LaunchedEffect
            delay(HERO_AUTO_SCROLL_MS)
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
                )
            }

            if (items.size > 1) {
                HeroPageIndicators(
                    pagerState = pagerState,
                    count = items.size,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = layout.verticalPadding),
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
) {
    val tokens = MaterialTheme.wb
    val background = MaterialTheme.colorScheme.background
    val fade = (1f - pageOffset.absoluteValue).coerceIn(0f, 1f)

    Box(modifier = Modifier.fillMaxSize()) {
        // Sources only provide a portrait poster, so it is used as the backdrop
        // and cropped. Over-scaled so parallax never exposes an edge.
        WbAsyncImage(
            url = item.posterUrl,
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // No title logos exist in this ecosystem, so the title is always
            // rendered as text.
            Text(
                text = item.title,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                color = tokens.colors.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(12.dp))

            HeroMetaRow(sourceName = item.sourceName)

            Spacer(Modifier.height(14.dp))

            // White pill CTA: onBackground fill with background-coloured label.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(40.dp))
                    .background(tokens.colors.textPrimary)
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 28.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "Watch Now",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = tokens.colors.background,
                )
            }
        }
    }
}

/** Shows which extension provided the entry. */
@Composable
private fun HeroMetaRow(sourceName: String) {
    val tokens = MaterialTheme.wb
    if (sourceName.isBlank()) return

    Text(
        text = sourceName,
        style = MaterialTheme.typography.labelLarge,
        color = tokens.colors.textPrimary.copy(alpha = 0.9f),
        maxLines = 1,
    )
}

/** Dots that stretch 8dp -> 32dp for the active page. */
@Composable
private fun HeroPageIndicators(
    pagerState: PagerState,
    count: Int,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb
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
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(width.dp)
                    .clip(CircleShape)
                    .background(
                        tokens.colors.textPrimary.copy(alpha = 0.35f + 0.57f * active),
                    ),
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
