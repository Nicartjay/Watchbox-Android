package space.nicart.watchbox.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.tvFocusable
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.domain.AnimeCard
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
    onOpenAnime: (AnimeCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tokens = MaterialTheme.wb

    // Whatever the D-pad last landed on. Drives the backdrop, so moving across a row
    // changes the background rather than requiring a separate action.
    var focused by remember { mutableStateOf<AnimeCard?>(null) }

    // Seeded from the hero list so the backdrop is populated before the D-pad has
    // touched anything - otherwise the screen opens on flat black.
    val hero = state.feed?.hero?.firstOrNull()
    val backdrop = focused ?: hero

    Box(modifier = modifier.fillMaxSize()) {
        TvBackdrop(card = backdrop)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                // Overscan-safe: a television can crop up to about 5% of each edge.
                start = 48.dp,
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
                item(key = "row-${row.sourceId}-${row.title}") {
                    TvPosterRow(
                        title = row.title,
                        items = row.items,
                        onFocus = { focused = it },
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
            url = card?.posterUrl,
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
        Text(
            text = card?.title.orEmpty(),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = tokens.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        card?.sourceName?.takeIf { it.isNotBlank() }?.let { source ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = source,
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textMuted,
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
private fun TvPosterRow(
    title: String,
    items: List<AnimeCard>,
    onFocus: (AnimeCard) -> Unit,
    onClick: (AnimeCard) -> Unit,
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
                TvPosterCard(
                    card = card,
                    onFocus = { onFocus(card) },
                    onClick = { onClick(card) },
                )
            }
        }
    }
}

@Composable
private fun TvPosterCard(
    card: AnimeCard,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Column(
        modifier = Modifier.width(POSTER_WIDTH),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(POSTER_ASPECT)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.colors.surfaceCard)
                .tvFocusable(interaction, RoundedCornerShape(12.dp))
                .clickable(
                    interactionSource = interaction,
                    // Compose's ripple is invisible at TV distance; the border and
                    // scale from tvFocusable are the affordance instead.
                    indication = null,
                    onClick = onClick,
                ),
        ) {
            WbAsyncImage(
                url = card.posterUrl,
                contentDescription = card.title,
                contentScale = ContentScale.Crop,
                fallbackLabel = card.title,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Text(
            text = card.title,
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    // Reported through the interaction source rather than onFocusChanged so the
    // backdrop and the visual focus state can never disagree.
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

private val POSTER_WIDTH = 168.dp

/** 2:3, the standard poster ratio. */
private const val POSTER_ASPECT = 0.675f
