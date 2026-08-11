package space.nicart.watchbox.ui.tv

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.layout.layout
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
import space.nicart.watchbox.data.local.WatchHistoryEntry
import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.ui.components.WbAsyncImage
import space.nicart.watchbox.ui.components.WbEmptyState
import space.nicart.watchbox.ui.components.WbProgressBar
import space.nicart.watchbox.core.ui.LocalLayoutMetrics
import kotlinx.coroutines.delay
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

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
    onResume: (WatchHistoryEntry) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    val focused by artworkViewModel.focused.collectAsStateWithLifecycle()
    val artwork by artworkViewModel.artwork.collectAsStateWithLifecycle()
    val lastOpened by artworkViewModel.lastOpened.collectAsStateWithLifecycle()
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
        val topInset = rowsTopInset(maxHeight, hasContinueRow = continueWatching.isNotEmpty())

        TvBackdrop(card = backdrop, fade = scrollProgress)

        // Title block sits in the upper-left third, clear of both the rail and the rows.
        // Rows first, so the title block and picker draw over them. The rows fill the
        // screen - their top inset is what pushes the first row to the bottom edge - so
        // composing them last would put a transparent sheet over the whole upper area
        // and hide everything beneath it.
        TvHomeRows(
            state = state,
            continueWatching = continueWatching,
            artwork = artwork,
            gridState = gridState,
            topInset = topInset,
            onFocus = artworkViewModel::onFocus,
            onPrefetch = artworkViewModel::onRowVisible,
            // The row is recorded alongside the card so focus returns to the row it was
            // opened from, not to the other row that happens to list the same title.
            onOpenAnime = { row, card ->
                artworkViewModel.onOpen(row, card)
                onOpenAnime(card)
            },
            onResume = onResume,
            onRemove = { viewModel.removeFromHistory(it.key) },
            onLoadMore = viewModel::loadMoreLatest,
            openedKey = lastOpened,
            onFocusRestored = artworkViewModel::onFocusRestored,
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
 * Height of the Continue Watching row, built from the same pieces as [popularRowHeight].
 *
 * Its cards are 16:9 rather than 2:3, so it is materially shorter than the Popular row -
 * which matters because whichever row comes first is what the top inset is measured
 * against.
 */
@Composable
private fun continueRowHeight(): Dp {
    val cardHeight = (CONTINUE_CARD_WIDTH * LocalPosterScale.current) / CONTINUE_CARD_ASPECT
    return POPULAR_LABEL_HEIGHT + POPULAR_LABEL_GAP + (POPULAR_ROW_VERTICAL_PADDING * 2) +
        cardHeight + POPULAR_CARD_LABEL_HEIGHT
}

/**
 * Top inset that rests the first row on the bottom edge of [viewportHeight].
 *
 * Takes the viewport height as measured by the caller rather than reading it from the
 * configuration: the theme installs a scaled density for the UI scale setting, so the
 * configuration's screen height is in unscaled dp and does not match the dp the layout
 * is actually working in.
 *
 * Measured against whichever row is actually first: with history present that is the
 * shorter Continue Watching row, and using Popular's height regardless would leave it
 * hanging well above the bottom edge.
 *
 * Floored so a large poster scale cannot push the row off-screen and out of reach.
 */
@Composable
private fun rowsTopInset(viewportHeight: Dp, hasContinueRow: Boolean): Dp {
    val firstRowHeight = if (hasContinueRow) continueRowHeight() else popularRowHeight()
    return (viewportHeight - firstRowHeight - POPULAR_BOTTOM_GAP)
        .coerceAtLeast(MIN_ROWS_TOP_INSET)
}

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
    continueWatching: List<WatchHistoryEntry>,
    artwork: Map<String, AnimeCard>,
    gridState: LazyGridState,
    topInset: Dp,
    onFocus: (AnimeCard) -> Unit,
    onPrefetch: (String, List<AnimeCard>) -> Unit,
    onOpenAnime: (String, AnimeCard) -> Unit,
    onResume: (WatchHistoryEntry) -> Unit,
    onRemove: (WatchHistoryEntry) -> Unit,
    onLoadMore: () -> Unit,
    openedKey: String?,
    onFocusRestored: () -> Unit,
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
        if (continueWatching.isNotEmpty()) {
            item(key = "continue-row", span = { GridItemSpan(maxLineSpan) }) {
                // Keyed by the entry set so a title that appears after watching gets its
                // backdrop fetched. These cards do not report focus - the hero stays on
                // the catalogue rows - so this is the only thing that enriches them.
                LaunchedEffect(continueWatching.size) {
                    onPrefetch(
                        "tv-continue-${continueWatching.size}",
                        continueWatching.map { it.toCard() },
                    )
                }

                TvContinueRow(
                    entries = continueWatching,
                    artwork = artwork,
                    onResume = onResume,
                    onRemove = onRemove,
                )
            }
        }

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
                    onClick = { onOpenAnime(ROW_POPULAR, it) },
                    rowKey = ROW_POPULAR,
                    openedKey = openedKey,
                    onFocusRestored = onFocusRestored,
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
                    onClick = { onOpenAnime(ROW_LATEST, card) },
                    isOpened = openedKey == "$ROW_LATEST::${card.key}",
                    onFocusRestored = onFocusRestored,
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
/**
 * Reclaims focus for the card the user opened, once, after returning from Detail.
 *
 * Pushing Detail disposes the home subtree, so nothing here remembers what was focused;
 * on the way back the shell re-homes focus to the navigation rail and the user is dumped
 * at the top of the screen having lost their place. The opened card's key is kept in the
 * artwork view model, which outlives the navigation, and whichever card matches it claims
 * focus as it composes.
 *
 * [onRestored] clears the key so this happens exactly once. Without it the card would drag
 * focus back every recomposition, including while the user was trying to move off it.
 *
 * The retry mirrors tvInitialFocus: the grid restores its scroll position over the frames
 * after this runs, so the card may not have a focusable node yet, and requestFocus reports
 * success even when it does not.
 */
@Composable
private fun Modifier.restoreFocusIfOpened(
    isOpened: Boolean,
    onRestored: () -> Unit,
): Modifier {
    if (!LocalLayoutMetrics.current.isFocusDriven || !isOpened) return this

    val requester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        repeat(RESTORE_FOCUS_ATTEMPTS) {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
            if (hasFocus) {
                onRestored()
                return@LaunchedEffect
            }
            delay(RESTORE_FOCUS_RETRY_MS)
        }
        // Given up on: the card scrolled out of the restored viewport, or the row it was
        // in is gone. Cleared anyway so a stale key cannot steal focus later.
        onRestored()
    }

    return this
        .focusRequester(requester)
        .onFocusChanged { hasFocus = it.isFocused }
}

