package space.nicart.watchbox.ui.tv

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.LocalPosterScale
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.tvFocusable
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.ui.components.WbAsyncImage
import space.nicart.watchbox.ui.components.WbEmptyState

/**
 * TV home: a full-screen backdrop with one row of posters along the bottom.
 *
 * The backdrop is the screen, not a banner. Rows sit at the bottom edge so the artwork
 * for the focused title stays almost entirely visible, and scrolling down brings the
 * next row up over it - which is how every leanback interface handles the trade between
 * showing artwork and showing a catalogue.
 *
 * Posters are portrait. A landscape card shows more of a backdrop, but a row of them
 * fits half as many titles and reads as a list of screenshots; the portrait poster is
 * what people recognise a title by.
 */
@Composable
fun TvHomeScreen(
    viewModel: TvHomeViewModel,
    artworkViewModel: TvArtworkViewModel,
    onOpenAnime: (AnimeCard) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focused by artworkViewModel.focused.collectAsStateWithLifecycle()
    val artwork by artworkViewModel.artwork.collectAsStateWithLifecycle()
    val tokens = MaterialTheme.wb

    val gridState = rememberLazyGridState()

    /**
     * How far the grid has scrolled, as 0..1.
     *
     * Drives the backdrop fade. Measured against one screen's worth of travel rather
     * than the whole list: the artwork should be gone by the time the user is browsing
     * the grid, not fade imperceptibly across hundreds of items.
     */
    val scrollProgress by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()
            when {
                first == null -> 0f
                // Past the first item, the artwork is fully covered.
                gridState.firstVisibleItemIndex > 0 -> 1f
                else -> (gridState.firstVisibleItemScrollOffset / FADE_DISTANCE_PX)
                    .coerceIn(0f, 1f)
            }
        }
    }

    if (state.hasNoSources) {
        TvHomeEmpty(onOpenSettings = onOpenSettings, modifier = modifier)
        return
    }

    // Seeded from the first card so the backdrop is populated before the D-pad has
    // touched anything, rather than opening on flat black.
    //
    // Resolved through the artwork map, not taken raw: the state's cards carry only what
    // the source returned, and the TMDB logo and backdrop live in that map. Using the
    // raw card meant the hero showed a typeset title until the user moved focus, even
    // though the logo had already been fetched.
    val seed = state.firstCard()
    val backdrop = focused ?: seed?.let { artwork[it.key] ?: it }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val topInset = rowsTopInset(maxHeight)

        TvBackdrop(card = backdrop, fade = scrollProgress)

        // Title block sits in the upper-left third, clear of both the rail and the rows.
        // Rows first, so the title block and picker draw over them. The rows fill the
        // screen - their top inset is what pushes the first row to the bottom edge - so
        // composing them last would put a transparent sheet over the whole upper area
        // and hide everything beneath it.
        TvHomeRows(
            state = state,
            artwork = artwork,
            gridState = gridState,
            topInset = topInset,
            onFocus = artworkViewModel::onFocus,
            onPrefetch = artworkViewModel::onRowVisible,
            onOpenAnime = onOpenAnime,
            onLoadMore = viewModel::loadMoreLatest,
        )

        // Faded out with the backdrop it sits on: text over a black background that no
        // longer shows the artwork it describes is just clutter.
        TvBackdropDetail(
            card = backdrop,
            modifier = Modifier
                .align(Alignment.TopStart)
                .alpha(1f - scrollProgress)
                .padding(start = TV_CONTENT_START, top = 80.dp, end = 48.dp),
        )

    }
}

@Composable
private fun TvHomeEmpty(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(start = TV_CONTENT_START, end = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        WbEmptyState(
            title = stringResource(R.string.empty_no_repos_title),
            body = stringResource(R.string.empty_no_repos_body),
            actionLabel = stringResource(R.string.action_add_repository),
            onAction = onOpenSettings,
        )
    }
}

/**
 * Height of the Popular row: label, spacing, poster, and its title.
 *
 * Computed rather than measured so it can be used to derive the top inset before the row
 * has been laid out.
 */
@Composable
private fun popularRowHeight(): Dp {
    val posterHeight = (POSTER_WIDTH * LocalPosterScale.current) / POSTER_ASPECT
    return POPULAR_LABEL_HEIGHT + POPULAR_LABEL_GAP + (POPULAR_ROW_VERTICAL_PADDING * 2) +
        posterHeight + POPULAR_CARD_LABEL_HEIGHT
}

/**
 * Top inset that rests the Popular row on the bottom edge of [viewportHeight].
 *
 * Takes the viewport height as measured by the caller rather than reading it from the
 * configuration: the theme installs a scaled density for the UI scale setting, so the
 * configuration's screen height is in unscaled dp and does not match the dp the layout
 * is actually working in.
 *
 * Floored so a large poster scale cannot push the row off-screen and out of reach.
 */
@Composable
private fun rowsTopInset(viewportHeight: Dp): Dp =
    (viewportHeight - popularRowHeight() - POPULAR_BOTTOM_GAP)
        .coerceAtLeast(MIN_ROWS_TOP_INSET)

