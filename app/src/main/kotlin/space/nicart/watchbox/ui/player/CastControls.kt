package space.nicart.watchbox.ui.player

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
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
import space.nicart.watchbox.cast.CastDevice
import space.nicart.watchbox.cast.CastProtocol
import space.nicart.watchbox.cast.CastState
import space.nicart.watchbox.core.ui.adaptiveFocus
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.core.ui.wbType

/**
 * Cast controls for the player.
 *
 * The panel reuses the right-edge drawer shape the quality and episode pickers
 * already use, so casting does not introduce a third presentation style.
 *
 * Chromecast and DLNA are listed differently on purpose. The Cast SDK owns
 * Chromecast discovery and presents its own system picker; duplicating that here
 * would mean two lists that can disagree about what is connected. So DLNA
 * renderers are listed directly, and Chromecast appears only once the SDK already
 * has a session.
 */
@Composable
fun CastButton(
    isCasting: Boolean,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Box(
        modifier = Modifier
            .size(size + 16.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .adaptiveFocus(interaction, androidx.compose.foundation.shape.CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isCasting) Icons.Filled.CastConnected else Icons.Filled.Cast,
            contentDescription = stringResource(R.string.cast_button),
            // Tinted while connected so the state is readable at a glance.
            tint = if (isCasting) tokens.colors.accent else Color.White,
            modifier = Modifier.size(size),
        )
    }
}

@Composable
fun CastPanel(
    state: CastState,
    visible: Boolean,
    onSelectDevice: (CastDevice) -> Unit,
    onCastToChromecast: () -> Unit,
    onSendToExternal: () -> Unit,
    onStopCasting: () -> Unit,
    onRescan: () -> Unit,
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

    AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.slideInHorizontally(tween(250)) { it },
        exit = androidx.compose.animation.slideOutHorizontally(tween(200)) { it },
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                modifier = Modifier
                    .width(420.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .background(tokens.colors.surfaceElevated)
                    .padding(24.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.cast_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(tokens.colors.surfaceCard)
                            .clickable(onClick = onRescan),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.isDiscovering) {
                            CircularProgressIndicator(
                                color = tokens.colors.accent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.cast_rescan),
                                tint = tokens.colors.textSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                state.errorMessage?.let { message ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(tokens.colors.danger.copy(alpha = 0.12f))
                            .padding(12.dp),
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.danger,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                if (state.isCasting) {
                    CastRow(
                        label = stringResource(
                            R.string.cast_playing_on,
                            state.deviceName.orEmpty(),
                        ),
                        selected = true,
                        onClick = {},
                    )
                    Spacer(Modifier.height(8.dp))
                    CastRow(
                        label = stringResource(R.string.cast_stop),
                        selected = false,
                        onClick = onStopCasting,
                    )
                    return@Column
                }

                // Only offered when the SDK already holds a session; the system
                // picker is what establishes it.
                if (state.canCastToChromecast) {
                    CastRow(
                        label = stringResource(R.string.cast_load_to_connected),
                        selected = false,
                        onClick = onCastToChromecast,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // Grouped by protocol so a long list stays readable, and so it is
                // obvious which devices are Chromecasts - they behave differently,
                // notably being the only ones that reliably play HLS.
                Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    if (state.devices.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val chromecasts = state.devices
                                .filter { it.protocol == CastProtocol.CHROMECAST }
                            val renderers = state.devices
                                .filter { it.protocol == CastProtocol.DLNA }

                            if (chromecasts.isNotEmpty()) {
                                item(key = "cc-label") {
                                    GroupLabel(stringResource(R.string.cast_group_chromecast))
                                }
                                items(items = chromecasts, key = { it.id }) { device ->
                                    CastRow(
                                        label = device.name,
                                        selected = false,
                                        onClick = { onSelectDevice(device) },
                                    )
                                }
                            }

                            if (renderers.isNotEmpty()) {
                                item(key = "dlna-label") {
                                    GroupLabel(stringResource(R.string.cast_group_dlna))
                                }
                                items(items = renderers, key = { it.id }) { device ->
                                    CastRow(
                                        label = device.name,
                                        selected = false,
                                        onClick = { onSelectDevice(device) },
                                    )
                                }
                            }
                        }
                    } else if (state.isDiscovering) {
                        Text(
                            text = stringResource(R.string.cast_searching),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textMuted,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.cast_none_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textMuted,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.cast_none_found_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.colors.textMuted,
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Always offered, found devices or not: other apps support
                    // receivers this one does not, and that is most valuable exactly
                    // when nothing was found here.
                    GroupLabel(stringResource(R.string.cast_group_other))
                    Spacer(Modifier.height(6.dp))
                    CastRow(
                        label = stringResource(R.string.cast_send_external),
                        selected = false,
                        onClick = onSendToExternal,
                    )
                }
            }
        }
    }
}

@Composable
private fun CastRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.wb

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) tokens.colors.accent else tokens.colors.surfaceCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Tv,
            contentDescription = null,
            tint = if (selected) tokens.colors.onAccent else tokens.colors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) tokens.colors.onAccent else tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * True when a Chromecast session exists but our media has not been sent to it.
 *
 * Derived rather than stored so it cannot drift out of step with the SDK, which
 * can start a session from the system UI without us being involved.
 */
private val CastState.canCastToChromecast: Boolean
    get() = !isCasting && deviceName != null

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.wb.colors.textMuted,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}
