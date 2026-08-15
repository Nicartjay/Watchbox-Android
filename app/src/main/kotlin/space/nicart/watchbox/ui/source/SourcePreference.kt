package space.nicart.watchbox.ui.source

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.TwoStatePreference
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.preferenceKey

/**
 * A source preference, flattened out of the `androidx.preference` tree into
 * something Compose can render.
 *
 * The extension ABI hands settings over as a [androidx.preference.PreferenceScreen] -
 * a View-based API from a different UI toolkit. Rather than host a
 * `PreferenceFragmentCompat` (which would drag fragment infrastructure into an
 * otherwise fragment-free app, and cannot be themed with the app's tokens), the
 * tree is read once and converted to these values.
 *
 * Writes go back through each [Preference]'s own change listener and
 * `SharedPreferences`, so the extension observes exactly what it would have seen
 * from the native UI.
 */
sealed interface SourcePreference {

    val title: String
    val summary: String?
    val key: String

    data class Switch(
        override val title: String,
        override val summary: String?,
        override val key: String,
        val checked: Boolean,
        val onChange: (Boolean) -> Unit,
    ) : SourcePreference

    data class Select(
        override val title: String,
        override val summary: String?,
        override val key: String,
        val entries: List<String>,
        val selectedIndex: Int,
        val onChange: (Int) -> Unit,
    ) : SourcePreference

    data class MultiSelect(
        override val title: String,
        override val summary: String?,
        override val key: String,
        val entries: List<String>,
        val selected: Set<String>,
        /** Values, not labels — the extension stores value strings. */
        val values: List<String>,
        val onChange: (Set<String>) -> Unit,
    ) : SourcePreference

    data class Text(
        override val title: String,
        override val summary: String?,
        override val key: String,
        val value: String,
        val onChange: (String) -> Unit,
    ) : SourcePreference

    /** Anything with no editable state — rendered as a plain informational row. */
    data class Info(
        override val title: String,
        override val summary: String?,
        override val key: String,
    ) : SourcePreference
}

/**
 * Builds the preference list for one source.
 *
 * Returns an empty list for a source that is not configurable, which the caller
 * uses to avoid offering a settings button at all.
 */
@SuppressLint("RestrictedApi")
fun readSourcePreferences(context: Context, source: AnimeSource): List<SourcePreference> {
    // Each early return is logged. There are four ways to end up with no preferences, they all
    // land the user on a blank screen, and from the outside that is indistinguishable from the
    // settings button not working at all - so "nothing happened" was unanswerable without this.
    if (source !is ConfigurableAnimeSource) {
        Log.i(TAG, "${source.name}: not configurable, no settings to show")
        return emptyList()
    }

    // PreferenceManager's context constructor is marked @RestrictTo, but it is the
    // only way to build a detached screen without a fragment. Aniyomi does the
    // same thing for the same reason.
    val manager = runCatching { PreferenceManager(context) }
        .onFailure { Log.w(TAG, "${source.name}: PreferenceManager failed: ${it.javaClass.simpleName}: ${it.message}") }
        .getOrNull()
        ?: return emptyList()

    // CRITICAL: point the manager at the source's own preference file.
    //
    // PreferenceManager defaults to the app's shared preferences, but extensions
    // read their settings from `source_<id>` via getSourcePreferences(). Without
    // this the UI writes real values to a file the extension never reads, so every
    // setting appears to save and then has no effect whatsoever.
    manager.sharedPreferencesName = source.preferenceKey()
    manager.sharedPreferencesMode = Context.MODE_PRIVATE

    val screen = runCatching { manager.createPreferenceScreen(context) }
        .onFailure { Log.w(TAG, "${source.name}: createPreferenceScreen failed: ${it.javaClass.simpleName}: ${it.message}") }
        .getOrNull()
        ?: return emptyList()

    // An extension's setupPreferenceScreen runs arbitrary code and may throw;
    // a broken settings screen must not take down the extension list.
    runCatching { source.setupPreferenceScreen(screen) }
        .onFailure {
            Log.w(TAG, "${source.name}: setupPreferenceScreen threw: ${it.javaClass.simpleName}: ${it.message}")
            return emptyList()
        }

    val prefs = screen.flatten().mapNotNull { it.toSourcePreference() }

    // The fourth path, and the least obvious: the screen was built without error but nothing in
    // it survived conversion - an unsupported preference type, or a blank label.
    Log.i(TAG, "${source.name}: ${screen.flatten().size} preference(s) built, ${prefs.size} shown")

    return prefs
}

private const val TAG = "WbSourcePrefs"

/** Depth-first flatten; nested groups are inlined rather than shown as sections. */
private fun PreferenceGroup.flatten(): List<Preference> =
    (0 until preferenceCount).flatMap { index ->
        when (val child = getPreference(index)) {
            is PreferenceGroup -> child.flatten()
            else -> listOf(child)
        }
    }

@Suppress("UNCHECKED_CAST")
private fun Preference.toSourcePreference(): SourcePreference? {
    val label = title?.toString().orEmpty().ifBlank { key.orEmpty() }
    if (label.isBlank()) return null

    val prefKey = key ?: return null
    val text = summary?.toString()

    return when (this) {
        is TwoStatePreference -> SourcePreference.Switch(
            title = label,
            summary = text,
            key = prefKey,
            checked = isChecked,
            onChange = { next ->
                // callChangeListener first: an extension may veto the value, and
                // persisting before asking would leave the two out of step.
                if (callChangeListener(next)) isChecked = next
            },
        )

        is MultiSelectListPreference -> SourcePreference.MultiSelect(
            title = label,
            summary = text,
            key = prefKey,
            entries = entries.map { it.toString() },
            values = entryValues.map { it.toString() },
            selected = values ?: emptySet(),
            onChange = { next ->
                if (callChangeListener(next)) values = next
            },
        )

        // Checked before ListPreference's parent types; order matters because
        // ListPreference is itself a DialogPreference.
        is ListPreference -> SourcePreference.Select(
            title = label,
            summary = text,
            key = prefKey,
            entries = entries.map { it.toString() },
            selectedIndex = entryValues.indexOfFirst { it.toString() == value }
                .coerceAtLeast(0),
            onChange = { index ->
                val next = entryValues.getOrNull(index)?.toString() ?: return@Select
                if (callChangeListener(next)) value = next
            },
        )

        is EditTextPreference -> SourcePreference.Text(
            title = label,
            summary = text,
            key = prefKey,
            value = getText() ?: "",
            onChange = { next ->
                if (callChangeListener(next)) setText(next)
            },
        )

        else -> SourcePreference.Info(title = label, summary = text, key = prefKey)
    }
}
