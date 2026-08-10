package space.nicart.watchbox.ui.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import space.nicart.watchbox.core.ui.adaptiveFocus
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.ui.ExperimentalComposeUiApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
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
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.ui.components.WbSearchField

/**
 * Filter panel for one source.
 *
 * Uses the same right-edge drawer as the player's quality and episode pickers, so
 * filters do not introduce a third presentation style.
 *
 * Every [AnimeFilter] subclass the ABI defines is handled. An unrecognised filter
 * would otherwise render as nothing at all, which looks like a missing feature
 * rather than an unsupported type.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SourceFilterPanel(
    entries: List<FilterEntry>,
    visible: Boolean,
    onChange: (path: List<Int>, value: Any?) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.wb
    val panelFocus = remember { FocusRequester() }
    val applyFocus = remember { FocusRequester() }

    // Pull focus in when the drawer opens, so the first D-pad press acts on a filter
    // rather than the grid behind it.
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        // Retried because requestFocus reports success even when no focusable node is
        // attached yet: the drawer is still animating in on the first frames.
        repeat(20) {
            runCatching { panelFocus.requestFocus() }
            delay(25)
        }
    }

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
                    // Keeps D-pad focus inside the drawer: the content behind is still
                    // laid out and focusable, so without this Down scrolls the hidden
                    // grid and the panel can never be reached with a remote. Back and
                    // Escape close it, since focus can no longer leave by moving.
                    .focusRequester(panelFocus)
                    // Cancels focus exit outright: the grid behind stays focusable, and
                    // a remote has no pointer to dismiss with, so focus escaping the
                    // drawer leaves no way back into it.
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .focusGroup()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Back, Key.Escape -> {
                                onDismiss()
                                true
                            }

                            else -> false
                        }
                    }
                    .padding(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.source_filters),
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

                if (entries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.source_filters_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .focusProperties { down = applyFocus }
                            .focusGroup(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(
                            count = entries.size,
                            key = { index -> entries[index].path.joinToString("-") },
                        ) { index ->
                            FilterRow(entry = entries[index], onChange = onChange)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanelButton(
                        label = stringResource(R.string.source_filters_reset),
                        filled = false,
                        onClick = onReset,
                        modifier = Modifier.weight(1f),
                    )
                    PanelButton(
                        label = stringResource(R.string.source_filters_apply),
                        filled = true,
                        onClick = onApply,
                        modifier = Modifier.weight(1f).focusRequester(applyFocus),
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (filled) tokens.colors.accent else tokens.colors.surface)
            .adaptiveFocus(
                interaction,
                RoundedCornerShape(12.dp),
                scale = false,
                borderColor = if (filled) {
                    tokens.colors.surfaceElevated
                } else {
                    Color.White
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (filled) tokens.colors.onAccent else tokens.colors.textSecondary,
        )
    }
}

@Composable
private fun FilterRow(
    entry: FilterEntry,
    onChange: (path: List<Int>, value: Any?) -> Unit,
) {
    val tokens = MaterialTheme.wb
    // Group members are indented so the grouping survives being flattened.
    val indent = (entry.depth * 12).dp

    Column(modifier = Modifier.padding(start = indent)) {
        when (val filter = entry.filter) {
            is AnimeFilter.Header -> Text(
                text = filter.name,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textMuted,
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
            )

            is AnimeFilter.Separator -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(vertical = 6.dp)
                    .background(tokens.colors.borderSubtle),
            )

            is AnimeFilter.CheckBox -> ToggleRow(
                label = filter.name,
                selected = filter.state,
                onClick = { onChange(entry.path, !filter.state) },
            )

            // Tri-state cycles rather than offering three controls: the ABI's
            // include/exclude pair is one value, and two checkboxes could express
            // the impossible "included and excluded".
            is AnimeFilter.TriState -> TriStateRow(
                label = filter.name,
                state = filter.state,
                onClick = { onChange(entry.path, nextTriState(filter.state)) },
            )

            is AnimeFilter.Select<*> -> SelectRow(
                label = filter.name,
                entries = filter.values.map { it.toString() },
                selectedIndex = filter.state,
                onSelect = { onChange(entry.path, it) },
            )

            is AnimeFilter.Sort -> SortRow(
                label = filter.name,
                columns = filter.values.toList(),
                selection = filter.state,
                onSelect = { index ->
                    onChange(entry.path, nextSortSelection(filter.state, index))
                },
            )

            is AnimeFilter.Text -> Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                Text(
                    text = filter.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textMuted,
                )
                WbSearchField(
                    value = filter.state,
                    onValueChange = { onChange(entry.path, it) },
                    placeholder = filter.name,
                    showSearchIcon = false,
                )
            }

            // Groups are labels only; their children follow as separate rows.
            is AnimeFilter.Group<*> -> Text(
                text = filter.name,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textMuted,
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .adaptiveFocus(interaction, RoundedCornerShape(10.dp), scale = false)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (selected) tokens.colors.accent else tokens.colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = tokens.colors.onAccent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TriStateRow(label: String, state: Int, onClick: () -> Unit) {
    val tokens = MaterialTheme.wb
    val (background, tint, glyph) = when (state) {
        AnimeFilter.TriState.STATE_INCLUDE ->
            Triple(tokens.colors.accent, tokens.colors.onAccent, "+")

        AnimeFilter.TriState.STATE_EXCLUDE ->
            Triple(tokens.colors.danger, tokens.colors.textPrimary, "−")

        else -> Triple(tokens.colors.surface, tokens.colors.textMuted, "")
    }
    val triInteraction = rememberFocusInteraction()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .adaptiveFocus(triInteraction, RoundedCornerShape(10.dp), scale = false)
            .clickable(
                interactionSource = triInteraction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            if (glyph.isNotEmpty()) {
                Text(
                    text = glyph,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SelectRow(
    label: String,
    entries: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val tokens = MaterialTheme.wb

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.textMuted,
        )
        entries.forEachIndexed { index, entry ->
            val isSelected = index == selectedIndex
            val optionInteraction = rememberFocusInteraction()
            Text(
                text = entry,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) tokens.colors.onAccent else tokens.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) tokens.colors.accent else tokens.colors.surface)
                    .adaptiveFocus(optionInteraction, RoundedCornerShape(8.dp), scale = false)
                    .clickable(
                        interactionSource = optionInteraction,
                        indication = LocalIndication.current,
                    ) { onSelect(index) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SortRow(
    label: String,
    columns: List<String>,
    selection: AnimeFilter.Sort.Selection?,
    onSelect: (Int) -> Unit,
) {
    val tokens = MaterialTheme.wb

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.textMuted,
        )
        columns.forEachIndexed { index, column ->
            val isSelected = selection?.index == index
            val sortInteraction = rememberFocusInteraction()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) tokens.colors.accent else tokens.colors.surface)
                    .adaptiveFocus(sortInteraction, RoundedCornerShape(8.dp), scale = false)
                    .clickable(
                        interactionSource = sortInteraction,
                        indication = LocalIndication.current,
                    ) { onSelect(index) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = column,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) {
                        tokens.colors.onAccent
                    } else {
                        tokens.colors.textSecondary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Direction is only meaningful for the active column.
                if (isSelected) {
                    Icon(
                        imageVector = if (selection.ascending) {
                            Icons.Rounded.ArrowUpward
                        } else {
                            Icons.Rounded.ArrowDownward
                        },
                        contentDescription = null,
                        tint = tokens.colors.onAccent,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
