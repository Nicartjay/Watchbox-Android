package space.nicart.watchbox.ui.tv

import androidx.compose.animation.Crossfade
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
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import space.nicart.watchbox.core.ui.adaptiveFocus
import space.nicart.watchbox.core.ui.tvInitialFocus
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * TV home: a full-screen backdrop with a spotlight carousel over rows of posters.
 *
 * The backdrop is the screen, not a banner. Rows sit at the bottom edge so the artwork
 * for the focused title stays almost entirely visible, and scrolling down brings the
 * next row up over it - which is how every leanback interface handles the trade between
 * showing artwork and showing a catalogue.
 *
 * Posters are portrait. A landscape card shows more of a backdrop, but a row of them
 * fits half as many titles and reads as a list of screenshots; the portrait poster is
 * what people recognise a title by.
 *
 * ## The carousel
 *
 * The upper-left block - logo, metadata, Play, Details - describes whatever is on the
 * backdrop. At rest that is the spotlight, which rotates through a handful of Popular
 * titles; once focus is in a row the backdrop follows that focus instead.
 *
 * The block is one unit deliberately: the buttons act on the title named directly above
 * them, so there is no reading in which Play starts something other than what the user is
 * looking at. The carousel keeps advancing on its timer whether or not the buttons hold
 * focus, but any input on them restarts the interval, so the title under Play cannot change
 * in the moment between the user deciding to press it and pressing it.
 *
 * [pickerOpen] holds the carousel still while the source drawer covers the hero.
 */
