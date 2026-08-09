package space.nicart.watchbox.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import space.nicart.watchbox.core.ui.LocalLayoutMetrics
import space.nicart.watchbox.core.ui.adaptiveFocus
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import space.nicart.watchbox.core.ui.wb

/**
 * Rounded search input.
 *
 * Extracted because the same field is now needed on the search page, the
 * extension list and the per-source browse screen. Duplicating the colour recipe
 * a third time would let the three drift apart, which is exactly the kind of
 * inconsistency that looks like a bug rather than a style choice.
 *
 * [onSubmit] is optional: filter-as-you-type callers (the extension list) have
 * nothing to submit, while network-backed callers need an explicit search action
 * on the keyboard.
 */
@Composable
fun WbSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
    /** Set false when reused as a plain text field rather than a search box. */
    showSearchIcon: Boolean = true,
) {
    val tokens = MaterialTheme.wb
    val metrics = LocalLayoutMetrics.current

    /**
     * True once the user has asked to type.
     *
     * On a television the field must not be editable until then. A focused TextField
     * raises the IME, and the IME consumes every D-pad press - the remote then appears to
     * move only keyboard keys, and nothing else on the screen can be reached. Since this
     * field is usually the first focusable item on its screen, the initial focus claim
     * would otherwise open the keyboard on entry, every time.
     */
    var editing by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    if (metrics.isFocusDriven && !editing) {
        WbSearchFieldButton(
            value = value,
            placeholder = placeholder,
            showSearchIcon = showSearchIcon,
            onClick = { editing = true },
            modifier = modifier,
        )
        return
    }

    // Requested only after the user opened the field, so the keyboard never appears
    // unbidden.
    LaunchedEffect(editing) {
        if (editing) runCatching { focusRequester.requestFocus() }
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(text = placeholder, color = tokens.colors.textMuted)
        },
        leadingIcon = if (showSearchIcon) {
            {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = tokens.colors.textMuted,
                )
            }
        } else {
            null
        },
        trailingIcon = {
            // Only present when there is something to clear, so the field does
            // not carry a dead control.
            if (value.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Clear",
                    tint = tokens.colors.textMuted,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onValueChange("") },
                )
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(
            imeAction = if (onSubmit != null) ImeAction.Search else ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onSearch = {
                onSubmit?.invoke()
                // Closed on submit so the results are reachable without first having to
                // escape the field.
                editing = false
                focusManager.clearFocus()
            },
            onDone = {
                onSubmit?.invoke()
                editing = false
                focusManager.clearFocus()
            },
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = tokens.colors.surfaceCard,
            unfocusedContainerColor = tokens.colors.surfaceCard,
            focusedBorderColor = tokens.colors.borderDefault,
            unfocusedBorderColor = tokens.colors.borderSubtle,
            focusedTextColor = tokens.colors.textPrimary,
            unfocusedTextColor = tokens.colors.textPrimary,
            cursorColor = tokens.colors.accent,
        ),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                // KeyDown, not KeyUp: a TextField consumes directional keys on KeyDown,
                // so a KeyUp-gated handler never runs.
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                when (event.key) {
                    // Both give the field back, so focus is never trapped in it.
                    Key.Back, Key.Escape, Key.DirectionUp -> {
                        editing = false
                        focusManager.clearFocus()
                        // Back is not consumed: swallowing it would stop the next press
                        // leaving the screen.
                        event.key != Key.Back
                    }

                    else -> false
                }
            },
    )
}

/**
 * The closed state of a search field on a focus-driven device.
 *
 * A plain focusable row that looks like the field it replaces, so the screen reads the
 * same whether or not the keyboard is open.
 */
@Composable
private fun WbSearchFieldButton(
    value: String,
    placeholder: String,
    showSearchIcon: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.wb
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surfaceCard)
            .adaptiveFocus(interaction, RoundedCornerShape(12.dp), scale = false)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showSearchIcon) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = tokens.colors.textMuted,
            )
        }
        Text(
            text = value.ifBlank { placeholder },
            style = MaterialTheme.typography.titleMedium,
            color = if (value.isBlank()) tokens.colors.textMuted else tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
