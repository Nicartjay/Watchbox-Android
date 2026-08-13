package space.nicart.watchbox.domain

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the home spotlight's selection.
 *
 * The pool building and the seeding are pinned rather than the visual result. Two properties
 * matter and neither is obvious from reading the call site:
 *
 *  - **Determinism within a seed.** The feed reloads whenever the installed extension set
 *    changes, and the pager is keyed on item count - so a same-length reshuffle does not reset
 *    the carousel, it silently swaps what each page shows while the user is looking at it.
 *  - **Deduplication.** The same title appears in several catalogues routinely, and a carousel
 *    repeating it reads as a bug.
 *
 * `homeFeed` itself needs live extension sources, so the arithmetic is exercised directly here
 * rather than through a stub of the thing being tested.
 */
class HeroSelectionTest {

    private fun card(source: Long, url: String) = AnimeCard(
        sourceId = source,
        sourceName = "Source $source",
        title = url,
        url = url,
        posterUrl = null,
    )

    /** Mirrors the pool step in `homeFeed`. */
    private fun pool(rows: List<List<AnimeCard>>): List<AnimeCard> =
        rows.asSequence()
            .flatMap { it.asSequence() }
            .distinctBy { it.key }
            .toList()

    @Test
    fun `the pool spans every row`() {
        val rows = listOf(
            listOf(card(1, "a"), card(1, "b")),
            listOf(card(2, "c")),
            listOf(card(3, "d"), card(3, "e")),
        )

        val pooled = pool(rows)

        assertEquals(5, pooled.size)
        assertEquals(setOf(1L, 2L, 3L), pooled.map { it.sourceId }.toSet())
    }

    /**
     * A title in two catalogues is one entry. The key is "sourceId::url", so the same title from
     * two different sources is genuinely two cards - dedup only removes exact repeats within a
     * source, which is what a paged catalogue produces.
     */
    @Test
    fun `duplicates within a source are collapsed`() {
        val rows = listOf(
            listOf(card(1, "a"), card(1, "a"), card(1, "b")),
        )

        assertEquals(2, pool(rows).size)
    }

    @Test
    fun `the same title from two sources is kept twice`() {
        val rows = listOf(listOf(card(1, "a")), listOf(card(2, "a")))

        assertEquals(2, pool(rows).size)
    }

    // ------------------------------------------------------------------ seeding

    /**
     * The whole point of seeding. An unseeded shuffle reorders on every feed reload, and the
     * carousel then changes under the user without resetting its position.
     */
    @Test
    fun `the same seed gives the same order`() {
        val pooled = (1..20).map { card(1, "url$it") }

        val first = pooled.shuffled(Random(4242)).map { it.url }
        val second = pooled.shuffled(Random(4242)).map { it.url }

        assertEquals(first, second)
    }

    @Test
    fun `a different seed gives a different order`() {
        val pooled = (1..20).map { card(1, "url$it") }

        val today = pooled.shuffled(Random(100)).map { it.url }
        val tomorrow = pooled.shuffled(Random(101)).map { it.url }

        assertTrue(today != tomorrow, "a new seed should reorder the spotlight")
    }

    /** The seed advances once per day, so the spotlight is stable within a session. */
    @Test
    fun `the seed is stable across a day and changes the next`() {
        val day = 24L * 60 * 60 * 1000
        val morning = 1_760_000_000_000L

        assertEquals(morning / day, (morning + 6 * 60 * 60 * 1000) / day)
        assertTrue(morning / day != (morning + day) / day)
    }

    // ------------------------------------------------------------------ sampling

    @Test
    fun `sampling never exceeds the pool`() {
        val pooled = listOf(card(1, "a"), card(1, "b"))

        assertEquals(2, pooled.shuffled(Random(1)).take(6).size)
    }

    @Test
    fun `an empty pool samples to nothing`() {
        assertTrue(pool(emptyList()).shuffled(Random(1)).take(6).isEmpty())
    }

    /** Sampling must not drop or invent entries. */
    @Test
    fun `the sample is a subset of the pool`() {
        val pooled = (1..30).map { card(1, "url$it") }
        val sample = pooled.shuffled(Random(7)).take(6)

        assertEquals(6, sample.size)
        assertEquals(6, sample.map { it.key }.distinct().size)
        assertTrue(pooled.map { it.key }.containsAll(sample.map { it.key }))
    }
}
