package space.nicart.watchbox.domain

import space.nicart.watchbox.extension.isShortReelsProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeSpotlightTest {
    private val isShortReels: (Long, String) -> Boolean = { id, name ->
        id == 42L || isShortReelsProvider(name)
    }

    @Test
    fun `home hero candidates exclude every Arvin provider`() {
        val rows = listOf(
            row(1L, "Rentaro", 20),
            row(2L, "Arvin-RF", 20),
            row(3L, "Arvin-HD", 20),
            row(4L, "Arvin-XY", 20),
        )

        val pool = homeSpotlightCandidates(rows, isShortReels)
        assertEquals(20, pool.size)
        assertTrue(pool.all { it.sourceName == "Rentaro" })
    }

    @Test
    fun `an Arvin factory with another source name cannot enter the hero`() {
        val rows = listOf(row(42L, "Drama provider", 10), row(1L, "Rentaro", 10))
        assertEquals(
            setOf(1L),
            homeSpotlightCandidates(rows, isShortReels).map { it.sourceId }.toSet(),
        )
    }

    @Test
    fun `only shorts means an empty hero while the shorts rows remain available`() {
        val rows = listOf(row(2L, "Arvin-RF", 20), row(3L, "Arvin-HD", 10))
        assertTrue(homeSpotlightCandidates(rows, isShortReels).isEmpty())
        assertEquals(30, rows.sumOf { it.items.size })
    }

    @Test
    fun `filtering shorts does not consume normal hero or Featured slots`() {
        val normal = row(1L, "Rentaro", 20)
        val rows = listOf(row(2L, "Arvin-RF", 200), normal)
        val pool = homeSpotlightCandidates(rows, isShortReels)

        assertEquals(normal.items, pool)
        assertEquals(14, pool.take(14).size)
    }

    @Test
    fun `normal titles are still deduplicated by source and URL`() {
        val normal = row(1L, "Rentaro", 2)
        val otherSource = row(5L, "Cineby", 2)
        val pool = homeSpotlightCandidates(listOf(normal, normal, otherSource), isShortReels)

        assertEquals(4, pool.size)
        assertEquals(4, pool.map { it.key }.distinct().size)
    }

    private fun row(id: Long, name: String, count: Int) = AnimeRow(
        sourceId = id,
        sourceName = name,
        title = "Latest on $name",
        items = List(count) { index ->
            AnimeCard(
                sourceId = id,
                sourceName = name,
                url = "/$index",
                title = "Title $index",
                posterUrl = null,
            )
        },
    )
}
