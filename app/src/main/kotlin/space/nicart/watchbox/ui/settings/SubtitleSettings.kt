package space.nicart.watchbox.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.LocalLayoutMetrics
import space.nicart.watchbox.core.ui.adaptiveFocus
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.data.remote.SubtitleProvider
import space.nicart.watchbox.ui.components.WbChip
import space.nicart.watchbox.ui.player.SUBTITLE_TEXT_COLORS
import space.nicart.watchbox.ui.player.SubtitleBackground
import space.nicart.watchbox.ui.player.SubtitleEdgeWidth
import space.nicart.watchbox.ui.player.SubtitleSize
import space.nicart.watchbox.ui.player.SubtitleStyle

/**
 * Live preview of the current subtitle appearance.
 *
 * Included because none of these choices can be judged from their names - whether an
 * outline is enough, or a band too heavy, depends entirely on how it looks. Without a
 * preview the only way to evaluate a change is to open a video and find a subtitled
 * moment.
 *
 * This is an approximation drawn with Compose, not Media3's renderer, so it shows
 * the effect rather than the exact pixels. It sits on a checkered-ish backdrop
 * because a preview over flat colour cannot show whether text stays readable.
 */
@Composable
fun SubtitlePreview(
    style: SubtitleStyle,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb
    val textColor = Color(style.textColor)
    val backdropColor = Color(0xFF3A3A3C)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(backdropColor),
        contentAlignment = Alignment.Center,
    ) {
        // A light band mimics a bright scene, which is exactly where subtitles
        // become hard to read.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .align(Alignment.TopCenter)
                .background(Color(0xFFB0B0B4)),
        )

        val bandModifier = when (style.background) {
            SubtitleBackground.FULL_BACKGROUND -> Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = style.backgroundOpacity))

            else -> Modifier
        }

        Box(modifier = bandModifier, contentAlignment = Alignment.Center) {
            val textModifier = when (style.background) {
                SubtitleBackground.BACKGROUND -> Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = style.backgroundOpacity))
                    .padding(horizontal = 6.dp, vertical = 2.dp)

                else -> Modifier
            }

            Text(
                text = "The quick brown fox",
                modifier = textModifier.padding(horizontal = 12.dp),
                textAlign = TextAlign.Center,
                style = TextStyle(
                    color = textColor,
                    // Preview scale is relative to the box, mirroring how Media3
                    // sizes text as a fraction of the video surface.
                    fontSize = (style.size.fraction * PREVIEW_HEIGHT_PX).sp,
                    fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                    shadow = when (style.background) {
                        // Compose has no true outline, so a tight opaque shadow
                        // stands in for one. It reads the same at this size.
                        SubtitleBackground.OUTLINE -> Shadow(
                            Color.Black,
                            Offset(0f, 0f),
                            blurRadius = style.edgeWidth.outlineDp * 3f,
                        )

                        SubtitleBackground.DROP_SHADOW -> Shadow(
                            Color.Black.copy(alpha = 0.85f),
                            Offset(style.edgeWidth.shadowDp, style.edgeWidth.shadowDp),
                            style.edgeWidth.shadowBlurDp,
                        )

                        else -> null
                    },
                ),
            )
        }

        Text(
            text = style.background.label,
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp),
        )
    }
}

/** Nominal preview height in px, used to turn a fraction into a font size. */
private const val PREVIEW_HEIGHT_PX = 300f

/** Horizontally scrolling size chips. */
@Composable
fun SubtitleSizeRow(
    selected: SubtitleSize,
    onSelect: (SubtitleSize) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(SubtitleSize.entries.size) { index ->
            val size = SubtitleSize.entries[index]
            WbChip(
                label = size.label,
                selected = size == selected,
                onClick = { onSelect(size) },
            )
        }
    }
}

/**
 * Which online catalogue the player searches.
 *
 * Two chips rather than a toggle: the labels have to name the providers, because the
 * difference between them is not a degree of the same thing.
 */
