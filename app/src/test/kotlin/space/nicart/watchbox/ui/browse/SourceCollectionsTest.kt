package space.nicart.watchbox.ui.browse

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import space.nicart.watchbox.extension.SourceCollection
import space.nicart.watchbox.extension.contentGroups
import space.nicart.watchbox.extension.model.Extension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceCollectionsTest {
    private fun source(id: Long, name: String) = object : AnimeCatalogueSource {
        override val id = id
        override val name = name
        override val lang = "en"
        override val supportsLatest = true
        override fun getFilterList() = AnimeFilterList()
    }

    private fun extension(name: String, vararg sources: AnimeSource) = Extension.Installed(
        name = name,
        pkgName = "example.${name.lowercase()}",
        versionName = "16.1",
        versionCode = 1L,
        libVersion = 16.0,
        lang = "en",
        isNsfw = false,
        sources = sources.toList(),
    )

    private val installed = listOf(
        extension("Rentaro", source(1, "Rentaro")),
        extension("Arvin-RF", source(2, "Arvin-RF")),
        extension("Arvin-HD", source(3, "Arvin-HD")),
        extension("Zoro", source(4, "Zoro")),
    )

    @Test
    fun `tablet and TV home source pickers exclude every Arvin provider`() {
        assertEquals(
            listOf("Rentaro", "Zoro"),
            installed.sourceEntries(SourceCollection.STANDARD).map { it.name },
        )
    }

    @Test
    fun `Short Reels lists only Arvin providers in display order`() {
        val entries = installed.sourceEntries(SourceCollection.SHORT_REELS)
        assertEquals(listOf("Arvin-HD", "Arvin-RF"), entries.map { it.name })
        assertTrue(entries.all { it.isShortReels })
    }

    @Test
    fun `phone collection retains all providers`() {
        assertEquals(4, installed.sourceEntries(SourceCollection.ALL).size)
    }

    @Test
    fun `phone Browse groups normal providers separately from Shorts Reels`() {
        val entries = installed.sourceEntries(SourceCollection.ALL)
        val groups = contentGroups(entries, SourceCollection.ALL) { it.isShortReels }

        assertEquals(listOf(SourceCollection.STANDARD, SourceCollection.SHORT_REELS), groups.map { it.collection })
        assertEquals(listOf("Rentaro", "Zoro"), groups[0].items.map { it.name })
        assertEquals(listOf("Arvin-HD", "Arvin-RF"), groups[1].items.map { it.name })
    }

    @Test
    fun `a scoped tablet list cannot gain another category through grouping`() {
        val entries = installed.sourceEntries(SourceCollection.ALL)
        val groups = contentGroups(entries, SourceCollection.SHORT_REELS) { it.isShortReels }
        assertEquals(1, groups.size)
        assertTrue(groups.single().items.all { it.isShortReels })
        assertTrue(contentGroups(emptyList<SourceEntry>(), SourceCollection.ALL) { it.isShortReels }.isEmpty())
    }

    @Test
    fun `an unknown suffix appears without an app-side allowlist`() {
        val withNew = installed + extension("Arvin-XY", source(5, "Arvin-XY"))
        assertEquals(3, withNew.sourceEntries(SourceCollection.SHORT_REELS).size)
        assertEquals(2, withNew.sourceEntries(SourceCollection.STANDARD).size)
    }

    @Test
    fun `factory sources are kept in the owning Arvin collection`() {
        val factory = listOf(extension("Arvin-XY", source(1, "First"), source(2, "Second")))
        assertEquals(2, factory.sourceEntries(SourceCollection.SHORT_REELS).size)
        assertTrue(factory.sourceEntries(SourceCollection.STANDARD).isEmpty())
    }

    @Test
    fun `a source name can identify the family even if its extension is generic`() {
        val bundle = listOf(extension("Mixed", source(1, "Arvin-XY"), source(2, "Regular")))
        assertEquals(listOf(1L), bundle.sourceEntries(SourceCollection.SHORT_REELS).map { it.id })
        assertEquals(listOf(2L), bundle.sourceEntries(SourceCollection.STANDARD).map { it.id })
    }

    @Test
    fun `installing only Arvin sources leaves the standard collection honestly empty`() {
        val shorts = installed.filter { it.name.startsWith("Arvin-") }
        assertTrue(shorts.sourceEntries(SourceCollection.STANDARD).isEmpty())
        assertEquals(2, shorts.sourceEntries(SourceCollection.SHORT_REELS).size)
    }

    @Test
    fun `non-catalogue sources are not offered as browse targets`() {
        val playbackOnly = object : AnimeSource {
            override val id = 8L
            override val name = "Arvin-Playback"
        }
        assertTrue(listOf(extension("Arvin-Playback", playbackOnly)).sourceEntries().isEmpty())
    }

    @Test
    fun `metadata and identity survive classification`() {
        val entry = installed.sourceEntries().first { it.id == 1L }
        assertEquals("en", entry.lang)
        assertTrue(entry.supportsLatest)
        assertFalse(entry.isShortReels)
    }
}
