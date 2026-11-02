package space.nicart.watchbox.ui.home

import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.domain.AnimeRow
import space.nicart.watchbox.ui.browse.SourceEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhoneHomeRowsTest {
    @Test
    fun `phone Home groups Arvin titles under Shorts Reels and retains normal rows`() {
        val rows = listOf(row(1L, "Rentaro"), row(2L, "Arvin-RF"), row(3L, "Arvin-HD"))
        val result = phoneHomeRows(rows, emptyList())

        assertEquals(listOf("Rentaro"), result.standard.map { it.sourceName })
        assertEquals(listOf("Arvin-RF", "Arvin-HD"), result.shortReels.map { it.sourceName })
    }

    @Test
    fun `an Arvin factory source is grouped by source identity even with a different name`() {
        val source = SourceEntry(2L, "Drama provider", "en", supportsLatest = true, isShortReels = true)
        val result = phoneHomeRows(listOf(row(2L, source.name)), listOf(source))

        assertTrue(result.standard.isEmpty())
        assertEquals(1, result.shortReels.size)
    }

    @Test
    fun `short rows cannot disappear while source metadata is first loading`() {
        val result = phoneHomeRows(listOf(row(2L, "Arvin-XY")), emptyList())
        assertEquals(1, result.shortReels.size)
        assertTrue(result.standard.isEmpty())
    }

    @Test
    fun `a phone without Arvin keeps its normal Home content intact`() {
        val rows = listOf(row(1L, "Rentaro"))
        val result = phoneHomeRows(rows, emptyList())

        assertEquals(rows, result.standard)
        assertTrue(result.shortReels.isEmpty())
    }

    @Test
    fun `shorts previews are deduplicated and bounded while provider browsing stays available`() {
        val duplicate = card(2L, "Arvin-RF", "duplicate")
        val rows = listOf(
            AnimeRow(2L, "Arvin-RF", "Latest", List(30) { card(2L, "Arvin-RF", "$it") } + duplicate),
            AnimeRow(2L, "Arvin-RF", "Popular", listOf(duplicate, duplicate)),
        )
        val result = phoneHomeRows(rows, emptyList())
        assertEquals(24, result.shortReels.size)
        assertEquals(result.shortReels.size, result.shortReels.map { it.key }.distinct().size)
        val repeated = phoneHomeRows(
            listOf(AnimeRow(2L, "Arvin-RF", "Latest", listOf(duplicate, duplicate))),
            emptyList(),
        )
        assertEquals(1, repeated.shortReels.size)
    }

    @Test
    fun `the same title on different providers retains each playable source identity`() {
        val rows = listOf(row(2L, "Arvin-RF"), row(3L, "Arvin-HD"))
        val result = phoneHomeRows(rows, emptyList())
        assertEquals(listOf(2L, 3L), result.shortReels.map { it.sourceId })
    }

    @Test
    fun `short titles alternate across every provider instead of filling the shelf from the first`() {
        val rows = listOf(
            manyTitles(2L, "Arvin-HD", 40),
            manyTitles(3L, "Arvin-RF", 40),
            manyTitles(4L, "Arvin-ND", 40),
        )
        val titles = phoneHomeRows(rows, emptyList()).shortReels

        assertEquals(List(8) { listOf(2L, 3L, 4L) }.flatten(), titles.map { it.sourceId })
        assertEquals(24, titles.size)
        assertEquals(24, titles.map { it.key }.distinct().size)
    }

    @Test
    fun `exhausted providers are skipped while remaining titles preserve their provider order`() {
        val rows = listOf(
            manyTitles(2L, "Arvin-HD", 4),
            manyTitles(3L, "Arvin-RF", 1),
            manyTitles(4L, "Arvin-ND", 2),
        )
        val titles = phoneHomeRows(rows, emptyList()).shortReels

        assertEquals(listOf(2L, 3L, 4L, 2L, 4L, 2L, 2L), titles.map { it.sourceId })
        assertEquals(listOf("0", "0", "0", "1", "1", "2", "3"), titles.map { it.url })
    }

    @Test
    fun `duplicate rows do not give one provider extra turns or repeat titles`() {
        val first = manyTitles(2L, "Arvin-HD", 3)
        val rows = listOf(
            first,
            first.copy(title = "Popular", items = first.items.reversed()),
            manyTitles(3L, "Arvin-RF", 3),
        )
        val titles = phoneHomeRows(rows, emptyList()).shortReels

        assertEquals(listOf(2L, 3L, 2L, 3L, 2L, 3L), titles.map { it.sourceId })
        assertEquals(6, titles.map { it.key }.distinct().size)
    }

    @Test
    fun `empty providers do not block populated providers or create an endless round`() {
        val empty = manyTitles(2L, "Arvin-HD", 0)
        assertTrue(phoneHomeRows(listOf(empty), emptyList()).shortReels.isEmpty())
        assertEquals(
            listOf(3L, 3L),
            phoneHomeRows(listOf(empty, manyTitles(3L, "Arvin-RF", 2)), emptyList())
                .shortReels.map { it.sourceId },
        )
    }

    private fun manyTitles(id: Long, name: String, count: Int) = AnimeRow(
        sourceId = id,
        sourceName = name,
        title = "Latest on $name",
        items = List(count) { card(id, name, "$it") },
    )

    private fun row(id: Long, sourceName: String) = AnimeRow(
        sourceId = id,
        sourceName = sourceName,
        title = "Latest on $sourceName",
        items = listOf(card(id, sourceName, "title")),
    )

    private fun card(id: Long, sourceName: String, url: String) = AnimeCard(
        sourceId = id,
        sourceName = sourceName,
        url = url,
        title = "Short drama",
        posterUrl = null,
    )
}
