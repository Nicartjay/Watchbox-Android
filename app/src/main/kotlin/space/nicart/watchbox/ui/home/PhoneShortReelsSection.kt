package space.nicart.watchbox.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.ui.browse.SourceEntry
import space.nicart.watchbox.ui.components.WbChip
import space.nicart.watchbox.ui.components.WbPosterCard

/** Always discoverable on phone Home, including before an Arvin extension is installed. */
@Composable
internal fun PhoneShortReelsSection(
    sources: List<SourceEntry>,
    titles: List<AnimeCard>,
    horizontalPadding: Dp,
    hasRepositories: Boolean,
    onManageExtensions: () -> Unit,
    onBrowseSource: (Long, String) -> Unit,
    onOpenAnime: (AnimeCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.SmartDisplay,
                contentDescription = null,
                tint = tokens.colors.accent,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = stringResource(R.string.section_short_reels),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onManageExtensions) {
                Text(
                    stringResource(
                        if (hasRepositories) R.string.title_extensions else R.string.action_add_repository,
                    ),
                    color = tokens.colors.accent,
                )
            }
        }

        if (sources.isEmpty()) {
            Text(
                text = stringResource(R.string.empty_short_reels_body),
                color = tokens.colors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = horizontalPadding),
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sources, key = { it.id }) { source ->
                    WbChip(
                        label = source.name,
                        selected = false,
                        onClick = { onBrowseSource(source.id, source.name) },
                    )
                }
            }
        }

        if (titles.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(titles, key = { it.key }) { card ->
                    WbPosterCard(
                        card = card,
                        subtitle = card.sourceName,
                        aspectRatio = 9f / 16f,
                        onClick = { onOpenAnime(card) },
                    )
                }
            }
        }
    }
}
