package space.nicart.watchbox.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.nicart.watchbox.core.ui.LocalPosterScale
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.tvFocusable
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.domain.AnimeCard
import androidx.compose.ui.res.stringResource
import space.nicart.watchbox.R
import space.nicart.watchbox.ui.components.WbEmptyState
import space.nicart.watchbox.ui.components.WbAsyncImage
import space.nicart.watchbox.ui.home.HomeViewModel

/**
 * TV home: a backdrop that follows focus, over rows of posters.
 *
 * The layout inverts the phone's. On a phone the hero is a card you scroll past; on a
 * television it is the background, and it changes to match whatever the D-pad is
 * currently on. That gives the screen a sense of place that a scrolling list cannot,
 * and it is the pattern every leanback interface uses - including NuvioTV - because
 * at three metres a large image is the only reliable way to convey what is selected.
 *
 * Text sizes are not the phone's scaled up. They are chosen for legibility at
 * distance, where anything below roughly 18sp on a 1080p panel becomes unreadable.
 */
@Composable
fun TvHomeScreen(
    viewModel: HomeViewModel,
    artworkViewModel: TvArtworkViewModel,
    onOpenAnime: (AnimeCard) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tokens = MaterialTheme.wb

    // Whatever the D-pad last landed on, with TMDB artwork attached once it resolves.
    val focused by artworkViewModel.focused.collectAsStateWithLifecycle()
    val artwork by artworkViewModel.artwork.collectAsStateWithLifecycle()

    // Seeded from the hero list so the backdrop is populated before the D-pad has
    // touched anything - otherwise the screen opens on flat black. Hero cards are
    // already enriched, so this needs no lookup.
    val hero = state.feed?.hero?.firstOrNull()
    val backdrop = focused ?: hero

    // Otherwise the TV home is simply empty, with nothing to focus and no indication
    // of what is missing - the worst possible first-run state on a device where the
    // only input is a remote.
    if (state.hasNoSources) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(start = TV_CONTENT_START, end = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            WbEmptyState(
                title = stringResource(
                    if (state.hasNoRepos) {
                        R.string.empty_no_repos_title
                    } else {
                        R.string.empty_no_sources_title
                    },
                ),
                body = stringResource(
                    if (state.hasNoRepos) {
                        R.string.empty_no_repos_body
                    } else {
                        R.string.empty_no_sources_body
                    },
                ),
                actionLabel = stringResource(
                    if (state.hasNoRepos) {
                        R.string.action_add_repository
                    } else {
                        R.string.action_browse_extensions
                    },
                ),
                onAction = onOpenSettings,
            )
        }
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        TvBackdrop(card = backdrop)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                // Overscan-safe: a television can crop up to about 5% of each edge.
                start = TV_CONTENT_START,
                end = 48.dp,
                top = 40.dp,
                bottom = 48.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            // Details for the focused title sit above the rows, in the space the
            // backdrop occupies, so the eye does not travel to a corner.
            item(key = "focused-detail") {
                TvFocusedDetail(card = backdrop)
            }

            state.feed?.rows.orEmpty().forEach { row ->
                val rowKey = "row-${row.sourceId}-${row.title}"

                item(key = rowKey) {
                    // Requested as the row composes rather than up front: rows below
                    // the fold are often never reached, and fetching them first would
                    // delay the artwork for the row being looked at.
                    LaunchedEffect(rowKey) {
                        artworkViewModel.onRowVisible(rowKey, row.items)
                    }

                    TvPosterRow(
                        title = row.title,
                        items = row.items,
                        artwork = artwork,
                        onFocus = artworkViewModel::onFocus,
                        onClick = onOpenAnime,
                    )
                }
            }
        }
    }
}

/**
 * Full-bleed backdrop for the focused title.
 *
 * Scrimmed heavily on the left and bottom because the rows and text sit there;
 * without it, poster art with a bright edge makes white text vanish.
 */
@Composable
private fun TvBackdrop(card: AnimeCard?) {
    val tokens = MaterialTheme.wb

    Box(modifier = Modifier.fillMaxSize()) {
        WbAsyncImage(
            // Backdrop first: it is 16:9 and composed for this use. A portrait
            // poster cropped to fill a widescreen panel loses most of the frame,
            // usually including the subject.
            url = card?.backdropUrl ?: card?.posterUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Two gradients rather than one: a single diagonal scrim either over-darkens
        // the image or under-protects the text, depending on the artwork.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to tokens.colors.background.copy(alpha = 0.96f),
                        0.55f to tokens.colors.background.copy(alpha = 0.60f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to tokens.colors.background.copy(alpha = 0.55f),
                        1f to tokens.colors.background,
                    ),
                ),
        )
    }
}