@Composable
fun SubtitleProviderRow(
    selected: SubtitleProvider,
    onSelect: (SubtitleProvider) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(SubtitleProvider.entries.size) { index ->
            val provider = SubtitleProvider.entries[index]
            WbChip(
                label = stringResource(
                    when (provider) {
                        SubtitleProvider.OPEN_SUBTITLES_LEGACY ->
                            R.string.settings_subtitle_provider_free
                        SubtitleProvider.OPEN_SUBTITLES_API ->
                            R.string.settings_subtitle_provider_api
                    },
                ),
                selected = provider == selected,
                onClick = { onSelect(provider) },
            )
        }
    }
}

/**
 * One offered artwork language.
 *
 * A fixed shortlist rather than TMDB's full list. Artwork coverage outside a handful of
 * markets is thin enough that most entries would fall straight back to English, and a
 * hundred-item picker to reach that outcome is worse than a short one that is honest about
 * what exists.
 *
 * Held as an ISO 639-1 code because that is what TMDB's `include_image_language` takes.
 */
data class ArtworkLanguage(val code: String, val label: String)

/**
 * Languages offered for posters and title logos.
 *
 * English first because it is the default and by far the best covered. The rest are the
 * markets whose releases carry their own lettering often enough for the choice to matter.
 */
val ARTWORK_LANGUAGES = listOf(
    ArtworkLanguage("en", "English"),
    ArtworkLanguage("ja", "日本語"),
    ArtworkLanguage("ko", "한국어"),
    ArtworkLanguage("zh", "中文"),
    ArtworkLanguage("es", "Español"),
    ArtworkLanguage("fr", "Français"),
    ArtworkLanguage("de", "Deutsch"),
    ArtworkLanguage("pt", "Português"),
    ArtworkLanguage("it", "Italiano"),
    ArtworkLanguage("ru", "Русский"),
)

/**
 * Preferred language for posters and title logos.
 *
 * Artwork only: it does not affect subtitles or the app's own strings. A title logo is drawn
 * per-market, so someone watching Japanese releases can have the Japanese lettering while
 * keeping an English interface.
 *
 * Chips rather than a dropdown, matching the provider row above it, and horizontally
 * scrolling because the list is longer than a phone is wide.
 */
@Composable
fun ArtworkLanguageRow(
    selected: String,
    onSelect: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ARTWORK_LANGUAGES.size) { index ->
            val language = ARTWORK_LANGUAGES[index]
            WbChip(
                label = language.label,
                // Compared case-insensitively: the stored value is normalised on write, but
                // a value written by an older build may not be.
                selected = language.code.equals(selected, ignoreCase = true),
                onClick = { onSelect(language.code) },
            )
        }
    }
}

/**
 * The OpenSubtitles API key.
 *
 * Kept as a draft until committed so a half-typed key is never saved and used for a search.
 * The saved value is not echoed back into the field - it is a credential, and there is nothing
 * useful to do with it on screen beyond confirming that one is set.
 */
