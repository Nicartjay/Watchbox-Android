package space.nicart.watchbox.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import space.nicart.watchbox.R
import space.nicart.watchbox.domain.EpisodeEntry
import space.nicart.watchbox.ui.components.NavOverlayPadding
import space.nicart.watchbox.ui.components.WbBackButton
import space.nicart.watchbox.ui.components.WbEmptyState
import space.nicart.watchbox.ui.components.WbLoading

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
    onPlay: (episode: EpisodeEntry, resumeMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isTablet = maxWidth >= 720.dp
        val contentPadding = if (isTablet) 32.dp else 18.dp
        val contentMaxWidth = if (isTablet) {
            (maxWidth.value * 0.6f).dp.coerceIn(520.dp, 680.dp)
        } else {
            maxWidth
        }
        val heroHeight = detailHeroHeight(maxWidth, isTablet)

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
                        .zIndex(1f),
                    contentPadding = PaddingValues(bottom = 32.dp + NavOverlayPadding),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    item(key = "hero") {
                        DetailHero(
                            detail = detail,
                            heroHeight = heroHeight,
                            scrollOffset = scrollOffset,
                            isTablet = isTablet,
                            contentMaxWidth = contentMaxWidth,
                        )
                    }

                    item(key = "actions") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = contentPadding)
                                .padding(bottom = 20.dp),
                            contentAlignment = Alignment.Center,
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
                            )
                        }
                    }

                    item(key = "meta") {
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

                    if (detail.episodes.isNotEmpty()) {
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
                }

                Box(modifier = Modifier.fillMaxSize().zIndex(2f)) {
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
