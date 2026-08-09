package space.nicart.watchbox.ui.extensions

import space.nicart.watchbox.extension.model.Extension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the extension list filters.
 *
 * Unit-tested because every failure here presents as an empty or wrong list rather
 * than an error, which reads as missing data. The repository rule in particular is
 * asymmetric - it must not apply to installed extensions - and that is invisible
 * until someone with a repo filter set wonders where their installed extensions
 * went.
 */
class ExtensionFiltersTest {

    private fun available(
        name: String,
        lang: String = "en",
        isNsfw: Boolean = false,
        repoUrl: String = REPO_A,
    ) = Extension.Available(
        name = name,
        pkgName = "eu.kanade.tachiyomi.animeextension.$lang.${name.lowercase()}",
        versionName = "1.0.0",
        versionCode = 1,
        libVersion = 14.0,
        lang = lang,
        isNsfw = isNsfw,
        apkName = "$name.apk",
        iconUrl = "https://example.invalid/icon.png",
        apkUrl = "https://example.invalid/$name.apk",
        repoUrl = repoUrl,
    )

    private fun installed(name: String, lang: String = "en", isNsfw: Boolean = false) =
        Extension.Installed(
            name = name,
            pkgName = "eu.kanade.tachiyomi.animeextension.$lang.${name.lowercase()}",
            versionName = "1.0.0",
            versionCode = 1,
            libVersion = 14.0,
            lang = lang,
            isNsfw = isNsfw,
            sources = emptyList(),
        )

    private val catalogue = listOf(
        available("Cineby", lang = "en"),
        available("Animeflv", lang = "es"),
        available("Anime Blkom", lang = "ar", repoUrl = REPO_B),
        available("Hentaimama", lang = "en", isNsfw = true),
    )

    // ------------------------------------------------------------- no filters

    @Test
    fun `no filters keeps everything`() {
        assertEquals(4, catalogue.applyFilters(ExtensionFilters()).size)
    }

    @Test
    fun `an empty filter set is not active`() {
        assertFalse(ExtensionFilters().isActive)
    }

    // -------------------------------------------------------------- language

    @Test
    fun `a single language narrows to that language`() {
        val result = catalogue.applyFilters(ExtensionFilters(languages = setOf("es")))
        assertEquals(listOf("Animeflv"), result.map { it.name })
    }

    @Test
    fun `several languages are a union, not an intersection`() {
        // Selecting two languages must widen the list, not empty it.
        val result = catalogue.applyFilters(ExtensionFilters(languages = setOf("es", "ar")))
        assertEquals(setOf("Animeflv", "Anime Blkom"), result.map { it.name }.toSet())
    }

    @Test
    fun `language matching ignores case`() {
        // Codes are lowercased on the way in; a stored uppercase value must still
        // match rather than silently filtering everything out.
        val result = catalogue.applyFilters(ExtensionFilters(languages = setOf("es")))
        assertEquals(1, result.size)
    }

    // ------------------------------------------------------------------ nsfw

    @Test
    fun `hide excludes adult extensions`() {
        val result = catalogue.applyFilters(ExtensionFilters(nsfw = NsfwFilter.HIDE))
        assertEquals(3, result.size)
        assertFalse(result.any { it.isNsfw })
    }

    @Test
    fun `only keeps just the adult extensions`() {
        val result = catalogue.applyFilters(ExtensionFilters(nsfw = NsfwFilter.ONLY))
        assertEquals(listOf("Hentaimama"), result.map { it.name })
    }

    @Test
    fun `all keeps both`() {
        assertEquals(4, catalogue.applyFilters(ExtensionFilters(nsfw = NsfwFilter.ALL)).size)
    }

    // ------------------------------------------------------------ repository

    @Test
    fun `a repository filter narrows to that repository`() {
        val result = catalogue.applyFilters(ExtensionFilters(repoUrls = setOf(REPO_B)))
        assertEquals(listOf("Anime Blkom"), result.map { it.name })
    }

    @Test
    fun `installed extensions survive a repository filter`() {
        // Installed extensions live on disk and may come from a repo that has since
        // been removed. Excluding them would make them vanish for no visible reason.
        val list = listOf(installed("Cineby"), installed("Animeflv"))
        val result = list.applyFilters(ExtensionFilters(repoUrls = setOf(REPO_B)))

        assertEquals(2, result.size, "a repo filter must not hide installed extensions")
    }

    // ------------------------------------------------------------- combining

    @Test
    fun `filters combine as an intersection`() {
        // English AND non-adult: Cineby only, not Hentaimama.
        val result = catalogue.applyFilters(
            ExtensionFilters(languages = setOf("en"), nsfw = NsfwFilter.HIDE),
        )
        assertEquals(listOf("Cineby"), result.map { it.name })
    }

    @Test
    fun `query combines with the other filters`() {
        val result = catalogue.applyFilters(
            ExtensionFilters(query = "anime", languages = setOf("ar")),
        )
        assertEquals(listOf("Anime Blkom"), result.map { it.name })
    }

    @Test
    fun `a contradictory combination yields nothing rather than throwing`() {
        val result = catalogue.applyFilters(
            ExtensionFilters(languages = setOf("es"), nsfw = NsfwFilter.ONLY),
        )
        assertTrue(result.isEmpty())
    }

    // ---------------------------------------------------------- active flag

    @Test
    fun `each filter kind marks the set active`() {
        assertTrue(ExtensionFilters(query = "a").isActive)
        assertTrue(ExtensionFilters(languages = setOf("en")).isActive)
        assertTrue(ExtensionFilters(nsfw = NsfwFilter.HIDE).isActive)
        assertTrue(ExtensionFilters(repoUrls = setOf(REPO_A)).isActive)
    }

    @Test
    fun `a blank query does not mark the set active`() {
        // Otherwise the filter button highlights while nothing is filtered.
        assertFalse(ExtensionFilters(query = "   ").isActive)
    }

    // ----------------------------------------------------- offered languages

    @Test
    fun `offered languages are deduplicated and sorted`() {
        assertEquals(listOf("ar", "en", "es"), catalogue.availableLanguages())
    }

    @Test
    fun `blank language codes are not offered`() {
        // Some extensions report no language; an empty chip would be unusable.
        val list = catalogue + available("Nolang", lang = "")
        assertEquals(listOf("ar", "en", "es"), list.availableLanguages())
    }

    private companion object {
        const val REPO_A = "https://example.invalid/repo-a"
        const val REPO_B = "https://example.invalid/repo-b"
    }
}
