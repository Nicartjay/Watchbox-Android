package space.nicart.watchbox.ui.components

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
        keyboardActions = KeyboardActions(onSearch = { onSubmit?.invoke() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = tokens.colors.surfaceCard,
            unfocusedContainerColor = tokens.colors.surfaceCard,
            focusedBorderColor = tokens.colors.borderDefault,
            unfocusedBorderColor = tokens.colors.borderSubtle,
            focusedTextColor = tokens.colors.textPrimary,
            unfocusedTextColor = tokens.colors.textPrimary,
            cursorColor = tokens.colors.accent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}
