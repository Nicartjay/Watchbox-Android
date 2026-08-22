package space.nicart.watchbox.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.nicart.watchbox.core.ui.LocalLayoutMetrics
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.extension.model.Extension
import space.nicart.watchbox.core.ui.tvInitialFocus
import space.nicart.watchbox.R
import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.domain.EpisodeEntry
import space.nicart.watchbox.ui.components.NavOverlayPadding
import space.nicart.watchbox.ui.components.WbBackButton
import space.nicart.watchbox.ui.components.WbEmptyState
import space.nicart.watchbox.ui.components.WbLoading
import space.nicart.watchbox.ui.components.WbPosterCard
import space.nicart.watchbox.ui.components.WbShelfSection
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import space.nicart.watchbox.ui.components.openInBrowser
import space.nicart.watchbox.ui.components.openYouTube
import androidx.compose.runtime.mutableIntStateOf

/**
 * Title detail page.
 *
 * Keeps Nuvio's structure: a bare `LazyColumn` with the parallax hero as item 0
 * and a collapsing floating header layered above at a higher z-index. Sections
 * are padded 18dp horizontally with 20dp gaps.
 *
 * No season selector, cast rail or recommendations row: `getEpisodeList` returns
 * one flat list and extensions expose no cast or related titles.
 */
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    /** Resolves a source's owning extension, for its icon. */
    extensionForSource: (Long) -> Extension.Installed? = { null },
    onPlay: (episode: EpisodeEntry, resumeMs: Long) -> Unit,
    onOpenAnime: (AnimeCard) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Start playback as soon as the episode list is known.
     *
     * Set by the home hero's "Watch Now", which has no episode list of its own.
     */
    autoPlay: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Which list the tab strip is showing.
    //
    // Screen-local rather than in the view model: a view preference that should reset when
    // the page is left, not survive it.
    //
    // The episode block is not held here. It belongs with the season selector, which
    // EpisodeList owns - keeping it at this level meant the blocks were computed from every
    // season's episodes combined while the filter ran inside, so the two disagreed.
    var detailTab by remember { mutableStateOf(DetailTab.EPISODES) }

    // Reviews are phone-only, so the layout has to know which it is.
    val isFocusDriven = LocalLayoutMetrics.current.isFocusDriven

    // Fires once per screen, not once per recomposition: `startTarget` stays
    // non-null for the life of the page, so an un-latched effect would re-navigate
    // every time the user came back from the player.
    var autoPlayed by remember { mutableStateOf(false) }
    LaunchedEffect(autoPlay, state.startTarget, autoPlayed) {
        if (!autoPlay || autoPlayed) return@LaunchedEffect
        val target = state.startTarget ?: return@LaunchedEffect
        autoPlayed = true
        onPlay(target, state.resumeTarget?.second ?: 0L)
    }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    /** True while focus is on the action buttons, the first focusable item. */
    var atTopFocusable by remember { mutableStateOf(false) }

    // Owned here rather than by the hero, because the toggle for it sits in the top
    // overlay and the video it applies to sits in the list - two subtrees with only this
    // screen in common.
    //
    // Keyed on the trailer so a new title starts silent again: carrying the choice over
    // would mean navigating from an unmuted page to another one plays sound without it
    // having been asked for there.
    var trailerMuted by remember(state.trailer?.url) { mutableStateOf(true) }

    // The toggle waits on this. Before a frame has rendered there is no video to silence,
    // and the trailer may yet fail outright and never appear at all.
    var trailerPlaying by remember(state.trailer?.url) { mutableStateOf(false) }

    // Where Down from the overlay controls lands.
    //
    // The overlay is a separate layer stacked above the list, so the two are siblings
    // rather than neighbours: Compose's spatial search finds nothing below a control up
    // there and the remote stops dead. Naming the target explicitly is the same fix the
    // filter panel uses for its own list.
    val actionsFocusRequester = remember { FocusRequester() }

    // The two overlay controls, so each can name the other as its neighbour.
    val backFocusRequester = remember { FocusRequester() }
    val muteFocusRequester = remember { FocusRequester() }

    // Undoes the scroll that claiming initial focus causes.
    //
    // On a television the screen focuses its first focusable so the remote is live on
    // arrival, and Compose scrolls whatever it focuses into view. That focusable is the
    // play button, which sits at the foot of a hero as tall as the viewport - so the page
    // opened already scrolled past its own artwork, with the title and summary above the
    // top edge.
    //
    // Latched, and reset per title. Without the latch this would fire again every time
    // focus came back to the play button, dragging the page to the top from wherever the
    // user had scrolled to - so it runs once on arrival and then stays out of the way.
    //
    // Waits for focus to land rather than running immediately, because the request is
    // retried over several frames and a scroll before it arrives would be undone by it.
    // Snapped, not animated: this corrects a movement the user never asked for, so it
    // should not be seen happening at all.
    var openScrollCorrected by remember(state.detail?.key) { mutableStateOf(false) }
    LaunchedEffect(state.detail?.key, atTopFocusable, openScrollCorrected) {
        if (!isFocusDriven || openScrollCorrected || !atTopFocusable) return@LaunchedEffect
        listState.scrollToItem(0)
        openScrollCorrected = true
    }

    // Draws its own background rather than relying on the window's. The screen had none, so
    // whatever was behind it showed through - and a navigation transition or a translucent
    // parent puts something other than the window there.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.wb.colors.background),
    ) {
        val metrics = LocalLayoutMetrics.current
        val isFocusDriven = metrics.isFocusDriven
        val isTablet = maxWidth >= 720.dp

        // A wide hero needs room for text *beside* the artwork, which a portrait tablet
        // does not have even though it is a tablet. Gated on the landscape width rather
        // than the form factor for that reason.
        val usesWideHero = metrics.isTv || maxWidth >= 900.dp

        val contentPadding = when {
            metrics.isTv -> metrics.screenPadding
            isTablet -> 32.dp
            else -> 18.dp
        }
        val contentMaxWidth = if (isTablet) {
            (maxWidth.value * 0.6f).dp.coerceIn(520.dp, 680.dp)
        } else {
            maxWidth
        }
        // The wide hero is the screen, not a banner above it: it carries the badge,
        // title, metadata, summary and actions, and the Netflix layout depends on that
        // vertical room. The stacked phone hero keeps its own proportional sizing.
        //
        // The full viewport, not a fraction of it. At 78% the backdrop and trailer
        // stopped short of the bottom edge with the action row on flat background
        // beneath, so the cut was on screen - and with it whatever the video happened to
        // be showing along that line, which for a subtitled trailer is a line of someone
        // else's captions. Taking the whole height leaves the artwork no visible edge and
        // puts the copy and actions over it, which is what the hero was imitating.
        val heroHeight = if (usesWideHero) {
            maxHeight.coerceAtLeast(360.dp)
        } else {
            detailHeroHeight(maxWidth, isTablet)
        }

        val scrollOffset by remember {
            derivedStateOf {
                if (listState.firstVisibleItemIndex == 0) {
                    listState.firstVisibleItemScrollOffset.toFloat()
                } else {
                    with(density) { heroHeight.toPx() }
                }
            }
        }
        val headerProgress by remember {
            derivedStateOf {
                val threshold = with(density) { (heroHeight - 120.dp).toPx() }
                if (listState.firstVisibleItemIndex > 0) {
                    1f
                } else {
                    (scrollOffset / threshold).coerceIn(0f, 1f)
                }
            }
        }

        // Whether the bare back button is on screen, as opposed to the floating header
        // that replaces it once scrolled. Read here because the action row's Up edge
        // targets it, and naming a control that is not composed throws when followed.
        val backButtonVisible = headerProgress <= 0.05f

        val detail = state.detail

        // Hoisted because the action row has two homes: inside the wide hero, overlaid on
        // the artwork, and as a list item of its own on a phone.
        val onOpenInBrowser: (() -> Unit)? = state.webUrl?.let { url ->
            {
                if (!context.openInBrowser(url)) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.source_open_site_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

        // The extension's icon, which stands in for Netflix's "N". Looked up rather
        // than carried on the model: a source has no icon of its own, it belongs to the
        // extension that created it.
        val extensionIcon = remember(detail?.sourceId) {
            detail?.sourceId?.let { extensionForSource(it)?.icon }
        }

        when {
            state.isLoading && detail == null -> WbLoading()

            detail == null -> WbEmptyState(
                title = stringResource(R.string.error_generic),
                body = state.errorMessage,
                actionLabel = stringResource(R.string.action_retry),
                onAction = viewModel::retry,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        // Applied to the list rather than the screen: the back button
                        // is overlaid outside it, so a group spanning both traps focus
                        // on the button with no route into the content.
                        .tvInitialFocus()
                        // Returns the hero to view when focus is already on the topmost
                        // focusable item.
                        //
                        // The action buttons are that item: everything above them is
                        // artwork and text, and the two controls in the overlay are
                        // reachable only by being named, not by a spatial search. With
                        // nothing above to move to, Compose has no reason to scroll and
                        // the top of the page becomes unreachable. Handled here rather
                        // than by making the hero focusable, which would add a stop that
                        // does nothing when activated.
                        //
                        // Runs before focus search, so while the list is scrolled this
                        // takes Up. Once the hero is in view it declines, which is what
                        // lets Up out of the content and up to the back button.
                        .onPreviewKeyEvent { event ->
                            if (!isFocusDriven) return@onPreviewKeyEvent false
                            if (event.type != KeyEventType.KeyDown) {
                                return@onPreviewKeyEvent false
                            }
                            if (event.key != Key.DirectionUp) {
                                return@onPreviewKeyEvent false
                            }
                            // Only when already scrolled and nothing focusable is above,
                            // so normal upward movement between rows is untouched.
                            val alreadyAtTop = listState.firstVisibleItemIndex == 0 &&
                                listState.firstVisibleItemScrollOffset == 0
                            if (!atTopFocusable || alreadyAtTop) {
                                return@onPreviewKeyEvent false
                            }
                            scope.launch { listState.animateScrollToItem(0) }
                            true
                        }
                        .zIndex(1f),
                    contentPadding = PaddingValues(bottom = 32.dp + NavOverlayPadding),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    item(key = "hero") {
                        // Landscape screens get the Netflix-style hero: a full-bleed
                        // backdrop with the copy overlaid on the left. The phone keeps
                        // its stacked layout, which is the only thing that works when
                        // there is no width to place text beside the image.
                        if (usesWideHero) {
                            NetflixDetailHero(
                                detail = detail,
                                extensionIcon = extensionIcon,
                                heroHeight = heroHeight,
                                contentPadding = contentPadding,
                                trailer = state.trailer,
                                trailerMuted = trailerMuted,
                                onTrailerFirstFrame = { trailerPlaying = true },
                                // Hosted by the hero rather than placed after it, now
                                // that the artwork takes the whole viewport: as a
                                // sibling below it the row would start one screen down,
                                // leaving Play off screen until the user scrolled for it.
                                actions = {
                                    DetailActions(
                                        state = state,
                                        isTablet = isTablet,
                                        compactButtons = true,
                                        onPlay = onPlay,
                                        onToggleWatched = viewModel::toggleWatched,
                                        onToggleWatchlist = viewModel::toggleWatchlist,
                                        onOpenInBrowser = onOpenInBrowser,
                                        modifier = Modifier
                                            .focusRequester(actionsFocusRequester)
                                            .onFocusChanged {
                                                atTopFocusable = it.hasFocus
                                            }
                                            // Up reaches the back button, which a spatial
                                            // search will not find: it sits in a layer
                                            // stacked over the list rather than beside it.
                                            //
                                            // Only while that button is the one on screen.
                                            // Once scrolled the floating header takes its
                                            // place and refuses focus, and by then the
                                            // list's own Up handler is taking the key
                                            // anyway to bring the hero back.
                                            .then(
                                                if (isFocusDriven && backButtonVisible) {
                                                    Modifier.focusProperties {
                                                        up = backFocusRequester
                                                    }
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                    )
                                },
                            )
                        } else {
                            DetailHero(
                                detail = detail,
                                heroHeight = heroHeight,
                                scrollOffset = scrollOffset,
                                isTablet = isTablet,
                                contentMaxWidth = contentMaxWidth,
                                trailer = state.trailer,
                                trailerMuted = trailerMuted,
                                onTrailerFirstFrame = { trailerPlaying = true },
                            )
                        }
                    }

                    // Only when the hero is not already carrying it. On a wide screen the
                    // row lives inside the hero, overlaid on the artwork.
                    if (!usesWideHero) item(key = "actions") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                // The topmost focusable content, so this is what marks
                                // "nothing above to move to".
                                .onFocusChanged { atTopFocusable = it.hasFocus }
                                .padding(horizontal = contentPadding)
                                .padding(bottom = 20.dp),
                            // Centred, matching the centred copy of the stacked hero above.
                            contentAlignment = Alignment.Center,
                        ) {
                            DetailActions(
                                state = state,
                                isTablet = isTablet,
                                compactButtons = false,
                                onPlay = onPlay,
                                onToggleWatched = viewModel::toggleWatched,
                                onToggleWatchlist = viewModel::toggleWatchlist,
                                onOpenInBrowser = onOpenInBrowser,
                            )
                        }
                    }

                    // Skipped on a wide screen: the hero already carries the year,
                    // rating, episode count and genres, so this repeats them.
                    if (!usesWideHero) item(key = "meta") {
                        DetailMetaInfo(
                            detail = detail,
                            sourceIcon = extensionIcon,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = contentPadding)
                                .padding(bottom = 20.dp)
                                .then(
                                    if (isTablet) Modifier.widthIn(max = contentMaxWidth)
                                    else Modifier,
                                ),
                        )
                    }

                    // Availability, when TMDB knows of any in the user's country. Above
                    // the episode list because "can I watch this legally" is a question
                    // asked before picking an episode, not after.
                    if (detail.extras.providers.isNotEmpty()) {
                        item(key = "providers") {
                            ProviderSection(
                                extras = detail.extras,
                                isTablet = isTablet,
                                horizontalPadding = contentPadding,
                                modifier = Modifier.padding(bottom = 20.dp),
                            )
                        }
                    }

                    // Tabs only when there is a second list to switch to. A film has no
                    // episode list worth showing - its single entry is what Play already
                    // opens - so for a film with trailers the strip is the only way to
                    // reach them, and for one without it does not appear at all.
                    val hasVideos = detail.extras.videos.isNotEmpty()
                    val showEpisodes = !detail.isMovie

                    if (showEpisodes || hasVideos) {
                        item(key = "detail-tabs") {
                            if (showEpisodes && hasVideos) {
                                DetailTabRow(
                                    selected = detailTab,
                                    showVideos = true,
                                    videoCount = detail.extras.videos.size,
                                    onSelect = { detailTab = it },
                                    horizontalPadding = contentPadding,
                                    modifier = Modifier.padding(bottom = 14.dp),
                                )
                            } else {
                                DetailSectionTitle(
                                    title = if (showEpisodes) {
                                        stringResource(R.string.detail_episodes)
                                    } else {
                                        stringResource(
                                            R.string.detail_videos_count,
                                            detail.extras.videos.size,
                                        )
                                    },
                                    isTablet = isTablet,
                                    modifier = Modifier
                                        .padding(horizontal = contentPadding)
                                        .padding(bottom = 14.dp),
                                )
                            }
                        }

                        // Which list the strip resolves to. A film has no episodes, so it
                        // shows videos whatever the tab says.
                        val showsVideoList = detailTab == DetailTab.VIDEOS || !showEpisodes

                        if (showsVideoList && hasVideos) {
                            item(key = "videos") {
                                VideoRail(
                                    videos = detail.extras.videos,
                                    onOpen = { video ->
                                        // Targets the YouTube app explicitly: a plain
                                        // ACTION_VIEW goes to whichever app holds the
                                        // default for youtube.com, which is the browser
                                        // on many devices.
                                        if (!context.openYouTube(video.watchUrl)) {
                                            Toast.makeText(
                                                context,
                                                context.getString(
                                                    R.string.source_open_site_failed,
                                                ),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    },
                                    horizontalPadding = contentPadding,
                                    modifier = Modifier.padding(bottom = 20.dp),
                                )
                            }
                        } else if (showEpisodes) {
                            item(key = "episodes") {
                                // The whole list is handed over, not a slice of it.
                                //
                                // EpisodeList owns the season selector, so slicing here as
                                // well meant two layers cutting the same list from different
                                // ends: the chips were computed from every season's episodes
                                // combined, then the season filter ran inside, so picking
                                // "51-100" on a show whose seasons are 20 episodes each
                                // showed nothing at all. Episode blocks belong under the
                                // season, so they live where the season is known.
                                EpisodeList(
                                    episodes = detail.episodes,
                                    watchedUrls = state.watchedEpisodeUrls,
                                    currentUrl = state.history?.episodeUrl,
                                    isLoading = false,
                                    horizontalPadding = contentPadding,
                                    onPlay = { onPlay(it, 0L) },
                                    modifier = Modifier.padding(bottom = 20.dp),
                                )
                            }
                        }
                    }

                    // Reviews, phone only. On a television a wall of small body text read
                    // at three metres is unusable, and there is no way to scroll one
                    // review's text with a D-pad without trapping focus inside it.
                    if (!isFocusDriven && detail.extras.reviews.isNotEmpty()) {
                        item(key = "reviews") {
                            ReviewSection(
                                reviews = detail.extras.reviews,
                                isTablet = isTablet,
                                horizontalPadding = contentPadding,
                                modifier = Modifier.padding(bottom = 20.dp),
                            )
                        }
                    }

                    // Suggestions load after the detail, so the section simply
                    // does not appear until there is something to show. No
                    // skeleton: an empty result is a normal outcome for sources
                    // with neither a related feed nor useful search.
                    if (state.suggestions.isNotEmpty()) {
                        item(key = "suggestions") {
                            WbShelfSection(
                                title = stringResource(R.string.detail_more_like_this),
                                items = state.suggestions,
                                key = { it.key },
                                horizontalPadding = contentPadding,
                                modifier = Modifier.padding(bottom = 20.dp),
                            ) { card ->
                                WbPosterCard(
                                    card = card,
                                    // The year, not the source name. Suggestions come from
                                    // the same source as the title being viewed, so the name
                                    // repeated under every card said nothing - while the year
                                    // distinguishes a remake or a sequel from the original.
                                    subtitle = card.year,
                                    onClick = { onOpenAnime(card) },
                                )
                            }
                        }
                    }
                }

                // Both overlay controls are conditional, and both are named as the target
                // of an explicit focus edge. A redirect to a requester attached to nothing
                // throws when it is followed, so every edge is gated on the same value
                // that decides whether its target exists at all.
                val muteButtonVisible = state.trailerMuteButton &&
                    trailerPlaying &&
                    backButtonVisible

                // The overlay across the top. Full-screen, so its controls sit *above*
                // every row in the list geometrically rather than beside them.
                //
                // The refusal of focus that used to sit here, on the container, is now on
                // the floating header alone. It cannot be applied to the whole overlay: a
                // parent's canFocus overwrites its children's, so a blanket refusal here
                // silently defeated the back button's own opt-in - which left the button
                // unfocusable, the action row's Up pointing at something that could not
                // take focus, and the remote unable to leave the play button at all.
                //
                // What the refusal was for is now done by the explicit edges below: every
                // control up here names the action row as its Down, so focus can always
                // get back into the content and the "stuck on Back" trap stays closed.
                Box(modifier = Modifier.fillMaxSize().zIndex(2f)) {
                    DetailFloatingHeader(
                        detail = detail,
                        progress = headerProgress,
                        inWatchlist = state.inWatchlist,
                        onBack = onBack,
                        onToggleWatchlist = viewModel::toggleWatchlist,
                        // Still refused focus, and this is the part that always needed to
                        // be: it appears only once scrolled, and both of its controls are
                        // duplicated - Back by the hardware key, the watchlist by the
                        // action row - so neither is worth a stop on the way through.
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .then(
                                if (isFocusDriven) {
                                    Modifier.focusProperties { canFocus = false }
                                } else {
                                    Modifier
                                },
                            ),
                    )

                    if (backButtonVisible) {
                        WbBackButton(
                            onClick = onBack,
                            background = Color.Black.copy(alpha = 0.35f),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .statusBarsPadding()
                                .padding(start = 12.dp, top = 8.dp)
                                .then(
                                    if (isFocusDriven) {
                                        Modifier
                                            .focusRequester(backFocusRequester)
                                            .focusProperties {
                                                // The way back into the content, which is
                                                // what stops this being a dead end.
                                                down = actionsFocusRequester
                                                if (muteButtonVisible) {
                                                    right = muteFocusRequester
                                                }
                                                // Nothing sits left of or above this.
                                                left = FocusRequester.Cancel
                                                up = FocusRequester.Cancel
                                            }
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }

                    // The mute toggle: the back button's row, at the opposite end of it.
                    //
                    // A sibling of the back button now that the refusal of focus sits on
                    // the floating header rather than on this container - it needed its own
                    // layer only to escape that blanket.
                    //
                    // Shown on the same terms as the back button, and only once the setting
                    // is on and a frame has actually played.
                    if (muteButtonVisible) {
                        TrailerMuteButton(
                            muted = trailerMuted,
                            onToggle = { trailerMuted = !trailerMuted },
                            background = Color.Black.copy(alpha = 0.35f),
                            // Same top inset as the back button so the two sit level, and
                            // the mirror of its start inset at the other edge.
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(end = 12.dp, top = 8.dp)
                                .then(
                                    if (isFocusDriven) {
                                        Modifier
                                            .focusRequester(muteFocusRequester)
                                            .focusProperties {
                                                // Named rather than left to a spatial
                                                // search, which cannot cross from this
                                                // layer into the list below it.
                                                down = actionsFocusRequester
                                                left = backFocusRequester
                                                right = FocusRequester.Cancel
                                                up = FocusRequester.Cancel
                                            }
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The action row, wired to the screen's state.
 *
 * Extracted because the row has two homes and the wiring is the bulk of it: the wide
 * hero draws it over the artwork, a phone places it in the list below the hero.
 */
@Composable
private fun DetailActions(
    state: DetailUiState,
    isTablet: Boolean,
    compactButtons: Boolean,
    onPlay: (episode: EpisodeEntry, resumeMs: Long) -> Unit,
    onToggleWatched: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onOpenInBrowser: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    DetailActionButtons(
        playLabel = stringResource(
            if (state.isResume) R.string.action_resume else R.string.action_play,
        ),
        enabled = state.startTarget != null,
        watched = state.history?.isFinished == true,
        inWatchlist = state.inWatchlist,
        isTablet = isTablet,
        onPlay = {
            val resume = state.resumeTarget
            if (resume != null) {
                onPlay(resume.first, resume.second)
            } else {
                state.startTarget?.let { onPlay(it, 0L) }
            }
        },
        onToggleWatched = onToggleWatched,
        onToggleWatchlist = onToggleWatchlist,
        onOpenInBrowser = onOpenInBrowser,
        compactButtons = compactButtons,
        modifier = modifier,
    )
}