/** Title and metadata for whatever currently has focus. */
@Composable
private fun TvFocusedDetail(card: AnimeCard?) {
    val tokens = MaterialTheme.wb

    Column(
        modifier = Modifier
            .fillMaxWidth(0.55f)
            .height(220.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        // TMDB's logo is the official wordmark, already set in the title's own
        // typeface. Where one exists it is strictly better than re-typesetting the
        // name, which is what the phone hero does too.
        val logo = card?.logoUrl

        if (logo != null) {
            WbAsyncImage(
                url = logo,
                contentDescription = card.title,
                // Fit, never Crop: a logo is mostly transparent and cropping it
                // cuts the wordmark.
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .height(LOGO_HEIGHT),
            )
        } else {
            Text(
                text = card?.title.orEmpty(),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = tokens.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(10.dp))

        // Built from whatever TMDB returned; a card with no match still shows its
        // source, so the line is never empty.
        val meta = listOfNotNull(
            card?.year?.takeIf { it.isNotBlank() },
            card?.genres?.take(2)?.joinToString(" · ")?.takeIf { it.isNotBlank() },
            card?.sourceName?.takeIf { it.isNotBlank() },
        ).joinToString("  ·  ")

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
 * One horizontal rail of posters.
 *
 * `onFocus` fires as the D-pad crosses each card, which is what drives the backdrop.
 * It is deliberately separate from `onClick`: on a TV, moving over an item is a
 * meaningful event in its own right, unlike on a touchscreen where the two collapse.
 */
@Composable
fun TvPosterRow(
    title: String,
    items: List<AnimeCard>,
    onClick: (AnimeCard) -> Unit,
    artwork: Map<String, AnimeCard> = emptyMap(),
    onFocus: (AnimeCard) -> Unit = {},
) {
    val tokens = MaterialTheme.wb

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textPrimary,
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            // Room for the focused card to scale up without being clipped by the row.
            contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp),
        ) {
            items(items = items, key = { it.key }) { card ->
                // The enriched copy when it exists; the original otherwise, so a card
                // is never blank while its artwork is in flight.
                val resolved = artwork[card.key] ?: card

                TvLandscapeCard(
                    card = resolved,
                    onFocus = { onFocus(card) },
                    onClick = { onClick(card) },
                )
            }
        }
    }
}

@Composable
fun TvLandscapeCard(
    card: AnimeCard,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    // TV cards size themselves rather than filling a grid cell, so the poster scale has
    // to be applied here - the shared WbPosterCard path never runs on this screen.
    val cardWidth = CARD_WIDTH * LocalPosterScale.current

    Column(
        modifier = Modifier.width(cardWidth),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CARD_ASPECT)
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.colors.surfaceCard)
                .tvFocusable(interaction, RoundedCornerShape(10.dp))
                .clickable(
                    interactionSource = interaction,
                    // Compose's ripple is invisible at TV distance; the border and
                    // scale from tvFocusable are the affordance instead.
                    indication = null,
                    onClick = onClick,
                ),
        ) {
            WbAsyncImage(
                // Backdrop first. Falling back to the portrait poster is deliberate
                // rather than showing an empty card: cropped it is wrong, but a
                // recognisable wrong image beats a grey box while TMDB resolves.
                url = card.cardBackdropUrl ?: card.posterUrl,
                contentDescription = card.title,
                contentScale = ContentScale.Crop,
                fallbackLabel = card.title,
                modifier = Modifier.fillMaxSize(),
            )

            // Scrim only under the logo, so artwork stays bright everywhere else.
            if (card.logoUrl != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.55f to Color.Black.copy(alpha = 0.45f),
                                1f to Color.Black.copy(alpha = 0.75f),
                            ),
                        ),
                )
                WbAsyncImage(
                    url = card.logoUrl,
                    contentDescription = card.title,
                    // Fit, never Crop: a logo is mostly transparent and cropping
                    // cuts the wordmark.
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .fillMaxWidth(0.7f)
                        .height(CARD_LOGO_HEIGHT),
                )
            }
        }

        // Only when no logo was found, or the title would appear twice.
        if (card.logoUrl == null) {
            Text(
                text = card.title,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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

/** Tall enough for a wide wordmark without dominating the screen. */
private val LOGO_HEIGHT = 108.dp

/**
 * Landscape card metrics.
 *
 * Wider than the portrait poster it replaces: at 16:9 a card of the same height would
 * be enormous, so the height comes down and the width goes up, which also puts more
 * cards on screen per row.
 */
private val CARD_WIDTH = 300.dp

/** 16:9, matching the backdrop it displays. */
private const val CARD_ASPECT = 1.777f

private val CARD_LOGO_HEIGHT = 40.dp
