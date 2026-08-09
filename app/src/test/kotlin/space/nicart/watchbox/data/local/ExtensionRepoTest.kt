package space.nicart.watchbox.data.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for repository URL normalisation and naming.
 *
 * Normalisation is what makes duplicate detection work, and it is easy to get
 * wrong in a way that never surfaces: the same repository added as a root and as
 * an index link would produce two entries that fetch identical indexes, so every
 * extension would appear twice with no obvious cause.
 */
class ExtensionRepoTest {

    // ----------------------------------------------------------- normalising

    @Test
    fun `a trailing slash is removed`() {
        assertEquals(
            "https://example.com/repo",
            ExtensionRepo.normaliseUrl("https://example.com/repo/"),
        )
    }

    @Test
    fun `a direct index link collapses to the repository root`() {
        // Users paste whichever URL they were given; both must resolve to one entry.
        assertEquals(
            "https://example.com/repo",
            ExtensionRepo.normaliseUrl("https://example.com/repo/index.min.json"),
        )
        assertEquals(
            "https://example.com/repo",
            ExtensionRepo.normaliseUrl("https://example.com/repo/index.json"),
        )
    }

    @Test
    fun `surrounding whitespace is removed`() {
        assertEquals(
            "https://example.com/repo",
            ExtensionRepo.normaliseUrl("  https://example.com/repo  "),
        )
    }

    @Test
    fun `every spelling of one repository normalises to the same value`() {
        val forms = listOf(
            "https://example.com/repo",
            "https://example.com/repo/",
            "https://example.com/repo/index.min.json",
            " https://example.com/repo/index.json ",
        )
        assertEquals(1, forms.map(ExtensionRepo::normaliseUrl).distinct().size)
    }

    @Test
    fun `an already-normal url is unchanged`() {
        val url = "https://raw.githubusercontent.com/yuzono/anime-repo/repo"
        assertEquals(url, ExtensionRepo.normaliseUrl(url))
    }

    // -------------------------------------------------------- display names

    @Test
    fun `a github repository is named owner slash project`() {
        // The bare host is raw.githubusercontent.com for every GitHub repo, so it
        // distinguishes nothing; owner/project is what the user recognises.
        val repo = ExtensionRepo("https://raw.githubusercontent.com/yuzono/anime-repo/repo")
        assertEquals("yuzono/anime-repo", repo.displayName)
    }

    @Test
    fun `a non-github repository falls back to its host`() {
        val repo = ExtensionRepo("https://repo.example.com/anime/extensions")
        assertEquals("repo.example.com", repo.displayName)
    }

    @Test
    fun `a display name is never blank`() {
        // It labels a row; an empty label would render an invisible entry.
        listOf(
            "https://example.com",
            "https://example.com/",
            "not a url",
            "",
        ).forEach { url ->
            val name = ExtensionRepo(url).displayName
            assertTrue(
                name.isNotBlank() || url.isBlank(),
                "blank display name for '$url'",
            )
        }
    }

    // -------------------------------------------------------------- defaults

    @Test
    fun `the default list has exactly one enabled repository`() {
        assertEquals(1, ExtensionRepo.DEFAULT.size)
        assertTrue(ExtensionRepo.DEFAULT.single().enabled)
    }

    @Test
    fun `a repository is enabled unless stated otherwise`() {
        // Adding a repo the user just typed should take effect immediately.
        assertTrue(ExtensionRepo("https://example.com/repo").enabled)
    }
}