@Composable
fun TvHomeScreen(
    viewModel: TvHomeViewModel,
    artworkViewModel: TvArtworkViewModel,
    onOpenAnime: (AnimeCard) -> Unit,
    onResume: (WatchHistoryEntry) -> Unit,
    onOpenSettings: () -> Unit,
    onPlay: (TvPlayRequest.Ready) -> Unit = {},
    pickerOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    val focused by artworkViewModel.focused.collectAsStateWithLifecycle()
    val artwork by artworkViewModel.artwork.collectAsStateWithLifecycle()
    val lastOpened by artworkViewModel.lastOpened.collectAsStateWithLifecycle()
    val playRequest by viewModel.playRequest.collectAsStateWithLifecycle()

    // Drops the previous source's artwork when the feed switches.
    //
    // The id is passed rather than relied on as the effect key: this composition is disposed
    // and rebuilt whenever Detail is pushed and popped, so the effect re-runs with an unchanged
    // source and the view model has to tell the two apart itself.
    LaunchedEffect(state.selected?.id) {
        artworkViewModel.onSourceChanged(state.selected?.id)
    }

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
        TvHomeEmpty(
            hasNoRepos = state.hasNoRepos,
            // Both branches land in Settings, because that is where the TV build keeps the
            // extension list - there is no separate destination to send the user to.
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )
        return
    }

    val heroItems = remember(state.popular, state.latest) { state.heroItems() }

    // Kept in the view model, which survives navigation: a remembered index came back as 0
    // after visiting a detail page, resetting the spotlight and leaving focus restoration
    // with no matching card to return to.
    val heroIndex by viewModel.heroIndex.collectAsStateWithLifecycle()
    LaunchedEffect(heroItems.size) { viewModel.clampHero(heroItems.size) }

    var heroFocused by remember { mutableStateOf(false) }

    // Waits briefly for the enriched card rather than painting the raw one immediately.
    //
    // The fallback to the source entry meant the spotlight painted the extension's portrait
    // poster stretched across the screen and then swapped it for the TMDB backdrop a moment
    // later - a visible flicker on every rotation.
    //
    // Deliberately a timeout rather than waiting indefinitely: a title TMDB has no match for
    // never resolves, and holding the previous slide forever would strand the spotlight on
    // the wrong title. After the grace period the raw entry is shown, which is what this did
    // before - just without the flicker for the common case where artwork does arrive.
    val rawHero = heroItems.getOrNull(heroIndex)
    val enrichedHero = rawHero?.let { artwork[it.key] }

    var artworkGraceElapsed by remember(rawHero?.key) { mutableStateOf(false) }
    LaunchedEffect(rawHero?.key) {
        artworkGraceElapsed = false
        delay(HERO_ARTWORK_GRACE_MS)
        artworkGraceElapsed = true
    }

    val heroCard = enrichedHero ?: rawHero?.takeIf { artworkGraceElapsed }

    /**
     * Bumped on every user action on the hero, to restart the rotation delay.
     *
     * A counter rather than a timestamp: it is a change signal for the rotation effect, and
     * a plain increment is unambiguous. Reading a clock would also make the effect's restart
     * depend on when it happened to recompose.
     */
    var heroInteraction by remember { mutableIntStateOf(0) }

    /**
     * Holds the grid at the top while the spotlight has focus.
     *
     * The hero is a full-viewport grid item, so focusing a button inside it triggers the
     * lazy grid's own bring-into-view scroll: it dragged the hero up by around half the
     * screen and faded the artwork the user was looking at to black. Nothing should scroll
     * until focus actually leaves the hero.
     *
     * A continuous guard rather than a single corrective scroll: the bring-into-view runs
     * *after* focus lands, so a one-shot check races it and usually loses. Not a fight with
     * the user either - while focus is on the hero there is nothing below it to scroll to.
     */
    LaunchedEffect(heroFocused) {
        if (!heroFocused) return@LaunchedEffect

        snapshotFlow { gridState.firstVisibleItemScrollOffset to gridState.firstVisibleItemIndex }
            .collect { (offset, index) ->
                if (index != 0 || offset != 0) gridState.scrollToItem(0)
            }
    }

    /**
     * Rotates the spotlight, on a timer, regardless of what has focus.
     *
     * Focus is deliberately not a condition. Focus lands on a hero button the moment the
     * screen opens and stays there until the user moves into the rows, so gating on it left
     * the carousel motionless in the one state where it should obviously be cycling: someone
     * looking at the home screen having touched nothing.
     *
     * Only two things stop it, and neither is about focus:
     *
     * - The picker drawer being open, which covers the hero. Rotating there changes artwork
     *   under a panel the user is reading, and the spotlight they come back to is not the one
     *   they left.
     * - A play request in flight. Advancing mid-resolve would leave the spinner on one title
     *   and open the player on another.
     *
     * A press or focus move on the hero does not stop the rotation, but it does restart the
     * interval via [heroInteraction]: the user gets a full period of a stable target after
     * every input, so the title under Play cannot change in the instant between deciding to
     * press it and pressing it.
     */
    // How far through the current slide's dwell we are, 0..1, for the indicator fill.
    //
    // Driven by the same effect that advances the hero rather than a timer of its own, so
    // the bar completes exactly as the slide changes instead of drifting against it.
    val heroDwell = remember { Animatable(0f) }

    LaunchedEffect(heroItems.size, playRequest, heroInteraction, pickerOpen) {
        if (heroItems.size <= 1 || pickerOpen) {
            // Nothing is rotating, so leave no half-filled pill implying otherwise.
            heroDwell.snapTo(0f)
            return@LaunchedEffect
        }
        if (playRequest !is TvPlayRequest.Idle) {
            heroDwell.snapTo(0f)
            return@LaunchedEffect
        }

        while (true) {
            heroDwell.snapTo(0f)
            heroDwell.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = HERO_ROTATE_MS.toInt(),
                    easing = LinearEasing,
                ),
            )
            // Cleared before the index changes, not after: the effect cannot restart until
            // the recomposition lands, and a value left at 1f shows the incoming pill
            // already full.
            heroDwell.snapTo(0f)
            viewModel.advanceHero(heroItems.size)
        }
    }

    // Prefetches artwork for the spotlight, which is not in any row's request: the hero
    // needs a wide backdrop and a logo, and without this it showed the source's portrait
    // poster stretched across the screen until the user focused the matching card.
    LaunchedEffect(heroItems) {
        if (heroItems.isNotEmpty()) artworkViewModel.onRowVisible("tv-hero", heroItems)
    }

    // Navigating out of a composable is a side effect of state, not of the click: the
    // request is resolved asynchronously, so the press that started it is long over by the
    // time there is a route to follow.
    LaunchedEffect(playRequest) {
        when (val request = playRequest) {
            is TvPlayRequest.Ready -> {
                onPlay(request)
                viewModel.onPlayHandled()
            }
            // No episodes resolved. The detail page is where the user can see why, which
            // is better than a button that visibly does nothing.
            is TvPlayRequest.Unavailable -> {
                onOpenAnime(request.card)
                viewModel.onPlayHandled()
            }
            else -> Unit
        }
    }

    // Seeded from the hero so the backdrop is populated before the D-pad has touched
    // anything, rather than opening on flat black.
    //
    // Resolved through the artwork map, not taken raw: the state's cards carry only what
    // the source returned, and the TMDB logo and backdrop live in that map. Using the
    // raw card meant the hero showed a typeset title until the user moved focus, even
    // though the logo had already been fetched.
    val backdrop = focused ?: heroCard

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // The hero fills the viewport, so its height is measured here rather than derived
        // from the rows below it.
        val viewportHeight = maxHeight

        TvBackdrop(card = backdrop, fade = scrollProgress)

        // The hero is the grid's first item rather than an overlay above it, which is what
        // makes it genuinely full-screen: the rows start a whole viewport down and are
        // reached by scrolling, and the same D-pad press that scrolls them into view is the
        // one that moves focus into them. Overlaying instead meant the rows had to be
        // pushed down by a computed inset, and any row taller than that inset predicted
        // overflowed the screen.
        TvHomeRows(
            state = state,
            continueWatching = continueWatching,
            artwork = artwork,
            gridState = gridState,
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
            heroHeight = viewportHeight,
            hero = {
                TvHeroPage(
                    card = heroCard,
                    isPlayResolving = (playRequest as? TvPlayRequest.Resolving)
                        ?.cardKey == heroCard?.key,
                    onPlay = { heroCard?.let(viewModel::play) },
                    onDetails = {
                        heroCard?.let {
                            artworkViewModel.onOpen(ROW_HERO, it)
                            onOpenAnime(it)
                        }
                    },
                    // Returning from a title opened via Details puts focus back on the
                    // button it was opened from, the same as the rows do. Without this the
                    // shell re-homed focus to the rail and the user was thrown to the top
                    // of the screen having lost the spotlight they were looking at.
                    isOpened = heroCard != null &&
                        lastOpened == artworkViewModel.openedKey(ROW_HERO, heroCard),
                    onFocusRestored = artworkViewModel::onFocusRestored,
                    heroCount = heroItems.size,
                    heroIndex = heroIndex,
                    heroProgress = { heroDwell.value },
                    onFocusChanged = { heroFocused = it },
                    // Any input on the hero gives the user a fresh full interval before the
                    // spotlight moves under them.
                    onInteraction = { heroInteraction++ },
                    isLoading = state.isLoading,
                    onRetry = { state.selected?.let(viewModel::select) },
                )
            },
        )
    }
}

