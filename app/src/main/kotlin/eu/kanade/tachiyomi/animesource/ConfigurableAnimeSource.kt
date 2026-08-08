package eu.kanade.tachiyomi.animesource

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * A source with user-editable settings.
 *
 * Extensions call [getSourcePreferences] from their own constructors, so the
 * default implementation must work before the host has touched the instance.
 * It resolves an [android.app.Application] out of the Injekt graph, which is
 * why the host has to register one during startup.
 *
 * [setupPreferenceScreen] takes an `androidx.preference.PreferenceScreen`, so
 * `androidx.preference` is an ABI dependency even though nothing else uses it.
 *
 * See the note in [eu.kanade.tachiyomi.animesource.model.SAnime] for why this
 * package reproduces the Aniyomi ABI.
 */
interface ConfigurableAnimeSource : AnimeSource {

    fun getSourcePreferences(): SharedPreferences =
        Injekt.get<android.app.Application>()
            .getSharedPreferences(preferenceKey(), Context.MODE_PRIVATE)

    fun setupPreferenceScreen(screen: PreferenceScreen)
}

/** Per-source preference file name. Stable across versions. */
fun AnimeSource.preferenceKey(): String = "source_$id"

/** Preference file name for a source that has not been instantiated. */
fun sourcePreferencesKey(id: Long): String = "source_$id"
