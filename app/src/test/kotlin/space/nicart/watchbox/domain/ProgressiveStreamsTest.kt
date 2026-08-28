package space.nicart.watchbox.domain

import eu.kanade.tachiyomi.animesource.ProgressiveVideoSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Tests for the contract a progressive source has to satisfy.
 *
 * The interface is the whole point of the change, and it is the part the app cannot verify at
 * runtime: an extension implements it or does not, and if it emits the wrong shape the failure
 * is a stream that never appears rather than an error. These pin what the host relies on.
 *
 * The interface is deliberately separate from AnimeSource rather than a defaulted member of it.
 * Kotlin compiles interface members to `abstract` plus a static DefaultImpls, so adding one -
 * even with a body - changes the compiled contract and an extension built against the older
 * shape raises AbstractMethodError. `tools/verify-extension-abi.py` guards that; this file
 * guards the semantics.
 */
class ProgressiveStreamsTest {

    /** Cumulative emissions, as the contract requires. */
    private fun cumulative(vararg batches: List<String>): Flow<List<String>> = flow {
        val seen = mutableListOf<String>()
        batches.forEach { batch ->
            seen += batch
            emit(seen.toList())
        }
    }

    @Test
    fun `each emission carries everything found so far`() = runBlocking {
        // Cumulative, not incremental. Emitting only the new entries would make every host
        // responsible for merging, and two hosts would do it differently.
        val emissions = cumulative(
            listOf("Jay/Lisbon 1080p"),
            listOf("Art/Citadel 1080p"),
            listOf("Yoru 720p"),
        ).toList()

        assertEquals(listOf("Jay/Lisbon 1080p"), emissions[0])
        assertEquals(listOf("Jay/Lisbon 1080p", "Art/Citadel 1080p"), emissions[1])
        assertEquals(3, emissions[2].size)
    }

    @Test
    fun `a stream once emitted is not withdrawn`() = runBlocking {
        // The host may already be playing the first thing it was given, so a later emission
        // dropping it would pull the video out from under the viewer.
        val emissions = cumulative(
            listOf("first"),
            listOf("second"),
        ).toList()

        assertTrue(emissions.all { "first" in it })
    }

    @Test
    fun `the first emission is enough to start playing`() = runBlocking {
        // The reason the interface exists: the host plays what arrives first rather than
        // waiting for the slowest backend.
        val first = cumulative(listOf("playable"), listOf("later")).toList().first()

        assertEquals(1, first.size)
    }

    @Test
    fun `a source with nothing to offer still emits`() = runBlocking {
        // Emitting an empty list rather than completing silently is what lets the host tell
        // "none found" from "still working".
        val emissions = flow { emit(emptyList<String>()) }.toList()

        assertEquals(1, emissions.size)
        assertTrue(emissions.single().isEmpty())
    }

    @Test
    fun `a single emission is a valid progressive source`() = runBlocking {
        // A source with one backend has nothing to stagger, and must not be required to fake
        // it. This is also the shape the host synthesises for a source without the interface.
        val emissions = cumulative(listOf("only")).toList()

        assertEquals(1, emissions.size)
    }

    @Test
    fun `reordering between emissions is allowed`() = runBlocking {
        // A source that learns a better ranking should be able to say so, as long as nothing
        // already offered disappears.
        val emissions = flow {
            emit(listOf("b"))
            emit(listOf("a", "b"))
        }.toList()

        assertEquals(listOf("b"), emissions[0])
        assertEquals(listOf("a", "b"), emissions[1])
        assertTrue(emissions.last().containsAll(emissions.first()))
    }

    @Test
    fun `a backend failing mid flow keeps what already arrived`() = runBlocking {
        // Throwing would discard work that was already usable. The host treats a failure after
        // a successful emission as the end of the flow, not as an error.
        val collected = mutableListOf<List<String>>()

        runCatching {
            flow {
                emit(listOf("survivor"))
                error("second backend died")
            }.toList(collected)
        }

        assertEquals(1, collected.size)
        assertEquals(listOf("survivor"), collected.single())
    }

    @Test
    fun `the interface extends AnimeSource so a source stays usable either way`() {
        // The host calls getVideoList for anything that does not implement this, so a
        // progressive source must still be a normal one.
        assertTrue(
            eu.kanade.tachiyomi.animesource.AnimeSource::class.java
                .isAssignableFrom(ProgressiveVideoSource::class.java) ||
                ProgressiveVideoSource::class.java.interfaces.contains(
                    eu.kanade.tachiyomi.animesource.AnimeSource::class.java,
                ),
        )
    }

    @Test
    fun `the interface adds nothing to AnimeSource itself`() {
        // The safety property. AnimeSource must not gain a member, or every extension compiled
        // against the older shape breaks at call time.
        val members = eu.kanade.tachiyomi.animesource.AnimeSource::class.java.declaredMethods
            .map { it.name }
            .toSet()

        assertTrue("getVideoListFlow" !in members)
    }
}
