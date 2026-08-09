package space.nicart.watchbox.ui.source

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.ui.components.NavOverlayPadding
import space.nicart.watchbox.ui.components.WbBackButton
import space.nicart.watchbox.ui.components.WbEmptyState
import space.nicart.watchbox.ui.components.WbScreenHeader
import space.nicart.watchbox.ui.components.WbSearchField
import space.nicart.watchbox.ui.components.sectionHorizontalPadding

/**
 * Settings for the sources bundled in one extension.
 *
 * Each source's preferences are read out of the extension's own
 * `setupPreferenceScreen` and rendered with the app's controls, so an extension
 * that ships settings is configurable without embedding a second UI toolkit.
 *
 * Sources are labelled only when the extension has more than one, since a single
 * source's name is almost always the extension's name repeated.
 */
@Composable
fun SourceSettingsScreen(
    extensionName: String,
    groups: List<SourcePreferenceGroup>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val padding = sectionHorizontalPadding(maxWidth)

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = padding)
                    .padding(top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WbBackButton(onClick = onBack)
                Spacer(Modifier.size(8.dp))
                WbScreenHeader(title = extensionName, modifier = Modifier.weight(1f))
            }

            if (groups.all { it.preferences.isEmpty() }) {
                WbEmptyState(
                    title = stringResource(R.string.source_no_preferences),
                    modifier = Modifier.padding(horizontal = padding),
                )
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(
                    start = padding,
                    end = padding,
                    bottom = 18.dp + NavOverlayPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                groups.forEach { group ->
                    if (groups.size > 1) {
                        item(key = "label-${group.sourceId}") {
                            Text(
                                text = group.sourceName.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.wb.colors.textMuted,
                                modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                            )
                        }
                    }

                    items(
                        count = group.preferences.size,
                        key = { index -> "${group.sourceId}-${group.preferences[index].key}" },
                    ) { index ->
                        SourcePreferenceRow(group.preferences[index])
                    }
                }
            }
        }
    }
}

/** One source's preferences, kept together under its name. */
data class SourcePreferenceGroup(
    val sourceId: Long,
    val sourceName: String,
    val preferences: List<SourcePreference>,
)

@Composable
private fun SourcePreferenceRow(preference: SourcePreference) {
    val tokens = MaterialTheme.wb

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tokens.colors.surfaceCard)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (preference) {
            is SourcePreference.Switch -> SwitchRow(preference)
            is SourcePreference.Select -> SelectRow(preference)
            is SourcePreference.MultiSelect -> MultiSelectRow(preference)
            is SourcePreference.Text -> TextRow(preference)
            is SourcePreference.Info -> LabelAndSummary(preference.title, preference.summary)
        }
    }
}

@Composable
private fun LabelAndSummary(title: String, summary: String?) {
    val tokens = MaterialTheme.wb
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
        )
        summary?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun SwitchRow(preference: SourcePreference.Switch) {
    val tokens = MaterialTheme.wb
    // Local mirror so the control responds immediately; the extension's
    // SharedPreferences is the durable store but is not observable from here.
    var checked by remember(preference.key) { mutableStateOf(preference.checked) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            LabelAndSummary(preference.title, preference.summary)
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                preference.onChange(it)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = tokens.colors.onAccent,
                checkedTrackColor = tokens.colors.accent,
                uncheckedThumbColor = tokens.colors.textMuted,
                uncheckedTrackColor = tokens.colors.surface,
            ),
        )
    }
}

@Composable
private fun SelectRow(preference: SourcePreference.Select) {
    val tokens = MaterialTheme.wb
    var selected by remember(preference.key) { mutableIntStateOf(preference.selectedIndex) }

    LabelAndSummary(preference.title, preference.summary)

    // Inline options rather than a dialog: source option lists are short, and the
    // app has no dialog pattern to match.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        preference.entries.forEachIndexed { index, entry ->
            val isSelected = index == selected
            Text(
                text = entry,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) tokens.colors.onAccent else tokens.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) tokens.colors.accent else tokens.colors.surface)
                    .clickable {
                        selected = index
                        preference.onChange(index)
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun MultiSelectRow(preference: SourcePreference.MultiSelect) {
    val tokens = MaterialTheme.wb
    var selected by remember(preference.key) { mutableStateOf(preference.selected) }

    LabelAndSummary(preference.title, preference.summary)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        preference.entries.forEachIndexed { index, entry ->
            // Entries and values are parallel arrays; the stored set holds values.
            val value = preference.values.getOrNull(index) ?: return@forEachIndexed
            val isSelected = value in selected
            Text(
                text = entry,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) tokens.colors.onAccent else tokens.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) tokens.colors.accent else tokens.colors.surface)
                    .clickable {
                        selected = if (isSelected) selected - value else selected + value
                        preference.onChange(selected)
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun TextRow(preference: SourcePreference.Text) {
    var value by remember(preference.key) { mutableStateOf(preference.value) }

    LabelAndSummary(preference.title, preference.summary)

    WbSearchField(
        value = value,
        onValueChange = {
            value = it
            preference.onChange(it)
        },
        placeholder = preference.title,
        showSearchIcon = false,
    )
}
