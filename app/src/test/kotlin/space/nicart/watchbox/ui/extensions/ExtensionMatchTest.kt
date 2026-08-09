package space.nicart.watchbox.ui.extensions

import space.nicart.watchbox.extension.model.Extension
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the extension list filter.
 *
 * Unit-tested because the failure mode is a screen that looks empty rather than an
 * error: if matching is too strict the list silently hides extensions the user has
 * installed, which reads as data loss. The language and package-name cases exist
 * specifically to be searchable and would otherwise be easy to drop in a refactor.
 */
class ExtensionMatchTest {

    private fun available(
        name: String,
        lang: String = "en",
        pkgName: String = "eu.kanade.tachiyomi.animeextension.en.test",
    ) = Extension.Available(
        name = name,
        pkgName = pkgName,
        versionName = "1.0.0",
        versionCode = 1,
        libVersion = 14.0,
        lang = lang,
        isNsfw = false,
        apkName = "test.apk",
        iconUrl = "https://example.invalid/icon.png",
        apkUrl = "https://example.invalid/test.apk",
    )

    @Test
    fun `a blank query matches everything`() {
        // The unfiltered list has to stay the default, or the screen starts empty.
        assertTrue(available("Cineby").matches(""))
        assertTrue(available("Cineby").matches("   "))
    }

    @Test
    fun `matching on name is case insensitive`() {
        val extension = available("Cineby")
        assertTrue(extension.matches("cine"))
        assertTrue(extension.matches("CINEBY"))
        assertTrue(extension.matches("neb"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        // Trailing spaces are common from keyboard autocomplete.
        assertTrue(available("Cineby").matches("  cine "))
    }

    @Test
    fun `language codes are searchable`() {
        // So "es" finds the Spanish extensions.
        val spanish = available("Animeflv", lang = "es")
        assertTrue(spanish.matches("es"))
        assertFalse(spanish.matches("de"))
    }

    @Test
    fun `the package name's last segment is searchable`() {
        val extension = available("Kickassanime", pkgName = "eu.kanade.tachiyomi.animeextension.en.kickassanime")
        assertTrue(extension.matches("kickass"))
    }

    @Test
    fun `the shared package prefix is not searchable`() {
        // Every extension is "eu.kanade.tachiyomi.animeextension.*", so matching the
        // whole package name would make short queries hit everything: "de" via
        // "kanADE" and "en" via "extENsion". That silently breaks language search.
        val extension = available("Cineby", lang = "en", pkgName = "eu.kanade.tachiyomi.animeextension.en.cineby")

        assertFalse(extension.matches("kanade"), "the vendor prefix must not match")
        assertFalse(extension.matches("tachiyomi"), "the vendor prefix must not match")
        assertFalse(extension.matches("animeextension"), "the shared suffix must not match")
    }

    @Test
    fun `a non-matching query excludes the extension`() {
        assertFalse(available("Cineby", lang = "en").matches("zoro"))
    }

    @Test
    fun `installed extensions use the same rule`() {
        val installed = Extension.Installed(
            name = "Cineby",
            pkgName = "eu.kanade.tachiyomi.animeextension.en.cineby",
            versionName = "1.0.0",
            versionCode = 1,
            libVersion = 14.0,
            lang = "en",
            isNsfw = false,
            sources = emptyList(),
        )

        assertTrue(installed.matches("cine"))
        assertFalse(installed.matches("nonsense"))
    }
}