@Composable
private fun TvHomeEmpty(
    hasNoRepos: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(start = TV_CONTENT_START, end = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Two different dead ends, and sending the user to the wrong one leaves them
        // stuck: with no repository the extension list is empty, and once one exists the
        // remaining step is installing from it rather than adding another.
        if (hasNoRepos) {
            WbEmptyState(
                title = stringResource(R.string.empty_no_repos_title),
                body = stringResource(R.string.empty_no_repos_body),
                actionLabel = stringResource(R.string.action_add_repository),
                onAction = onOpenSettings,
            )
        } else {
            WbEmptyState(
                title = stringResource(R.string.empty_no_sources_title),
                body = stringResource(R.string.empty_no_sources_body),
                actionLabel = stringResource(R.string.action_browse_extensions),
                onAction = onOpenSettings,
            )
        }
    }
}

/**
 * Full-bleed backdrop for the focused title.
 *
 * Uses the wide TMDB backdrop, not the portrait poster the cards show: a poster cropped
 * to 16:9 loses most of the frame, usually including the subject.
 *
 * Asks for the full-resolution asset, not the w1280 one the phone hero uses: this fills
 * the panel, and w1280 is narrower than even a 1080p screen, so it upscaled visibly.
 */
@Composable
private fun TvBackdrop(card: AnimeCard?, fade: Float) {
    val tokens = MaterialTheme.wb

    Box(modifier = Modifier.fillMaxSize()) {
        // Crossfaded rather than swapped. A hard cut between two full-screen images is a
        // jarring flash at this size, and the carousel advances on its own - an unannounced
        // full-screen change the user did not ask for reads as a glitch.
        //
        // Keyed by the URL, not the card: two entries for the same title resolve to the same
        // artwork, and re-running the fade for an identical image is a visible flicker for
        // no change. Coil's own crossfade cannot do this - it fades a placeholder to a
        // loaded image within one request, not one image to the next.
        Crossfade(
            targetState = card?.fullBleedImage,
            animationSpec = tween(durationMillis = BACKDROP_FADE_MS),
            label = "tv-hero-backdrop",
        ) { url ->
            WbAsyncImage(
                url = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Left gradient protects the title block; the bottom one carries the rows and the
        // buttons in the lower right.
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

/**
 * The full-screen spotlight page.
 *
 * Occupies the whole viewport, so the backdrop is seen as a picture rather than as a strip
 * behind a row of posters. The rows begin below it and are reached by scrolling down.
 *
 * The title block sits upper-left and the buttons lower-right, which is the diagonal a
 * leanback interface reads along: the artwork's subject is usually centre-frame, and putting
 * text and controls in opposite corners leaves it uncovered. It also puts the buttons
 * nearest the rows below, so one press down from Play reaches the first row.
 *
 * Transparent: the backdrop is drawn behind the whole screen by [TvBackdrop], not by this,
 * because the same image has to stay put while the rows scroll over it.
 */
@Composable
private fun TvHeroPage(
    card: AnimeCard?,
    isPlayResolving: Boolean,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    isOpened: Boolean,
    onFocusRestored: () -> Unit,
    heroCount: Int,
    heroIndex: Int,
    /** Dwell progress of the current slide, read as a lambda so only the draw re-runs. */
    heroProgress: () -> Float,
    onFocusChanged: (Boolean) -> Unit,
    onInteraction: () -> Unit,
    isLoading: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // With no title to show the page still has to occupy the slot and say why. Returning
    // early here left the whole viewport blank on a source switch - no artwork, no text and
    // nothing focusable, so the remote appeared dead until the feed happened to arrive.
    if (card == null) {
        TvHeroPlaceholder(
            isLoading = isLoading,
            onRetry = onRetry,
            onFocusChanged = onFocusChanged,
            modifier = modifier,
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onFocusChanged { onFocusChanged(it.hasFocus) },
    ) {
        // Crossfaded on the same timing as the backdrop behind it. Left as a hard swap the
        // logo and metadata cut instantly while the artwork was still dissolving, which drew
        // the eye to the text rather than the image and made the transition look broken.
        //
        // Keyed by the card, not the URL: the title has to change even when two entries
        // happen to share artwork.
        // Bottom-left, above the page indicator, matching the phone hero.
        //
        // It used to sit top-left, which put the logo against the brightest part of most
        // backdrops and left the lower half of the screen empty. Anchoring the whole block
        // to the bottom edge also means a synopsis of any length grows upward into the
        // image rather than pushing the indicator off-screen.
        Crossfade(
            targetState = card,
            animationSpec = tween(durationMillis = BACKDROP_FADE_MS),
            label = "tv-hero-title",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = HERO_TITLE_BOTTOM, end = 48.dp),
        ) { shown ->
            TvHeroTitle(card = shown)
        }

        TvHeroActions(
            isPlayResolving = isPlayResolving,
            onPlay = onPlay,
            onDetails = onDetails,
            isOpened = isOpened,
            onFocusRestored = onFocusRestored,
            onInteraction = onInteraction,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = HERO_ACTIONS_BOTTOM),
        )

        // Opposite corner from the buttons. They are a position readout, not a control, so
        // the corner the user is not aiming at is the right place for them: beside Play they
        // competed for attention with the primary action and crowded its focus ring.
        //
        // No horizontal inset on either of these: this page sits inside the grid's content
        // padding, so the box edges already carry the rail clearance and the overscan
        // margin. Adding padding here applied that inset twice - the dots landed at double
        // the title's indent instead of lining up beneath it.
        TvHeroDots(
            count = heroCount,
            current = heroIndex,
            progress = heroProgress,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = HERO_DOTS_BOTTOM),
        )
    }
}

/**
 * The spotlight slot when there is no title to put in it.
 *
 * Exists so the hero never becomes an empty screen. On a source switch both feeds are
 * cleared before the new ones arrive, and with nothing rendered the viewport went black with
 * no text and nothing focusable - indistinguishable from a crash, and the remote looked dead
 * because focus had nowhere to land.
 *
 * Carries a focusable Retry. A source that is simply unreachable - blocked by a network, or
 * offline - is the common case behind an empty catalogue, and without a control here the only
 * way out was to switch source and back.

 */
@Composable
private fun TvHeroPlaceholder(
    isLoading: Boolean,
    onRetry: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb
    val retryInteraction = rememberFocusInteraction()

    Box(
        modifier = modifier
            .fillMaxSize()
            .onFocusChanged { onFocusChanged(it.hasFocus) },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.55f),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (isLoading) {
                // A spinner beside the label rather than centred on the screen: the text is
                // what says which state this is, and a bare spinner in the middle of a black
                // viewport reads as a hang.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(
                        color = tokens.colors.textPrimary,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = stringResource(R.string.tv_hero_loading),
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.colors.textMuted,
                    )
                }
                return@Column
            }

            Text(
                text = stringResource(R.string.tv_hero_empty_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = tokens.colors.textPrimary,
            )
            Text(
                // Deliberately not the exception's own message. It reaches here verbatim -
                // "java.security.cert.CertPathValidatorException: Trust anchor for
                // certification path not found" - which says nothing to a viewer holding a
                // remote. The cause is logged; this line only has to say what to do next.
                text = stringResource(R.string.tv_hero_empty_body),
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier
                    .height(HERO_BUTTON_HEIGHT)
                    .adaptiveFocus(
                        retryInteraction,
                        RoundedCornerShape(HERO_BUTTON_HEIGHT / 2),
                        borderColor = tokens.colors.background,
                    )
                    .clip(RoundedCornerShape(HERO_BUTTON_HEIGHT / 2))
                    .background(tokens.colors.textPrimary)
                    // Takes focus on arrival: it is the only control on the screen, so
                    // anything else leaves the remote with nowhere to go.
                    .tvInitialFocus()
                    .clickable(
                        interactionSource = retryInteraction,
                        indication = null,
                        onClick = onRetry,
                    )
                    .padding(horizontal = 26.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.action_retry),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.background,
                )
            }
        }
    }
}