/**
 * Full-bleed backdrop for the focused title.
 *
 * Uses the wide TMDB backdrop, not the portrait poster the cards show: a poster cropped
 * to 16:9 loses most of the frame, usually including the subject.
 */
@Composable
private fun TvBackdrop(card: AnimeCard?, fade: Float) {
    val tokens = MaterialTheme.wb

    Box(modifier = Modifier.fillMaxSize()) {
        WbAsyncImage(
            url = card?.backdropUrl ?: card?.posterUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Left gradient protects the title block; the bottom one carries the row.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to tokens.colors.background.copy(alpha = 0.92f),
                        0.5f to tokens.colors.background.copy(alpha = 0.45f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to tokens.colors.background.copy(alpha = 0.35f),
                        0.35f to Color.Transparent,
                        0.72f to tokens.colors.background.copy(alpha = 0.72f),
                        1f to tokens.colors.background,
                    ),
                ),
        )

        // Scroll-driven fade to black. A solid overlay whose opacity follows the scroll,
        // rather than moving the image: the artwork belongs to the focused title, and
        // once the user is browsing the grid it is no longer what they are looking at.
        // Capped just below fully opaque so the transition never looks like a hard cut.
        if (fade > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(tokens.colors.background.copy(alpha = fade * MAX_FADE)),
            )
        }
    }
}

