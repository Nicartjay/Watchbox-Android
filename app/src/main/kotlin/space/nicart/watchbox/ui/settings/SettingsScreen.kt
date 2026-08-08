package space.nicart.watchbox.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.nicart.watchbox.BuildConfig
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.AppTheme
import space.nicart.watchbox.core.ui.paletteForPreview
import space.nicart.watchbox.extension.ExtensionRepoApi
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.ui.components.NavOverlayPadding
import space.nicart.watchbox.ui.components.WbScreenHeader
import space.nicart.watchbox.ui.components.sectionHorizontalPadding

/**
 * Settings.
 *
 * Grouped rows on a plain background, matching Nuvio's settings pattern. The
 * repository URL is editable here because the app has no content of its own until
 * an extension is installed, and the default repo may not be the one you want.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val tokens = MaterialTheme.wb

    var repoDraft by remember(settings.repoUrl) {
        mutableStateOf(settings.repoUrl)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val padding = sectionHorizontalPadding(maxWidth)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = padding,
                end = padding,
                bottom = 18.dp + NavOverlayPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                Box(modifier = Modifier.statusBarsPadding().padding(top = 10.dp)) {
                    WbScreenHeader(title = stringResource(R.string.title_settings))
                }
            }

            // -------------------------------------------------- appearance
            item(key = "appearance-label") {
                SettingsGroupLabel(stringResource(R.string.settings_appearance))
            }

            item(key = "theme") {
                SettingsCard {
                    Text(
                        text = stringResource(R.string.settings_theme),
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.colors.textPrimary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppTheme.entries.forEach { theme ->
                            ThemeSwatch(
                                theme = theme,
                                selected = theme == settings.theme,
                                onClick = { viewModel.setTheme(theme) },
                            )
                        }
                    }
                }
            }

            item(key = "amoled") {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_amoled),
                    checked = settings.amoled,
                    onCheckedChange = viewModel::setAmoled,
                )
            }

            // ---------------------------------------------------- playback
            item(key = "playback-label") {
                SettingsGroupLabel(stringResource(R.string.settings_playback))
            }

            item(key = "auto-next") {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_auto_next),
                    checked = settings.autoPlayNext,
                    onCheckedChange = viewModel::setAutoPlayNext,
                )
            }

            item(key = "nsfw") {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_nsfw),
                    checked = settings.nsfwSourcesEnabled,
                    onCheckedChange = viewModel::setNsfwEnabled,
                )
            }

            // ------------------------------------------------------ server
            item(key = "server-label") {
                SettingsGroupLabel(stringResource(R.string.settings_repo_url))
            }

            item(key = "server") {
                SettingsCard {
                    Text(
                        text = stringResource(R.string.settings_repo_url_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = repoDraft,
                        onValueChange = { repoDraft = it },
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
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingsTextAction(
                            label = "Save",
                            onClick = { viewModel.setRepoUrl(repoDraft) },
                        )
                        SettingsTextAction(
                            label = "Reset",
                            onClick = {
                                viewModel.setRepoUrl("")
                                repoDraft = ExtensionRepoApi.DEFAULT_REPO
                            },
                        )
                    }
                }
            }

            // -------------------------------------------------------- data
            item(key = "data-label") {
                SettingsGroupLabel(stringResource(R.string.settings_data))
            }

            item(key = "clear-history") {
                SettingsActionRow(
                    title = stringResource(R.string.settings_clear_history),
                    onClick = viewModel::clearHistory,
                )
            }

            item(key = "clear-list") {
                SettingsActionRow(
                    title = stringResource(R.string.settings_clear_list),
                    onClick = viewModel::clearWatchlist,
                )
            }

            // ------------------------------------------------------- about
            item(key = "about-label") {
                SettingsGroupLabel(stringResource(R.string.settings_about))
            }

            item(key = "about") {
                SettingsCard {
                    Text(
                        text = "WatchBox for Android",
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.colors.textPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Interface derived from NuvioMobile (GPL-3.0). " +
                            "Plays media from user-installed extensions; this app " +
                            "hosts and distributes no content itself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.wb.colors.textMuted,
        modifier = Modifier.padding(top = 10.dp, start = 4.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val tokens = MaterialTheme.wb
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tokens.colors.surfaceCard)
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val tokens = MaterialTheme.wb
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tokens.colors.surfaceCard)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
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
private fun SettingsActionRow(title: String, onClick: () -> Unit) {
    val tokens = MaterialTheme.wb
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tokens.colors.surfaceCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
        )
    }
}

@Composable
private fun SettingsTextAction(label: String, onClick: () -> Unit) {
    val tokens = MaterialTheme.wb
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.textSecondary,
        )
    }
}

/** A colour dot per accent palette. */
@Composable
private fun ThemeSwatch(
    theme: AppTheme,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.wb
    val accent = Color(paletteForPreview(theme).secondary)

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(accent)
            .clickable(onClick = onClick)
            .then(
                if (selected) {
                    Modifier.border(3.dp, tokens.colors.textPrimary, CircleShape)
                } else {
                    Modifier
                },
            ),
    )
}