@Composable
private fun TvGridPortraitCard(
    card: AnimeCard,
    onClick: () -> Unit,
    isOpened: Boolean = false,
    onFocusRestored: () -> Unit = {},
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
                // Before clip: clipping first would cut the scaled edge and the outline.
                .tvFocusable(interaction, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.colors.surfaceCard)
                .restoreFocusIfOpened(isOpened, onFocusRestored)
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
    rowKey: String = "",
    openedKey: String? = null,
    onFocusRestored: () -> Unit = {},
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
            // Bled outward, then padded back in by the same amount. A LazyRow clips to
            // its own bounds, and the grid's content padding puts those bounds exactly on
            // the first card's edge - so the focus outline and the scaled-up edge of the
            // first and last cards were cut off, while the cards between them were fine.
            // Widening the row and insetting its content leaves the cards where they were
            // and gives the outline somewhere to draw.
            modifier = Modifier.focusBleed(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(
                horizontal = FOCUS_BLEED,
                vertical = FOCUS_BLEED,
            ),
        ) {
            items(items = items, key = { it.key }) { card ->
                TvPortraitCard(
                    card = artwork[card.key] ?: card,
                    onFocus = { onFocus(card) },
                    onClick = { onClick(card) },
                    isOpened = openedKey == "$rowKey::${card.key}",
                    onFocusRestored = onFocusRestored,
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
    isOpened: Boolean = false,
    onFocusRestored: () -> Unit = {},
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
                // Before clip: clipping first would cut the scaled edge and the outline.
                .tvFocusable(interaction, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.colors.surfaceCard)
                .restoreFocusIfOpened(isOpened, onFocusRestored)
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

/**
 * Continue Watching as a row of landscape cards.
 *
 * Landscape, unlike every other row on this screen, and deliberately: a resume card is
 * about the episode you were part-way through, so it carries a progress bar and an
 * episode label, and that furniture needs horizontal room. The different shape also
 * marks the row as "yours" rather than another slice of the catalogue.
 */
@Composable
private fun TvContinueRow(
    entries: List<WatchHistoryEntry>,
    artwork: Map<String, AnimeCard>,
    onResume: (WatchHistoryEntry) -> Unit,
    onRemove: (WatchHistoryEntry) -> Unit,
) {
    val tokens = MaterialTheme.wb

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.section_continue_watching),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textPrimary,
        )

        LazyRow(
            // See TvPortraitRow: the row clips to its own bounds, so it is bled outward
            // and its content inset by the same amount to leave the focus outline room.
            modifier = Modifier.focusBleed(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(
                horizontal = FOCUS_BLEED,
                vertical = FOCUS_BLEED,
            ),
        ) {
            items(items = entries, key = { it.key }) { entry ->
                TvContinueCard(
                    entry = entry,
                    // History stores only the source's own poster. The artwork map is
                    // keyed the same way as the card rows, so a resolved backdrop for
                    // this title is reused here rather than fetched again.
                    artwork = artwork[entry.key],
                    onClick = { onResume(entry) },
                    onLongClick = { onRemove(entry) },
                )
            }
        }
    }
}

@Composable
private fun TvContinueCard(
    entry: WatchHistoryEntry,
    artwork: AnimeCard?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()
    val width = CONTINUE_CARD_WIDTH * LocalPosterScale.current

    Column(
        modifier = Modifier.width(width),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CONTINUE_CARD_ASPECT)
                // Before clip: clipping first would cut the scaled edge and the outline.
                .tvFocusable(interaction, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.colors.surfaceCard)
                .combinedClickable(
                    interactionSource = interaction,
                    // Matches the poster cards: at TV distance the border and scale from
                    // tvFocusable are the affordance, and a ripple is invisible.
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        ) {
            WbAsyncImage(
                // Backdrop first here, unlike the portrait rows: this card is 16:9, and
                // a portrait poster cropped to it loses most of the frame. Falls back to
                // the poster stored with the history entry until artwork resolves.
                url = artwork?.cardBackdropUrl ?: artwork?.backdropUrl ?: entry.posterUrl,
                contentDescription = entry.title,
                contentScale = ContentScale.Crop,
                fallbackLabel = entry.title,
                modifier = Modifier.fillMaxSize(),
            )

            // Scrim under the label and progress bar, so both stay legible over a bright
            // frame.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.5f)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                        ),
                    ),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                entry.episodeLabel.takeIf { it.isNotBlank() }?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                WbProgressBar(progress = entry.progress, modifier = Modifier.fillMaxWidth())
            }
        }

        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Continue Watching card width.
 *
 * Wider than a poster so the 16:9 frame ends up a similar height to the portrait cards,
 * keeping the row's overall block consistent with the rest of the screen.
 */
private fun WatchHistoryEntry.toCard(): AnimeCard = AnimeCard(
    sourceId = sourceId,
    url = animeUrl,
    title = title,
    posterUrl = posterUrl,
    sourceName = sourceName,
)

/**
 * Slack a row leaves around its cards for the focus outline and scale.
 *
 * A focused card grows by 6% and is stroked on its edge, both of which reach outside the
 * card's own bounds. The largest card here is 225dp tall, so 6% is under 7dp per side;
 * 12dp covers that and the stroke with room to spare.
 */
private val FOCUS_BLEED = 12.dp

private val CONTINUE_CARD_WIDTH = 220.dp

/** 16:9, expressed the way [aspectRatio] wants it. */
private const val CONTINUE_CARD_ASPECT = 1.777f

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

/** The row's vertical content padding, which is the focus slack it leaves. */
private val POPULAR_ROW_VERTICAL_PADDING = FOCUS_BLEED
private val POPULAR_CARD_LABEL_HEIGHT = 28.dp

/**
 * Widens a row past its parent's padding by [FOCUS_BLEED] on both sides.
 *
 * Paired with matching content padding on the row itself, which puts the cards back
 * where they were. Only the row's clip bounds move, so the outline of the first and last
 * card has somewhere to draw. Not a negative padding modifier: those shift the content
 * too, which would pull the first card under the navigation rail.
 */
private fun Modifier.focusBleed(): Modifier = layout { measurable, constraints ->
    val bleed = FOCUS_BLEED.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(maxWidth = constraints.maxWidth + bleed),
    )
    // Reports the original width so the row still occupies its allotted space in the
    // grid, and offsets the wider content back over the padding it was given.
    layout(constraints.maxWidth, placeable.height) {
        placeable.place(-FOCUS_BLEED.roundToPx(), 0)
    }
}

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

/** Retry budget for returning focus to the card that was opened. */
private const val RESTORE_FOCUS_ATTEMPTS = 20
private const val RESTORE_FOCUS_RETRY_MS = 40L

/**
 * Row identifiers for focus restoration.
 *
 * A card key alone is ambiguous - the same title appears in both rows - so the row is
 * part of the identity of "the card that was opened".
 */
private const val ROW_POPULAR = "popular"
private const val ROW_LATEST = "latest"
