package space.nicart.watchbox.extension

import space.nicart.watchbox.extension.model.Extension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for merging several repository indexes into one list.
 *
 * Unit-tested because overlap between repositories is both common and silently
 * destructive: without de-duplication a user with two overlapping repos sees every
 * shared extension twice, and which copy installs depends on repository order -
 * so the same tap can install a different build on two devices.
 */
class RepoMergeTest {

    private fun entry(
        name: String,
        pkg: String = name.lowercase(),
        versionCode: Long = 1,
    ) = Extension.Available(
        name = name,
        pkgName = "eu.kanade.tachiyomi.animeextension.en.$pkg",
        versionName = "1.$versionCode.0",
        versionCode = versionCode,
        libVersion = 14.0,
        lang = "en",
        isNsfw = false,
        apkName = "$pkg.apk",
        iconUrl = "https://example.invalid/icon.png",
        apkUrl = "https://example.invalid/$pkg.apk",
    )

    @Test
    fun `entries from several repositories are combined`() {
        val merged = mergeRepoEntries(
            listOf(
                REPO_A to listOf(entry("Cineby")),
                REPO_B to listOf(entry("Zoro")),
            ),
        )
        assertEquals(listOf("Cineby", "Zoro"), merged.map { it.name })
    }

    @Test
    fun `each entry records the repository it came from`() {
        val merged = mergeRepoEntries(
            listOf(
                REPO_A to listOf(entry("Cineby")),
                REPO_B to listOf(entry("Zoro")),
            ),
        )
        assertEquals(REPO_A, merged.first { it.name == "Cineby" }.repoUrl)
        assertEquals(REPO_B, merged.first { it.name == "Zoro" }.repoUrl)
    }

    @Test
    fun `a duplicate package appears exactly once`() {
        val merged = mergeRepoEntries(
            listOf(
                REPO_A to listOf(entry("Cineby", versionCode = 1)),
                REPO_B to listOf(entry("Cineby", versionCode = 1)),
            ),
        )
        assertEquals(1, merged.size, "the same package must not be listed twice")
    }

    @Test
    fun `the highest version wins regardless of repository order`() {
        val aFirst = mergeRepoEntries(
            listOf(
                REPO_A to listOf(entry("Cineby", versionCode = 5)),
                REPO_B to listOf(entry("Cineby", versionCode = 9)),
            ),
        )
        val bFirst = mergeRepoEntries(
            listOf(
                REPO_B to listOf(entry("Cineby", versionCode = 9)),
                REPO_A to listOf(entry("Cineby", versionCode = 5)),
            ),
        )

        // Order-independence is the point: otherwise the same tap installs a
        // different build depending on the order repositories were added.
        assertEquals(9, aFirst.single().versionCode)
        assertEquals(9, bFirst.single().versionCode)
        assertEquals(REPO_B, aFirst.single().repoUrl)
        assertEquals(REPO_B, bFirst.single().repoUrl)
    }

    @Test
    fun `the result is sorted by name, case-insensitively`() {
        val merged = mergeRepoEntries(
            listOf(
                REPO_A to listOf(entry("zoro", pkg = "zoro"), entry("Animeflv", pkg = "flv")),
                REPO_B to listOf(entry("cineby", pkg = "cineby")),
            ),
        )
        assertEquals(listOf("Animeflv", "cineby", "zoro"), merged.map { it.name })
    }

    @Test
    fun `no repositories yields an empty list`() {
        assertTrue(mergeRepoEntries(emptyList()).isEmpty())
    }

    @Test
    fun `a repository that returned nothing does not affect the others`() {
        // This is the shape of a partial failure: one repo contributes nothing.
        val merged = mergeRepoEntries(
            listOf(
                REPO_A to listOf(entry("Cineby")),
                REPO_B to emptyList(),
            ),
        )
        assertEquals(listOf("Cineby"), merged.map { it.name })
    }

    private companion object {
        const val REPO_A = "https://example.invalid/repo-a"
        const val REPO_B = "https://example.invalid/repo-b"
    }
}
