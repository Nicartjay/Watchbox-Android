package space.nicart.watchbox.ui.source

import android.text.InputType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType

/** A UI identity, not a persistence key: keyless account actions must not be dropped or saved. */
internal fun sourcePreferenceUiKey(key: String?, index: Int): String =
    key?.takeIf { it.isNotBlank() } ?: "__watchbox_unkeyed_$index"

/** Preserve the extension's Android input semantics in the Compose editor. */
internal fun sourcePreferenceKeyboardType(inputType: Int): KeyboardType {
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER -> when {
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD -> KeyboardType.NumberPassword
            inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0 -> KeyboardType.Decimal
            else -> KeyboardType.Number
        }
        InputType.TYPE_CLASS_PHONE -> KeyboardType.Phone
        InputType.TYPE_CLASS_TEXT -> when (variation) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> KeyboardType.Password
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> KeyboardType.Email
            InputType.TYPE_TEXT_VARIATION_URI -> KeyboardType.Uri
            else -> KeyboardType.Text
        }
        else -> KeyboardType.Text
    }
}

/**
 * Draft text stays local until the user presses Enter/Done. In particular, merely
 * typing or clearing an OTP must never call the extension's sign-in listener.
 * Deliberately not saveable: transient credentials must not enter saved UI state.
 */
internal class SourcePreferenceTextEditor(
    initialValue: String,
    private val onSubmit: (String) -> String,
) {
    var value by mutableStateOf(initialValue)
        private set

    fun edit(next: String) {
        value = next
    }

    fun submit() {
        value = onSubmit(value)
    }
}
