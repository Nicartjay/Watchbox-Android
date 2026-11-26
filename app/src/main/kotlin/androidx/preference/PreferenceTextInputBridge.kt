package androidx.preference

import android.widget.EditText

/**
 * AndroidX exposes the binding setter publicly but keeps its getter package-private.
 * Bind a temporary editor so the Compose adapter honours the extension's input type
 * (especially passwords) without guessing from a preference's title or key.
 */
internal fun EditTextPreference.readBoundInputType(): Int {
    val editor = EditText(context)
    getOnBindEditTextListener()?.onBindEditText(editor)
    return editor.inputType
}
