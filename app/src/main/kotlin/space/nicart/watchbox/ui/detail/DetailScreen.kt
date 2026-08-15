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
import androidx.compose.ui.focus.focusProperties
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
        val heroHeight = if (usesWideHero) {
            (maxHeight.value * 0.78f).dp.coerceAtLeast(360.dp)
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

        val detail = state.detail

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
                        // The hero holds nothing focusable, so once focus sits on the
                        // action buttons there is nothing above it to move to: Compose
                        // has no reason to scroll, and the top of the page becomes
                        // unreachable. Handled here rather than by making the hero
                        // focusable, which would add a stop that does nothing when
                        // activated.
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
                            )
                        } else {
                            DetailHero(
                                detail = detail,
                                heroHeight = heroHeight,
                                scrollOffset = scrollOffset,
                                isTablet = isTablet,
                                contentMaxWidth = contentMaxWidth,
                            )
                        }
                    }

                    item(key = "actions") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                // The topmost focusable content, so this is what marks
                                // "nothing above to move to".
                                .onFocusChanged { atTopFocusable = it.hasFocus }
                                .padding(horizontal = contentPadding)
                                .padding(bottom = 20.dp),
                            // Left-aligned under the hero copy on a wide screen, where
                            // centring would detach the buttons from the title they act
                            // on. Centred on a phone, where the copy is centred too.
                            contentAlignment = if (usesWideHero) {
                                Alignment.CenterStart
                            } else {
                                Alignment.Center
                            },
                        ) {
                            DetailActionButtons(
                                playLabel = stringResource(
                                    if (state.isResume) R.string.action_resume
                                    else R.string.action_play,
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
                                onToggleWatched = viewModel::toggleWatched,
                                onToggleWatchlist = viewModel::toggleWatchlist,
                                compactButtons = usesWideHero,
                            )
                        }
                    }

                    // Skipped on a wide screen: the hero already carries the year,
                    // rating, episode count and genres, so this repeats them.
                    if (!usesWideHero) item(key = "meta") {
                        DetailMetaInfo(
                            detail = detail,
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

                    // A film has nothing to choose between: its single entry is what
                    // the Play button already opens, so listing it is a redundant row
                    // that invites a second, identical decision.
                    if (!detail.isMovie) {
                        item(key = "episodes-title") {
                            DetailSectionTitle(
                                title = stringResource(R.string.detail_episodes),
                                isTablet = isTablet,
                                modifier = Modifier
                                    .padding(horizontal = contentPadding)
                                    .padding(bottom = 14.dp),
                            )
                        }

                        item(key = "episodes") {
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
                                    subtitle = card.sourceName,
                                    onClick = { onOpenAnime(card) },
                                )
                            }
                        }
                    }
                }

                // The overlay is decoration for a remote: it holds a back button and a
                // watchlist toggle, both duplicated by hardware Back and by the action
                // row. It is also full-screen, so its focusables sit *above* every row
                // in the list geometrically. Leaving them focusable makes Up from the
                // action buttons land on the back button, and from there the list is
                // below a stack of overlay targets rather than beside them - focus never
                // returns, which is the "stuck on Back" trap.
                //
                // canFocus is set on the container rather than on each button: it
                // propagates to every focus target beneath it, so the overlay cannot
                // reintroduce the trap by gaining a new control later. Touch and mouse
                // are unaffected - clickable still works without focus.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (isFocusDriven) {
                                Modifier.focusProperties { canFocus = false }
                            } else {
                                Modifier
                            },
                        )
                        .zIndex(2f),
                ) {
                    DetailFloatingHeader(
                        detail = detail,
                        progress = headerProgress,
                        inWatchlist = state.inWatchlist,
                        onBack = onBack,
                        onToggleWatchlist = viewModel::toggleWatchlist,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )

                    if (headerProgress <= 0.05f) {
                        WbBackButton(
                            onClick = onBack,
                            background = Color.Black.copy(alpha = 0.35f),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .statusBarsPadding()
                                .padding(start = 12.dp, top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