/** Title logo or text, plus a metadata line, for the focused card. */
@Composable
private fun TvBackdropDetail(card: AnimeCard?, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.wb
    if (card == null) return

    Column(modifier = modifier.fillMaxWidth(0.5f)) {
        if (card.logoUrl != null) {
            WbAsyncImage(
                url = card.logoUrl,
                contentDescription = card.title,
                contentScale = ContentScale.Fit,
                // Left, not the default centre: Fit letterboxes, and a centred logo
                // reads as an indent beside left-aligned text.
                alignment = Alignment.CenterStart,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .height(HERO_LOGO_HEIGHT),
            )
        } else {
            Text(
                text = card.title,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = tokens.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(12.dp))

        val meta = listOfNotNull(
            card.year?.takeIf { it.isNotBlank() },
            card.genres.take(2).joinToString(" · ").takeIf { it.isNotBlank() },
        ).joinToString("   ·   ")

        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Popular as a row, then Latest as a paging grid.
 *
 * One scrolling container for both, so the D-pad moves continuously from the Popular row
 * into the grid rather than crossing a boundary between two independently scrolling
 * lists - which on a remote reads as focus getting stuck.
 *
 * Latest pages as focus approaches the end. Driven by focus position rather than scroll
 * offset because with a D-pad the list only scrolls *because* focus moved, so watching
 * focus fires earlier and more directly.
 */
@Composable
private fun TvHomeRows(
    state: TvHomeState,
    artwork: Map<String, AnimeCard>,
    gridState: LazyGridState,
    topInset: Dp,
    onFocus: (AnimeCard) -> Unit,
    onPrefetch: (String, List<AnimeCard>) -> Unit,
    onOpenAnime: (AnimeCard) -> Unit,
    onLoadMore: () -> Unit,
) {
    val tokens = MaterialTheme.wb

    val shouldAppend by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            total > 0 && last >= total - LATEST_COLUMNS * 2
        }
    }
    LaunchedEffect(shouldAppend) {
        if (shouldAppend) onLoadMore()
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(LATEST_COLUMNS),
        modifier = Modifier.fillMaxSize(),
        // The top inset is what places the Popular row at the bottom edge, leaving
        // everything above it as visible backdrop. Derived from the row's own height
        // against the screen, not a fixed number: a constant that happened to look right
        // at one poster scale left the row overflowing the screen at another, and an
        // overflowing row forces a scroll the moment it takes focus - which dragged the
        // backdrop away exactly when the user was trying to look at it.
        contentPadding = PaddingValues(
            // Clears the navigation rail, which overlays the content. Without this the
            // grid's first column drew underneath it.
            start = TV_CONTENT_START,
            end = 48.dp,
            top = topInset,
            bottom = 48.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (state.popular.isNotEmpty()) {
            item(key = "popular-row", span = { GridItemSpan(maxLineSpan) }) {
                LaunchedEffect(state.selected?.id) {
                    onPrefetch("tv-popular-${state.selected?.id}", state.popular)
                }

                TvPortraitRow(
                    title = stringResource(R.string.tv_row_popular),
                    items = state.popular,
                    artwork = artwork,
                    onFocus = onFocus,
                    onClick = onOpenAnime,
                )
            }
        }

        if (state.latest.isNotEmpty()) {
            item(key = "latest-label", span = { GridItemSpan(maxLineSpan) }) {
                // Keyed by size so each appended page is enriched as it arrives. Latest
                // cards do not report focus, so this is the only thing that fetches their
                // posters.
                LaunchedEffect(state.latest.size) {
                    onPrefetch("tv-latest-${state.latest.size}", state.latest)
                }

                Text(
                    text = stringResource(R.string.tv_row_latest),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                    // No start padding: the grid's contentPadding already applies it to
                    // every item, and adding it here would double the inset.
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            items(items = state.latest, key = { it.key }) { card ->
                TvGridPortraitCard(
                    card = artwork[card.key] ?: card,
                    onClick = { onOpenAnime(card) },
                )
            }
        }

        if (state.isAppending) {
            item(key = "appending", span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = tokens.colors.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }
}

/**
 * A Latest grid cell.
 *
 * Fills its column rather than taking a fixed width, so the grid controls the size and
 * the poster scale reaches it through the column count.
 */
@Composable
private fun TvGridPortraitCard(
    card: AnimeCard,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Column(
        modifier = Modifier.padding(start = 0.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(POSTER_ASPECT)
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.colors.surfaceCard)
                .tvFocusable(interaction, RoundedCornerShape(10.dp))
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                ),
        ) {
            WbAsyncImage(
                url = card.tmdbPosterUrl ?: card.posterUrl,
                contentDescription = card.title,
                contentScale = ContentScale.Crop,
                fallbackLabel = card.title,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Text(
            text = card.title,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

}

/** A row of portrait posters. */
@Composable
fun TvPortraitRow(
    title: String,
    items: List<AnimeCard>,
    onClick: (AnimeCard) -> Unit,
    artwork: Map<String, AnimeCard> = emptyMap(),
    onFocus: (AnimeCard) -> Unit = {},
) {
    val tokens = MaterialTheme.wb

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textPrimary,
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            // Room for the focused card to scale without being clipped by the row.
            // Vertical only: this row is a full-span item inside the grid, which already
            // applies the horizontal inset.
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(items = items, key = { it.key }) { card ->
                TvPortraitCard(
                    card = artwork[card.key] ?: card,
                    onFocus = { onFocus(card) },
                    onClick = { onClick(card) },
                )
            }
        }
    }
}

@Composable
private fun TvPortraitCard(
    card: AnimeCard,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()
    val width = POSTER_WIDTH * LocalPosterScale.current

    Column(
        modifier = Modifier.width(width),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(POSTER_ASPECT)
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.colors.surfaceCard)
                .tvFocusable(interaction, RoundedCornerShape(10.dp))
                .clickable(
                    interactionSource = interaction,
                    // The ripple is invisible at TV distance; the border and scale from
                    // tvFocusable are the affordance.
                    indication = null,
                    onClick = onClick,
                ),
        ) {
            WbAsyncImage(
                // TMDB's portrait poster first: it is cleaner and consistently sized,
                // where a source's own artwork varies wildly in crop and quality.
                url = card.tmdbPosterUrl ?: card.posterUrl,
                contentDescription = card.title,
                contentScale = ContentScale.Crop,
                fallbackLabel = card.title,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Text(
            text = card.title,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    TvFocusReporter(interaction = interaction, onFocused = onFocus)
}




/** Bridges focus on [interaction] to a callback, without a second focus modifier. */
@Composable
private fun TvFocusReporter(
    interaction: MutableInteractionSource,
    onFocused: () -> Unit,
) {
    val isFocused by interaction.collectIsFocusedAsState()

    LaunchedEffect(isFocused) {
        if (isFocused) onFocused()
    }
}

private val POSTER_WIDTH = 150.dp

/** 2:3, the standard poster ratio. */
private const val POSTER_ASPECT = 0.667f

private val HERO_LOGO_HEIGHT = 96.dp

/**
 * How far down the first row starts.
 *
 * Sized so the first row's posters end near the bottom edge, leaving the rest of the
 * screen as backdrop. A 1080p panel is 540dp tall and the row block - title, poster,
 * label - is about 190dp, so the row begins around 330dp down.
 *
 * Deliberately a fixed value rather than a fraction of the viewport: it is measured
 * against the row's own height, which does not scale with the screen.
 */
/**
 * Smallest allowed top inset, for when the poster scale is large enough that the row
 * cannot fit under the backdrop.
 */
private val MIN_ROWS_TOP_INSET = 120.dp

/** Pieces of the Popular row's height, kept beside the row that uses them. */
private val POPULAR_LABEL_HEIGHT = 28.dp
private val POPULAR_LABEL_GAP = 10.dp
private val POPULAR_ROW_VERTICAL_PADDING = 8.dp
private val POPULAR_CARD_LABEL_HEIGHT = 28.dp

/** Gap between the Popular row and the bottom edge. */
private val POPULAR_BOTTOM_GAP = 24.dp

/** Columns in the Latest grid. Fewer than a phone: a D-pad crosses one per press. */
private const val LATEST_COLUMNS = 6

/**
 * Scroll distance over which the backdrop fades, in pixels.
 *
 * One row's worth of travel. Deliberately short: the fade is a transition into browsing,
 * not a slow dissolve that leaves the artwork half-visible behind a grid.
 */
private const val FADE_DISTANCE_PX = 420f

/** Just short of opaque, so the transition never reads as a hard cut. */
private const val MAX_FADE = 0.97f
