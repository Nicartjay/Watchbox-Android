package space.nicart.watchbox.domain

import kotlinx.coroutines.runBlocking
import space.nicart.watchbox.data.remote.TmdbExtras
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceMetadataPolicyTest {
    @Test
    fun `current and future Arvin providers never execute a metadata request`() = runBlocking {
        val policy = SourceMetadataPolicy { _, _ -> false }
        var requests = 0

        listOf("Arvin-HD", "Arvin-RF", "Arvin-ND", "Arvin-KD", "Arvin-NewProvider", " arvin-zz ")
            .forEach { name ->
                val result = policy.load(1L, name, fallback = "source only") {
                    requests++
                    "TMDB result"
                }
                assertEquals("source only", result, name)
            }

        assertEquals(0, requests)
    }

    @Test
    fun `an Arvin owning extension blocks metadata for a differently named source`() = runBlocking {
        val policy = SourceMetadataPolicy { id, _ -> id == 42L }

        assertNull(
            policy.load(42L, "Drama provider", fallback = null) {
                error("An Arvin extension must not request TMDB data")
            },
        )
    }

    @Test
    fun `saved Arvin names still block requests after their extension is removed`() = runBlocking {
        val policy = SourceMetadataPolicy { _, _ -> false }

        assertEquals(
            TmdbExtras(),
            policy.load(99L, "Arvin-RF", fallback = TmdbExtras()) {
                error("An old saved TMDB id must not trigger extras or ratings")
            },
        )
    }

    @Test
    fun `ordinary movie and series providers still execute their metadata requests`() = runBlocking {
        val policy = SourceMetadataPolicy { _, _ -> false }
        var requests = 0

        listOf("Rentaro", "Cineby", "ArvinHD", "My Arvin-HD").forEach { name ->
            assertEquals(
                "TMDB result",
                policy.load(2L, name, fallback = "source only") {
                    requests++
                    "TMDB result"
                },
            )
        }
        assertEquals(4, requests)
    }

    @Test
    fun `the source registry receives both identity and saved name`() = runBlocking {
        var identity: Pair<Long, String>? = null
        val policy = SourceMetadataPolicy { id, name ->
            identity = id to name
            true
        }

        assertNull(policy.load(7L, "Provider bundle", fallback = null) { "unexpected" })
        assertEquals(7L to "Provider bundle", identity)
    }
}
