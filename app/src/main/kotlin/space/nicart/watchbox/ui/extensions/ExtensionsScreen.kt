package space.nicart.watchbox.ui.extensions

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.nicart.watchbox.core.ui.tvInitialFocus
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.extension.model.Extension
import space.nicart.watchbox.extension.model.InstallStep
import space.nicart.watchbox.ui.components.NavOverlayPadding
import space.nicart.watchbox.ui.components.WbBackButton
import space.nicart.watchbox.ui.components.WbEmptyState
import space.nicart.watchbox.ui.components.WbScreenHeader
import space.nicart.watchbox.ui.components.WbSearchField
import space.nicart.watchbox.ui.components.sectionHorizontalPadding

/**
 * Extension manager.
 *
 * Installed extensions first, then everything else the repository offers.
 * Load failures are shown rather than hidden: an extension that will not link is
 * the single most likely thing to go wrong here, and silently omitting it makes
 * that impossible to diagnose.
 */
@Composable
fun ExtensionsScreen(
    viewModel: ExtensionsViewModel,
    onBack: () -> Unit,
    onOpenSettings: (Extension.Installed) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tokens = MaterialTheme.wb

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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WbBackButton(onClick = onBack)
                    Spacer(Modifier.size(8.dp))
                    WbScreenHeader(
                        title = stringResource(R.string.title_extensions),
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (state.filters.isActive) {
                                    tokens.colors.accent
                                } else {
                                    tokens.colors.surface
                                },
                            )
                            .clickable { viewModel.setFilterPanelOpen(true) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FilterList,
                            contentDescription = stringResource(R.string.extensions_filters),
                            tint = if (state.filters.isActive) {
                                tokens.colors.onAccent
                            } else {
                                tokens.colors.textSecondary
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(tokens.colors.surface)
                            .clickable(onClick = viewModel::refresh),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(
                                color = tokens.colors.accent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.extensions_refresh),
                                tint = tokens.colors.textSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            item(key = "search") {
                WbSearchField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = stringResource(R.string.extensions_search_hint),
                )
            }

            state.errorMessage?.let { message ->
                item(key = "error") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(tokens.colors.danger.copy(alpha = 0.12f))
                            .clickable(onClick = viewModel::dismissError)
                            .padding(16.dp),
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.danger,
                        )
                    }
                }
            }

            // Surfaced deliberately — see the class note.
            if (state.failures.isNotEmpty()) {
                item(key = "failures") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(tokens.colors.warning.copy(alpha = 0.10f))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.extensions_failed_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = tokens.colors.warning,
                        )
                        state.failures.forEach { failure ->
                            Text(
                                text = failure,
                                style = MaterialTheme.typography.labelSmall,
                                color = tokens.colors.textMuted,
                            )
                        }
                    }
                }
            }

            if (state.installed.isNotEmpty()) {
                item(key = "installed-label") {
                    SectionLabel(stringResource(R.string.extensions_installed))
                }
                items(items = state.installed, key = { it.pkgName }) { extension ->
                    InstalledRow(
                        extension = extension,
                        onUninstall = { viewModel.uninstall(extension) },
                        onOpenSettings = extension
                            .takeIf { it.hasConfigurableSources() }
                            ?.let { { onOpenSettings(it) } },
                    )
                }
            }

            // Distinct from "no extensions found": with no repository configured
            // there is nothing to search, and telling the user to add one is the
            // only useful thing this screen can say.
            if (state.repos.isEmpty()) {
                item(key = "no-repos") {
                    WbEmptyState(
                        title = stringResource(R.string.extensions_no_repos_title),
                        body = stringResource(R.string.extensions_no_repos_body),
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
                return@LazyColumn
            }

            item(key = "available-label") {
                SectionLabel(stringResource(R.string.extensions_available))
            }

            if (state.available.isEmpty() && !state.isRefreshing) {
                item(key = "available-empty") {
                    Text(
                        text = if (state.query.isBlank()) {
                            stringResource(R.string.extensions_empty_available)
                        } else {
                            stringResource(R.string.extensions_no_matches)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }

            items(items = state.available, key = { it.pkgName }) { extension ->
                AvailableRow(
                    extension = extension,
                    step = state.installing[extension.pkgName],
                    onInstall = { viewModel.install(extension) },
                )
            }
        }

        ExtensionFilterPanel(
            filters = state.filters,
            languages = state.languages,
            repos = state.repos,
            visible = state.filterPanelOpen,
            onToggleLanguage = viewModel::toggleLanguage,
            onSetNsfw = viewModel::setNsfwFilter,
            onToggleRepo = viewModel::toggleRepo,
            onReset = viewModel::resetFilters,
            onDismiss = { viewModel.setFilterPanelOpen(false) },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.wb.colors.textMuted,
        modifier = Modifier.padding(top = 10.dp, start = 4.dp),
    )
}

@Composable
private fun InstalledRow(
    extension: Extension.Installed,
    onUninstall: () -> Unit,
    onOpenSettings: (() -> Unit)?,
) {
    val tokens = MaterialTheme.wb

    ExtensionRow(
        title = extension.name,
        subtitle = buildString {
            append("v${extension.versionName}")
            if (extension.lang.isNotBlank()) append(" · ${extension.lang.uppercase()}")
            append(" · ${extension.sources.size} source")
            if (extension.sources.size != 1) append("s")
        },
        badge = when {
            extension.isObsolete -> stringResource(R.string.extensions_obsolete)
            extension.hasUpdate -> stringResource(R.string.extensions_update)
            extension.isNsfw -> stringResource(R.string.extensions_nsfw)
            else -> null
        },
        badgeColor = when {
            extension.isObsolete -> tokens.colors.textMuted
            extension.hasUpdate -> tokens.colors.accent
            else -> tokens.colors.danger
        },
        // Installed extensions have no icon URL — the drawable comes straight
        // from PackageManager. See ExtensionIcon for why the two differ.
        iconUrl = null,
        iconDrawable = extension.icon,
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Only shown when the extension actually exposes preferences, so
                // the button never opens an empty screen.
                onOpenSettings?.let {
                    ActionButton(
                        icon = Icons.Rounded.Settings,
                        description = stringResource(R.string.extensions_settings),
                        onClick = it,
                    )
                }
                ActionButton(
                    icon = Icons.Rounded.Delete,
                    description = stringResource(R.string.extensions_uninstall),
                    onClick = onUninstall,
                )
            }
        },
    )
}

@Composable
private fun AvailableRow(
    extension: Extension.Available,
    step: InstallStep?,
    onInstall: () -> Unit,
) {
    val tokens = MaterialTheme.wb

    ExtensionRow(
        title = extension.name,
        subtitle = buildString {
            append("v${extension.versionName}")
            if (extension.lang.isNotBlank()) append(" · ${extension.lang.uppercase()}")
        },
        badge = stringResource(R.string.extensions_nsfw).takeIf { extension.isNsfw },
        badgeColor = tokens.colors.danger,
        iconUrl = extension.iconUrl,
        trailing = {
            when (step) {
                InstallStep.Pending,
                InstallStep.Downloading,
                InstallStep.Installing,
                -> CircularProgressIndicator(
                    color = tokens.colors.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )

                else -> ActionButton(
                    icon = Icons.Rounded.Download,
                    description = stringResource(R.string.extensions_install),
                    onClick = onInstall,
                )
            }
        },
    )
}

@Composable
private fun ExtensionRow(
    title: String,
    subtitle: String,
    badge: String?,
    badgeColor: androidx.compose.ui.graphics.Color,
    iconUrl: String?,
    iconDrawable: android.graphics.drawable.Drawable? = null,
    trailing: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.wb

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tokens.colors.surfaceCard)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ExtensionIconSlot(
            drawable = iconDrawable,
            iconUrl = iconUrl,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.colors.surface),
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                badge?.let {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(badgeColor.copy(alpha = 0.18f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = badgeColor,
                        )
                    }
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        trailing()
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.wb
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tokens.colors.textSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}
