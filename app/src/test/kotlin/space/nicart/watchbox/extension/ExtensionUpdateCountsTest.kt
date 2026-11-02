package space.nicart.watchbox.extension

import space.nicart.watchbox.extension.model.Extension
import kotlin.test.Test
import kotlin.test.assertEquals

class ExtensionUpdateCountsTest {
    private fun installed(name: String, update: Boolean = false) = Extension.Installed(
        name = name,
        pkgName = "example.${name.lowercase()}",
        versionName = "16.1",
        versionCode = 1L,
        libVersion = 16.0,
        lang = "en",
        isNsfw = false,
        sources = emptyList(),
        hasUpdate = update,
    )

    @Test
    fun `no pending updates means no badges`() {
        val counts = listOf(installed("Rentaro"), installed("Arvin-HD")).extensionUpdateCounts()
        assertEquals(ExtensionUpdateCounts(), counts)
    }

    @Test
    fun `normal updates do not badge Short Reels`() {
        val counts = listOf(installed("Rentaro", true), installed("Arvin-HD")).extensionUpdateCounts()
        assertEquals(1, counts.standard)
        assertEquals(0, counts.shortReels)
    }

    @Test
    fun `Arvin updates do not badge Browse`() {
        val counts = listOf(installed("Rentaro"), installed("Arvin-HD", true)).extensionUpdateCounts()
        assertEquals(0, counts.standard)
        assertEquals(1, counts.shortReels)
    }

    @Test
    fun `mixed updates are counted in their own collections`() {
        val counts = listOf(
            installed("Rentaro", true),
            installed("AniDB", true),
            installed("Arvin-HD", true),
            installed("Arvin-XY", true),
            installed("Arvin-RF"),
        ).extensionUpdateCounts()
        assertEquals(2, counts.forCollection(SourceCollection.STANDARD))
        assertEquals(2, counts.forCollection(SourceCollection.SHORT_REELS))
        assertEquals(4, counts.forCollection(SourceCollection.ALL))
    }

    @Test
    fun `finishing a short update only clears the short badge`() {
        val entries = listOf(installed("Rentaro", true), installed("Arvin-HD", true))
        val after = entries.map { if (it.isShortReels) it.copy(hasUpdate = false) else it }
            .extensionUpdateCounts()
        assertEquals(ExtensionUpdateCounts(standard = 1), after)
    }
}