@Composable
fun SubtitleApiKeyField(
    saved: String,
    onSave: (String) -> Unit,
) {
    val tokens = MaterialTheme.wb
    val metrics = LocalLayoutMetrics.current
    val focus = remember { FocusRequester() }

    var draft by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.settings_subtitle_api_key),
        style = MaterialTheme.typography.labelMedium,
        color = tokens.colors.textMuted,
    )
    Spacer(Modifier.height(6.dp))

    // On TV a TextField that takes focus on entry raises the IME, which then swallows every
    // D-pad press and makes the rest of the screen unreachable. Pressing OK on this row is
    // what hands focus to the field. Same convention as the repository field.
    if (metrics.isTv && !editing) {
        SubtitleKeyRow(
            label = when {
                draft.isNotBlank() -> draft.masked()
                saved.isNotBlank() -> stringResource(R.string.settings_subtitle_api_key_set)
                else -> stringResource(R.string.settings_subtitle_api_key_hint)
            },
            onClick = { editing = true },
        )
        return
    }

    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        placeholder = {
            Text(
                text = if (saved.isNotBlank()) {
                    stringResource(R.string.settings_subtitle_api_key_set)
                } else {
                    stringResource(R.string.settings_subtitle_api_key_hint)
                },
                color = tokens.colors.textMuted,
            )
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = tokens.colors.surface,
            unfocusedContainerColor = tokens.colors.surface,
            focusedBorderColor = tokens.colors.borderDefault,
            unfocusedBorderColor = tokens.colors.borderSubtle,
            focusedTextColor = tokens.colors.textPrimary,
            unfocusedTextColor = tokens.colors.textPrimary,
            cursorColor = tokens.colors.accent,
        ),
        // Done commits, so a remote's OK saves the key without having to reach a button.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = {
                onSave(draft)
                draft = ""
                editing = false
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus),
    )

    LaunchedEffect(editing) {
        if (editing) runCatching { focus.requestFocus() }
    }
}

/** Row that stands in for the key field on TV until the user opens it. */
@Composable
private fun SubtitleKeyRow(label: String, onClick: () -> Unit) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .adaptiveFocus(interaction, RoundedCornerShape(12.dp), scale = false)
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Shows only the tail of a key, so a shoulder-surfer sees nothing useful. */
private fun String.masked(): String =
    if (length <= MASK_VISIBLE) "•".repeat(length) else "•".repeat(MASK_VISIBLE) + takeLast(MASK_VISIBLE)

private const val MASK_VISIBLE = 4

/**
 * Background style options, one per row.
 *
 * Listed vertically rather than as chips because the labels are long enough that a
 * horizontal strip would either truncate them or scroll past the selection.
 */
@Composable
fun SubtitleBackgroundColumn(
    selected: SubtitleBackground,
    onSelect: (SubtitleBackground) -> Unit,
) {
    val tokens = MaterialTheme.wb

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SubtitleBackground.entries.forEach { option ->
            val isSelected = option == selected
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) tokens.colors.onAccent else tokens.colors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) tokens.colors.accent else tokens.colors.surface)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

/** Preset colour swatches. */
@Composable
fun SubtitleColorRow(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val tokens = MaterialTheme.wb

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SUBTITLE_TEXT_COLORS.forEach { (_, color) ->
            val isSelected = color == selected
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) {
                            tokens.colors.accent
                        } else {
                            tokens.colors.borderSubtle
                        },
                        shape = CircleShape,
                    )
                    .clickable { onSelect(color) },
            )
        }
    }
}

/**
 * Background opacity, in steps.
 *
 * Stepped rather than a continuous slider: the useful range is narrow, and exact
 * values are meaningless to choose between by eye.
 */
@Composable
fun SubtitleOpacityRow(
    selected: Float,
    onSelect: (Float) -> Unit,
    enabled: Boolean,
) {
    val tokens = MaterialTheme.wb

    Column {
        if (!enabled) {
            // Explained rather than hidden, so the control does not appear and
            // disappear as the background style changes.
            Text(
                text = "Only used with a background or band",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
            )
            Spacer(Modifier.height(6.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OPACITY_STEPS.forEach { step ->
                WbChip(
                    label = "${(step * 100).toInt()}%",
                    selected = step == selected,
                    onClick = { if (enabled) onSelect(step) },
                )
            }
        }
    }
}

private val OPACITY_STEPS = listOf(0.3f, 0.6f, 0.8f, 1f)

/** Outline / shadow weight chips. Only meaningful when an edge is drawn. */
@Composable
fun SubtitleEdgeWidthRow(
    selected: SubtitleEdgeWidth,
    onSelect: (SubtitleEdgeWidth) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(SubtitleEdgeWidth.entries.size) { index ->
            val width = SubtitleEdgeWidth.entries[index]
            WbChip(
                label = width.label,
                selected = width == selected,
                onClick = { onSelect(width) },
            )
        }
    }
}
