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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.nicart.watchbox.core.ui.tvInitialFocus
import space.nicart.watchbox.BuildConfig
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.AppTheme
import space.nicart.watchbox.core.ui.paletteForPreview
import space.nicart.watchbox.data.remote.AppUpdate
import space.nicart.watchbox.data.local.POSTER_SCALE_MAX
import space.nicart.watchbox.data.local.POSTER_SCALE_MIN
import space.nicart.watchbox.data.local.UI_SCALE_MAX
import space.nicart.watchbox.data.local.UI_SCALE_MIN
import space.nicart.watchbox.data.local.ExtensionRepo
import space.nicart.watchbox.ui.player.SubtitleBackground
import space.nicart.watchbox.ui.player.subtitleStyle
import space.nicart.watchbox.core.ui.LocalLayoutMetrics
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
    val metrics = LocalLayoutMetrics.current
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val tokens = MaterialTheme.wb

    var repoDraft by remember { mutableStateOf("") }
    var repoError by remember { mutableStateOf<String?>(null) }

    // Resolved outside the click handler; stringResource is not callable there.
    val addRepoInvalid = stringResource(R.string.settings_repos_invalid)
    val addRepoDuplicate = stringResource(R.string.settings_repos_duplicate)

    BoxWithConstraints(modifier = modifier.fillMaxSize().tvInitialFocus()) {
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

            // ----------------------------------------------- repositories
            item(key = "repos-label") {
                SettingsGroupLabel(stringResource(R.string.settings_repos))
            }

            item(key = "repos") {
                SettingsCard {
                    Text(
                        text = stringResource(R.string.settings_repos_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )

                    Spacer(Modifier.height(12.dp))

                    if (settings.repos.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_repos_empty),
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.colors.textMuted,
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    settings.repos.forEach { repo ->
                        RepoRow(
                            repo = repo,
                            onToggle = { viewModel.setRepoEnabled(repo.url, it) },
                            // Every repository can be removed, including the last:
                            // an empty list is the initial state now that none ships
                            // by default, and the extension screen explains it.
                            onRemove = { viewModel.removeRepo(repo.url) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    // A TextField captures the D-pad: arrow keys become caret movement,
                    // so on a television focus enters this field and can never leave -
                    // every setting below it becomes unreachable. On TV the repository
                    // is added by deep link instead, so the field is simply omitted.
                    if (!metrics.isTv) {
                    OutlinedTextField(
                        value = repoDraft,
                        onValueChange = {
                            repoDraft = it
                            repoError = null
                        },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.settings_repos_hint),
                                color = tokens.colors.textMuted,
                            )
                        },
                        isError = repoError != null,
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

                    repoError?.let { message ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.colors.danger,
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingsTextAction(
                            label = stringResource(R.string.settings_repos_add),
                            onClick = {
                                val url = repoDraft.trim()
                                repoError = when {
                                    url.isBlank() -> null
                                    // Checked here rather than after the write: a
                                    // silent no-op on a typo'd duplicate looks like
                                    // the Add button is broken.
                                    !url.looksLikeHttpUrl() ->
                                        addRepoInvalid
                                    settings.repos.any { existing ->
                                        existing.url.equals(
                                            ExtensionRepo.normaliseUrl(url),
                                            ignoreCase = true,
                                        )
                                    } -> addRepoDuplicate

                                    else -> {
                                        viewModel.addRepo(url)
                                        repoDraft = ""
                                        null
                                    }
                                }
                            },
                        )
                        SettingsTextAction(
                            label = stringResource(R.string.settings_repos_clear),
                            onClick = {
                                viewModel.resetRepos()
                                repoDraft = ""
                                repoError = null
                            },
                        )
                    }
                    }
                }
            }

            // ----------------------------------------------------- display
            item(key = "display-label") {
                SettingsGroupLabel(stringResource(R.string.settings_display))
            }

            item(key = "scale") {
                SettingsCard {
                    Text(
                        text = stringResource(R.string.settings_scale_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                    Spacer(Modifier.height(14.dp))

                    ScaleSlider(
                        label = stringResource(R.string.settings_ui_scale),
                        value = settings.uiScale,
                        range = UI_SCALE_MIN..UI_SCALE_MAX,
                        onValueChange = viewModel::setUiScale,
                    )

                    Spacer(Modifier.height(10.dp))

                    ScaleSlider(
                        label = stringResource(R.string.settings_poster_scale),
                        value = settings.posterScale,
                        range = POSTER_SCALE_MIN..POSTER_SCALE_MAX,
                        onValueChange = viewModel::setPosterScale,
                    )
                }
            }

            // --------------------------------------------------- subtitles
            item(key = "subtitles-label") {
                SettingsGroupLabel(stringResource(R.string.settings_subtitles))
            }

            item(key = "subtitles") {
                SettingsCard {
                    val style = settings.subtitleStyle()

                    SubtitlePreview(style = style)

                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.settings_subtitle_size),
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.textMuted,
                    )
                    Spacer(Modifier.height(6.dp))
                    SubtitleSizeRow(
                        selected = style.size,
                        onSelect = viewModel::setSubtitleSize,
                    )

                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.settings_subtitle_background),
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.textMuted,
                    )
                    Spacer(Modifier.height(6.dp))
                    SubtitleBackgroundColumn(
                        selected = style.background,
                        onSelect = viewModel::setSubtitleBackground,
                    )

                    // Only shown when an edge is actually drawn.
                    if (style.usesEdge) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.settings_subtitle_edge_width),
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.colors.textMuted,
                        )
                        Spacer(Modifier.height(6.dp))
                        SubtitleEdgeWidthRow(
                            selected = style.edgeWidth,
                            onSelect = viewModel::setSubtitleEdgeWidth,
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.settings_subtitle_color),
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.textMuted,
                    )
                    Spacer(Modifier.height(6.dp))
                    SubtitleColorRow(
                        selected = style.textColor,
                        onSelect = viewModel::setSubtitleTextColor,
                    )

                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.settings_subtitle_opacity),
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.textMuted,
                    )
                    Spacer(Modifier.height(6.dp))
                    SubtitleOpacityRow(
                        selected = style.backgroundOpacity,
                        onSelect = viewModel::setSubtitleBackgroundOpacity,
                        // Opacity has no effect without something to be opaque.
                        enabled = style.background == SubtitleBackground.BACKGROUND ||
                            style.background == SubtitleBackground.FULL_BACKGROUND,
                    )
                }
            }

            item(key = "subtitle-bold") {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_subtitle_bold),
                    checked = settings.subtitleBold,
                    onCheckedChange = viewModel::setSubtitleBold,
                )
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
            // ------------------------------------------------------ updates
            item(key = "updates-label") {
                SettingsGroupLabel(stringResource(R.string.settings_updates))
            }

            item(key = "auto-update") {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_auto_check_updates),
                    checked = settings.autoCheckUpdates,
                    onCheckedChange = viewModel::setAutoCheckUpdates,
                )
            }

            item(key = "update-row") {
                UpdateCard(
                    state = updateState,
                    currentVersion = viewModel.currentVersion,
                    onCheck = viewModel::checkForUpdates,
                    onDownload = viewModel::downloadUpdate,
                    onSkip = viewModel::skipUpdate,
                    onDismiss = viewModel::dismissUpdateState,
                )
            }

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