/** Title logo or text, plus a metadata line, for the spotlight title. */
@Composable
private fun TvHeroTitle(card: AnimeCard, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.wb

    Column(modifier = modifier.fillMaxWidth(0.5f)) {
        if (card.logoUrl != null) {
            WbAsyncImage(
                url = card.logoUrl,
                contentDescription = card.title,
                contentScale = ContentScale.Fit,
                // Left, not the default centre: Fit letterboxes, and a centred logo
                // reads as an indent beside left-aligned text.
                alignment = Alignment.CenterStart,
                // Over the full-bleed backdrop, so a filled placeholder would show as
                // a solid block across the hero while the logo loads.
                transparentPlaceholder = true,
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

        // Score, year and type as pills, as on the phone. The score carries the fixed
        // amber rather than the theme accent: it is a rating, and a red or purple star
        // reads as a warning.
        TvHeroFacts(card = card)

        if (card.genres.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = card.genres.take(MAX_TV_HERO_GENRES).joinToString("   ·   "),
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // The synopsis the phone hero already shows. Two lines: this is read at three
        // metres, where a longer paragraph is skimmed rather than read, and the block
        // grows upward from a bottom anchor so a third line would eat into the artwork.
        if (card.overview.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = card.overview,
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Score / year / type pills for the TV spotlight. */
@Composable
private fun TvHeroFacts(card: AnimeCard) {
    val tokens = MaterialTheme.wb

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        card.ratingPercent?.let { percent ->
            TvHeroPill(
                label = "$percent%",
                icon = Icons.Rounded.Star,
                tint = tokens.colors.warning,
            )
        }

        card.year?.takeIf { it.isNotBlank() }?.let { year ->
            TvHeroPill(label = year, icon = Icons.Rounded.CalendarMonth)
        }

        TvHeroPill(
            label = if (card.isMovie) {
                stringResource(R.string.hero_type_movie)
            } else {
                stringResource(R.string.hero_type_series)
            },
            icon = Icons.Rounded.Tv,
        )
    }
}

@Composable
private fun TvHeroPill(label: String, icon: ImageVector, tint: Color? = null) {
    val tokens = MaterialTheme.wb

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tokens.colors.textPrimary.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint ?: tokens.colors.textPrimary.copy(alpha = 0.9f),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = tint ?: tokens.colors.textPrimary.copy(alpha = 0.9f),
            maxLines = 1,
        )
    }
}

/**
 * Play and Details for the spotlight title.
 *
 * Play is the filled one because it is the only reason to put a hero above the rows: the
 * whole point of a spotlight is to start watching without first walking into a detail page
 * and finding the episode list.
 *
 * Play sits on the right, nearest the screen edge, so it is the button the eye reaches
 * first when scanning in from the corner and the one a press down from the rows returns to.
 */
@Composable
private fun TvHeroActions(
    isPlayResolving: Boolean,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    isOpened: Boolean,
    onFocusRestored: () -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb
    val playInteraction = rememberFocusInteraction()
    val detailsInteraction = rememberFocusInteraction()

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .height(HERO_BUTTON_HEIGHT)
                // Per button, not on the parent: the parent's `hasFocus` stays true when the
                // D-pad moves between these two, so a single parent listener would miss the
                // very input that says the user is aiming at something.
                .onFocusChanged { if (it.isFocused) onInteraction() }
                .adaptiveFocus(
                    detailsInteraction,
                    RoundedCornerShape(HERO_BUTTON_HEIGHT / 2),
                )
                .clip(RoundedCornerShape(HERO_BUTTON_HEIGHT / 2))
                // Translucent rather than a solid surface: it sits on artwork, and a
                // filled panel here would read as a second, competing primary action.
                .background(Color.White.copy(alpha = 0.18f))
                .clickable(
                    interactionSource = detailsInteraction,
                    indication = null,
                    onClick = onDetails,
                )
                .padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = tokens.colors.textPrimary,
                modifier = Modifier.size(HERO_BUTTON_ICON),
            )
            Text(
                text = stringResource(R.string.tv_hero_details),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
            )
        }

        Row(
            modifier = Modifier
                .height(HERO_BUTTON_HEIGHT)
                .onFocusChanged { if (it.isFocused) onInteraction() }
                // Before clip, per tvFocusable's contract. Dark, not the usual white:
                // this button is white, and the default theme's accent is #F5F5F5, so a
                // white or accent stroke was invisible against it - the button scaled up
                // on focus but showed no ring. Matches the detail page's play button.
                .adaptiveFocus(
                    playInteraction,
                    RoundedCornerShape(HERO_BUTTON_HEIGHT / 2),
                    borderColor = tokens.colors.background,
                )
                .clip(RoundedCornerShape(HERO_BUTTON_HEIGHT / 2))
                .background(tokens.colors.textPrimary)
                // Play, not Details, reclaims focus on the way back: it is the primary
                // action, so landing there means the next press starts watching.
                .restoreFocusIfOpened(isOpened, onFocusRestored)
                .clickable(
                    interactionSource = playInteraction,
                    indication = null,
                    onClick = onPlay,
                )
                .padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Swapped in place of the icon rather than shown beside it, so resolving
            // does not change the button's width and shuffle the row beside it.
            if (isPlayResolving) {
                CircularProgressIndicator(
                    color = tokens.colors.background,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(HERO_BUTTON_ICON),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = tokens.colors.background,
                    modifier = Modifier.size(HERO_BUTTON_ICON),
                )
            }
            Text(
                text = stringResource(R.string.action_play),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.background,
            )
        }
    }
}

