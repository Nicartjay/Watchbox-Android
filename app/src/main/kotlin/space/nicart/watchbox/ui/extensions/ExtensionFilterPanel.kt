package space.nicart.watchbox.ui.extensions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.data.local.ExtensionRepo

/**
 * Filter panel for the extension list.
 *
 * Uses the same right-edge drawer as the player pickers and the source filters, so
 * filtering looks the same everywhere in the app.
 *
 * Sections are hidden when they would offer no choice - a language filter with one
 * language, or a repository filter with one repository, only takes up space and
 * implies a decision that does not exist.
 */
@Composable
fun ExtensionFilterPanel(
    filters: ExtensionFilters,
    languages: List<String>,
    repos: List<ExtensionRepo>,
    visible: Boolean,
    onToggleLanguage: (String) -> Unit,
    onSetNsfw: (NsfwFilter) -> Unit,
    onToggleRepo: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.wb

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(160)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f))
                .clickable(onClick = onDismiss),
        )
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(tween(250)) { it },
            exit = slideOutHorizontally(tween(200)) { it },
        ) {
            Column(
                modifier = Modifier
                    .width(420.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .background(tokens.colors.surfaceElevated)
                    .statusBarsPadding()
                    .padding(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.extensions_filters),
                        style = MaterialTheme.typography.titleLarge,
                        color = tokens.colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        tint = tokens.colors.textMuted,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable(onClick = onDismiss),
                    )
                }

                Spacer(Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    FilterSectionLabel(stringResource(R.string.extensions_filter_nsfw))
                    NsfwFilter.entries.forEach { option ->
                        FilterOptionRow(
                            label = stringResource(option.labelRes()),
                            selected = filters.nsfw == option,
                            onClick = { onSetNsfw(option) },
                        )
                    }

                    // One language is no choice at all.
                    if (languages.size > 1) {
                        Spacer(Modifier.height(10.dp))
                        FilterSectionLabel(stringResource(R.string.extensions_filter_language))
                        languages.forEach { lang ->
                            FilterOptionRow(
                                label = lang.uppercase(),
                                selected = lang in filters.languages,
                                onClick = { onToggleLanguage(lang) },
                            )
                        }
                    }

                    if (repos.size > 1) {
                        Spacer(Modifier.height(10.dp))
                        FilterSectionLabel(stringResource(R.string.extensions_filter_repo))
                        repos.forEach { repo ->
                            FilterOptionRow(
                                label = repo.displayName,
                                selected = repo.url in filters.repoUrls,
                                onClick = { onToggleRepo(repo.url) },
                                // A disabled repository contributes nothing to the
                                // list, so filtering by it could only ever return
                                // an empty result.
                                enabled = repo.enabled,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (filters.isActive) tokens.colors.accent else tokens.colors.surface,
                        )
                        .clickable(onClick = onReset)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.extensions_filter_reset),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (filters.isActive) {
                            tokens.colors.onAccent
                        } else {
                            tokens.colors.textSecondary
                        },
                    )
                }
            }
        }
    }
}

private fun NsfwFilter.labelRes(): Int = when (this) {
    NsfwFilter.ALL -> R.string.extensions_filter_nsfw_all
    NsfwFilter.HIDE -> R.string.extensions_filter_nsfw_hide
    NsfwFilter.ONLY -> R.string.extensions_filter_nsfw_only
}

@Composable
private fun FilterSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.wb.colors.textMuted,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun FilterOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val tokens = MaterialTheme.wb

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) tokens.colors.accent else tokens.colors.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                selected -> tokens.colors.onAccent
                !enabled -> tokens.colors.textMuted
                else -> tokens.colors.textSecondary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = tokens.colors.onAccent,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