/**
 * Update row.
 *
 * Collapses to a single tappable row most of the time and only expands when
 * there is something to act on. Release notes are shown before downloading so a
 * user is never asked to install something unexplained.
 */
@Composable
private fun UpdateCard(
    state: UpdateUiState,
    currentVersion: String,
    onCheck: () -> Unit,
    onDownload: (AppUpdate) -> Unit,
    onSkip: (AppUpdate) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.wb

    when (state) {
        is UpdateUiState.Available -> SettingsCard {
            Text(
                text = stringResource(R.string.update_available, state.update.versionName),
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.accent,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Installed: $currentVersion · ${state.update.apkSizeBytes.asMegabytes()}",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
            )

            state.update.releaseNotes.takeIf { it.isNotBlank() }?.let { notes ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.update_notes),
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textSecondary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsTextAction(
                    label = stringResource(R.string.update_download),
                    onClick = { onDownload(state.update) },
                )
                SettingsTextAction(
                    label = stringResource(R.string.update_skip),
                    onClick = { onSkip(state.update) },
                )
            }
        }

        is UpdateUiState.Downloading -> SettingsCard {
            Text(
                text = stringResource(R.string.update_downloading, state.percent),
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textPrimary,
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { state.percent / 100f },
                color = tokens.colors.accent,
                trackColor = tokens.colors.surface,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        UpdateUiState.Launching -> SettingsActionRow(
            title = stringResource(R.string.update_launching),
            onClick = {},
        )

        UpdateUiState.Checking -> SettingsActionRow(
            title = stringResource(R.string.update_checking),
            onClick = {},
        )

        UpdateUiState.UpToDate -> SettingsActionRow(
            title = stringResource(R.string.update_up_to_date),
            onClick = onDismiss,
        )

        is UpdateUiState.Failed -> SettingsActionRow(
            title = stringResource(R.string.update_failed, state.message),
            onClick = onCheck,
        )

        UpdateUiState.Idle -> SettingsActionRow(
            title = stringResource(R.string.settings_check_now),
            onClick = onCheck,
        )
    }
}

private fun Long.asMegabytes(): String = "%.1f MB".format(this / 1024.0 / 1024.0)

/**
 * One configured repository: name, enable switch, and removal.
 *
 * The full URL is shown beneath the derived name because two repositories can
 * share an owner, and the URL is the only thing that truly distinguishes them.
 */
@Composable
private fun RepoRow(
    repo: ExtensionRepo,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val tokens = MaterialTheme.wb

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = repo.displayName,
                style = MaterialTheme.typography.bodyLarge,
                // Dimmed when disabled, so the list reads at a glance.
                color = if (repo.enabled) {
                    tokens.colors.textPrimary
                } else {
                    tokens.colors.textMuted
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = repo.url,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Switch(
            checked = repo.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = tokens.colors.onAccent,
                checkedTrackColor = tokens.colors.accent,
                uncheckedThumbColor = tokens.colors.textMuted,
                uncheckedTrackColor = tokens.colors.surfaceCard,
            ),
        )

        Icon(
            imageVector = Icons.Rounded.Delete,
            contentDescription = stringResource(R.string.settings_repos_remove),
            tint = tokens.colors.textMuted,
            modifier = Modifier
                .size(20.dp)
                .clickable(onClick = onRemove),
        )
    }
}

/**
 * Cheap sanity check for a pasted repository URL.
 *
 * Deliberately not strict validation - only a scheme and a host are required.
 * Rejecting anything more adventurous would block self-hosted repos on odd ports
 * or LAN addresses, and the real verification is whether the index fetch succeeds.
 */
private fun String.looksLikeHttpUrl(): Boolean {
    val trimmed = trim()
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false

    val host = trimmed.substringAfter("://").substringBefore('/')
    return host.isNotBlank() && host.contains('.')
}