/**
 * Page indicator for the hero carousel.
 *
 * Dots rather than arrows: the carousel is driven by the D-pad, so on-screen arrows would
 * be focusable targets competing with Play for the user's presses. These are purely a
 * position readout.
 */
@Composable
private fun TvHeroDots(
    count: Int,
    current: Int,
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb
    if (count <= 1) return

    val fillColor = tokens.colors.textPrimary

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val isCurrent = index == current

            // Stretches into a pill, matching the phone hero. Animated rather than
            // switched so the growth reads as the same gesture as the slide change.
            val width by animateDpAsState(
                targetValue = if (isCurrent) HERO_DOT_PILL else HERO_DOT,
                animationSpec = tween(durationMillis = 220),
                label = "tv-hero-dot-width",
            )

            // The current pill's track is dimmer than an inactive dot: the fill supplies
            // the contrast, so a bright track would leave full and empty indistinguishable.
            Box(
                modifier = Modifier
                    .height(HERO_DOT)
                    .width(width)
                    .clip(RoundedCornerShape(50))
                    .background(
                        fillColor.copy(
                            alpha = if (isCurrent) HERO_DOT_TRACK_ALPHA else HERO_DOT_ALPHA,
                        ),
                    )
                    .drawWithContent {
                        drawContent()
                        if (!isCurrent) return@drawWithContent
                        // Clipped to the pill by the parent, so the fill keeps its rounded
                        // ends without a second shape.
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
    onFocus: (AnimeCard) -> Unit,
    onPrefetch: (String, List<AnimeCard>) -> Unit,
    onOpenAnime: (String, AnimeCard) -> Unit,
    onResume: (WatchHistoryEntry) -> Unit,
    onRemove: (WatchHistoryEntry) -> Unit,
    onLoadMore: () -> Unit,
    openedKey: String?,
    onFocusRestored: () -> Unit,
    heroHeight: Dp,
    hero: @Composable () -> Unit,
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
        contentPadding = PaddingValues(
            // Clears the navigation rail, which overlays the content. Without this the
            // grid's first column drew underneath it.
            start = TV_CONTENT_START,
            end = 48.dp,
            bottom = 48.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // The spotlight is the first item and a full viewport tall, which is what puts the
        // rows below the fold. Previously the rows were inset from the top by a computed
        // height instead, which had to predict how tall the first row would be - and any
        // row taller than the prediction overflowed the screen and forced a scroll the
        // moment it took focus, dragging the backdrop away as the user tried to look at it.
        item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
            Box(modifier = Modifier.height(heroHeight)) { hero() }
        }
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

/** The hero carousel, which restores focus like any other row. */
private const val ROW_HERO = "hero"

/**
 * How long each spotlight title is shown.
 *
 * Long enough to read the title, the metadata and decide - a carousel that turns over
 * faster than a person can act on it is just motion.
 */
private const val HERO_ROTATE_MS = 9_000L

/**
 * How long the spotlight waits for TMDB artwork before falling back to the source's own.
 *
 * Long enough to cover a cached or quick lookup, which is the common case, and short enough
 * that a title TMDB cannot match does not leave the slot empty for noticeably long. Trades a
 * brief placeholder for not flashing a stretched portrait poster and replacing it.
 */
private const val HERO_ARTWORK_GRACE_MS = 900L

private val HERO_BUTTON_HEIGHT = 48.dp
private val HERO_BUTTON_ICON = 20.dp

/**
 * Where the spotlight's title block starts.
 *
 * Below the top overscan margin, and high enough that a two-line title still clears the
 * buttons in the opposite corner.
 */
private val HERO_TITLE_TOP = 96.dp


/** Genres shown beside the spotlight; more than this wraps and pushes the block taller. */
private const val MAX_TV_HERO_GENRES = 3

/**
 * Gap between the buttons and the bottom edge.
 *
 * Clears the first row's label, which sits immediately below the fold, so the buttons do
 * not read as belonging to that row.
 */
private val HERO_ACTIONS_BOTTOM = 72.dp

/** Page dots: the active one is larger rather than merely brighter, to read at distance. */
private val HERO_DOT = 8.dp
private val HERO_DOT_ACTIVE = 11.dp

/**
 * Width of the current page's indicator.
 *
 * A pill rather than a larger dot, matching the phone hero, so the fill has somewhere to
 * run. Wider than the phone's 32dp because this is read across a room.
 */
private val HERO_DOT_PILL = 40.dp

/** Alpha of a dot that is not the current page. */
private const val HERO_DOT_ALPHA = 0.35f

/** Alpha of the current pill's track; the fill drawn over it carries the contrast. */
private const val HERO_DOT_TRACK_ALPHA = 0.28f

/**
 * Gap between the dots and the bottom edge.
 *
 * Set so the dots sit on the buttons' vertical centre in the opposite corner: the button
 * inset plus half the button's height, less half a dot.
 *
 * Declared after the values it reads: top-level properties initialise in file order, so
 * referencing one from above would silently read zero.
 */
private val HERO_DOTS_BOTTOM = HERO_ACTIONS_BOTTOM + (HERO_BUTTON_HEIGHT / 2) -
    (HERO_DOT_ACTIVE / 2)

/**
 * Gap between the title block and the bottom edge.
 *
 * Clears the page indicator, which sits below it, so the synopsis and the dots do not
 * collide. Derived from the indicator's own offset rather than a separate number, so the
 * two cannot drift apart.
 */
private val HERO_TITLE_BOTTOM = HERO_DOTS_BOTTOM + HERO_DOT + 20.dp

/**
 * How long the spotlight takes to dissolve from one title to the next.
 *
 * Long enough to read as a deliberate transition rather than a flicker, and short enough
 * that it is finished well before the next rotation.
 */
private const val BACKDROP_FADE_MS = 450
