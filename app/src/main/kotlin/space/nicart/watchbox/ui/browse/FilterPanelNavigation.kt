package space.nicart.watchbox.ui.browse

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType

/** Opt-in remote navigation: move focus, never activate Apply or consume unrelated keys. */
internal fun handleFilterApplyShortcut(
    enabled: Boolean,
    key: Key,
    type: KeyEventType,
    focusApply: () -> Boolean,
): Boolean {
    if (!enabled || key != Key.DirectionRight || type != KeyEventType.KeyDown) return false
    return focusApply()
}
