package space.nicart.watchbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import space.nicart.watchbox.core.ui.adaptiveFocus
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.core.ui.wbType

/**
 * Shared building blocks: screen scaffolds, images, headers, states.
 * Ported from NuvioMobile `core/ui/Components.kt`.
 */

/** Bottom inset that clears the floating nav pill (`App.kt:1909` uses 72dp). */
val NavOverlayPadding: Dp = 72.dp

/**
 * A vertically-scrolling screen (`NuvioScreen`, `Components.kt:79-102`).
 * Sections pad themselves when [horizontalPadding] is zero.
 */
@Composable
fun WbScreen(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    horizontalPadding: Dp = 16.dp,
    topPadding: Dp = 10.dp,
    bottomPadding: Dp = 18.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = horizontalPadding,
            end = horizontalPadding,
            top = topPadding,
            bottom = bottomPadding + NavOverlayPadding,
        ),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/**
 * Large page title (`NuvioScreenHeader`) — `displayLarge`: 38sp Bold with
 * -1.2sp tracking.
 */
@Composable
fun WbScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val tokens = MaterialTheme.wb
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.displayLarge,
            color = tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
        }
    }
}

/**
 * Network image with a shimmer placeholder and a title-text fallback.
 *
 * Coil is given an explicit [ImageRequest] so crossfade is consistent whether the
 * bitmap comes from memory, disk or the network.
 */
@Composable
fun WbAsyncImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackLabel: String? = null,
    /**
     * Where the image sits when it does not fill its box.
     *
     * Only matters with [ContentScale.Fit], which letterboxes: a wide logo in a taller
     * box is centred by default, which reads as an indent when everything beside it is
     * left-aligned.
     */
    alignment: Alignment = Alignment.Center,
    /**
     * Draw nothing while loading or on failure, instead of a filled placeholder.
     *
     * For an image laid *over* other artwork - a hero logo - the opaque surface
     * placeholder is worse than an empty gap: it covers the backdrop with a solid
     * block for as long as the request takes, which reads as a rendering fault.
     * A poster in a grid still wants the placeholder, so this is opt-in.
     */
    transparentPlaceholder: Boolean = false,
) {
    val tokens = MaterialTheme.wb
    val placeholderColor = if (transparentPlaceholder) Color.Transparent else tokens.colors.surface

    if (url.isNullOrBlank()) {
        Box(
            modifier = modifier.background(placeholderColor),
            contentAlignment = Alignment.Center,
        ) {
            fallbackLabel?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
        return
    }

    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        contentScale = contentScale,
        alignment = alignment,
        modifier = modifier,
        loading = {
            if (!transparentPlaceholder) {
                Box(modifier = Modifier.fillMaxSize().background(rememberShimmerBrush()))
            }
        },
        error = {
            Box(
                modifier = Modifier.fillMaxSize().background(placeholderColor),
                contentAlignment = Alignment.Center,
            ) {
                fallbackLabel?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.colors.textMuted,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        },
    )
}

/**
 * Diameter of the circular overlay buttons.
 *
 * Shared so a control placed beside one of them can offset itself by the right amount
 * without restating the number - two copies of it drift apart the moment either moves.
 */
val WbOverlayButtonSize: Dp = 40.dp

/** Circular translucent back button (`NuvioBackButton`). */
@Composable
fun WbBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = WbOverlayButtonSize,
    background: Color? = null,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()
    Box(
        modifier = modifier
            .size(size)
            // Before clip, or the stroke is clipped away at the circle's edge. Costs
            // nothing on a touchscreen, where adaptiveFocus returns the chain untouched -
            // which is why every caller gets this and none of them had to ask.
            .adaptiveFocus(interaction, CircleShape, scale = false)
            .clip(CircleShape)
            .then(background?.let { Modifier.background(it) } ?: Modifier)
            // The same interaction source the outline watches. A separate one here would
            // leave the button focusable but undrawn, which is what it was.
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "Back",
            tint = tokens.colors.textPrimary,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Centred spinner. */
@Composable
fun WbLoading(modifier: Modifier = Modifier, size: Dp = 34.dp) {
    val tokens = MaterialTheme.wb
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color = tokens.colors.accent,
            strokeWidth = 3.dp,
            modifier = Modifier.size(size),
        )
    }
}

/**
 * Spinner with a line saying what is being waited for, and for how long.
 *
 * [seconds] shows only once it passes [slowAfterSeconds], which is where a wait
 * stops reading as normal and starts reading as a hang.
 *
 * Deliberately silent about *which* server is being tried: a source resolves all
 * of them inside one suspend call and reports nothing until it returns, so naming
 * one would be invented detail.
 */
@Composable
fun WbLoadingStatus(
    label: String,
    seconds: Int,
    modifier: Modifier = Modifier,
    slowLabel: String? = null,
    slowAfterSeconds: Int = 5,
) {
    val tokens = MaterialTheme.wb
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            color = tokens.colors.accent,
            strokeWidth = 3.dp,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = tokens.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (slowLabel != null && seconds >= slowAfterSeconds) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = slowLabel,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Empty / error state with an optional retry. */
@Composable
fun WbEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.wb
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = tokens.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        body?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            WbPillButton(label = actionLabel, onClick = onAction)
        }
    }
}

/** White pill CTA: `onBackground` fill, `background` label (Nuvio's Play button). */
@Composable
fun WbPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val tokens = MaterialTheme.wb
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(40.dp))
            .background(tokens.colors.textPrimary)
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            leadingIcon?.invoke()
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.background,
            )
        }
    }
}

/** Rounded chip used for filters and season selectors. */
@Composable
fun WbChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .adaptiveFocus(interaction, RoundedCornerShape(20.dp), scale = false)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) tokens.colors.accent else tokens.colors.surfaceCard)
            // Outline, not scale: chips sit in rows whose height is fixed by their
            // neighbours, so scaling would push adjacent chips around.
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) tokens.colors.onAccent else tokens.colors.textSecondary,
        )
    }
}

/** Status-bar spacer for full-bleed screens. */
@Composable
fun WbStatusBarSpacer(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().statusBarsPadding())
}

/** Navigation-bar spacer. */
@Composable
fun WbNavBarSpacer(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().navigationBarsPadding())
}
