package space.nicart.watchbox.ui.update

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.LocalLayoutMetrics
import space.nicart.watchbox.core.ui.adaptiveFocus
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.wb
import androidx.compose.ui.res.stringResource

/**
 * The launch update prompt.
 *
 * A dialog rather than a banner, because it asks a question with two answers and needs one
 * of them. It only appears when an update genuinely exists and has not been skipped, so it
 * is never an interruption without content.
 *
 * Dismissable by tapping outside on touch, which leaves the version un-skipped so it can be
 * offered again tomorrow. Skip is the deliberate "not this one" and is remembered.
 */
@Composable
fun UpdatePromptDialog(
    state: UpdatePromptState,
    onDownload: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state is UpdatePromptState.Hidden) return

    val tokens = MaterialTheme.wb
    val isFocusDriven = LocalLayoutMetrics.current.isFocusDriven

    // A download in progress must not be dismissed out from under itself.
    val dismissable = state is UpdatePromptState.Available || state is UpdatePromptState.Failed

    Dialog(onDismissRequest = { if (dismissable) onDismiss() }) {
        Column(
            modifier = Modifier
                .widthIn(max = DIALOG_MAX_WIDTH)
                .clip(RoundedCornerShape(20.dp))
                .background(tokens.colors.surfaceDialog)
                .padding(20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    tint = tokens.colors.accent,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = when (state) {
                        is UpdatePromptState.Available ->
                            stringResource(R.string.update_available, state.update.versionName)

                        is UpdatePromptState.Downloading ->
                            stringResource(R.string.update_downloading, state.percent)

                        UpdatePromptState.Launching -> stringResource(R.string.update_launching)
                        is UpdatePromptState.Failed ->
                            stringResource(R.string.update_failed, state.message)

                        UpdatePromptState.Hidden -> ""
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                )
            }

            when (state) {
                is UpdatePromptState.Available -> {
                    state.update.releaseNotes.takeIf { it.isNotBlank() }?.let { notes ->
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.update_notes),
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.colors.textSecondary,
                        )
                        Spacer(Modifier.height(4.dp))
                        // Scrollable and capped: release notes can run to many
                        // paragraphs, and a dialog that grows past the screen has no
                        // reachable buttons.
                        Text(
                            text = notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textMuted,
                            modifier = Modifier
                                .heightIn(max = NOTES_MAX_HEIGHT)
                                .verticalScroll(rememberScrollState()),
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    // Update is focused on entry, so a remote press acts on the primary
                    // choice rather than landing wherever focus happened to be.
                    val updateFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) {
                        if (!isFocusDriven) return@LaunchedEffect
                        repeat(PROMPT_FOCUS_ATTEMPTS) {
                            withFrameNanos { }
                            runCatching { updateFocus.requestFocus() }
                            delay(PROMPT_FOCUS_RETRY_MS)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PromptButton(
                            label = stringResource(R.string.update_skip),
                            primary = false,
                            onClick = onSkip,
                            modifier = Modifier.weight(1f),
                        )
                        PromptButton(
                            label = stringResource(R.string.update_download),
                            primary = true,
                            onClick = onDownload,
                            focusRequester = updateFocus,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is UpdatePromptState.Downloading -> {
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator(
                        color = tokens.colors.accent,
                        modifier = Modifier.size(28.dp),
                    )
                }

                UpdatePromptState.Launching -> Unit

                is UpdatePromptState.Failed -> {
                    Spacer(Modifier.height(16.dp))
                    PromptButton(
                        label = stringResource(R.string.extensions_failed_dismiss),
                        primary = true,
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                UpdatePromptState.Hidden -> Unit
            }
        }
    }
}

@Composable
private fun PromptButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Box(
        modifier = modifier
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .adaptiveFocus(interaction, RoundedCornerShape(40.dp), scale = false)
            .clip(RoundedCornerShape(40.dp))
            .background(if (primary) tokens.colors.accent else tokens.colors.surfaceCard)
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
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) tokens.colors.onAccent else tokens.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val DIALOG_MAX_WIDTH = 420.dp

/** Keeps long release notes from pushing the buttons off-screen. */
private val NOTES_MAX_HEIGHT = 200.dp

/** Matches the focus retry used elsewhere; requestFocus succeeds before a node exists. */
private const val PROMPT_FOCUS_ATTEMPTS = 12
private const val PROMPT_FOCUS_RETRY_MS = 60L
