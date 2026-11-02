package space.nicart.watchbox.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import space.nicart.watchbox.data.local.DownloadEntry
import space.nicart.watchbox.data.local.DownloadState
import space.nicart.watchbox.domain.AnimeDetail
import space.nicart.watchbox.domain.AnimeStatus
import space.nicart.watchbox.domain.EpisodeEntry
import space.nicart.watchbox.domain.StreamOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShortReelsDownloadQueueTest {
    @Test
    fun `automatic quality honors Auto ordering and the saved height without a picker`() {
        val options = listOf(stream(2160), stream(720), stream(480))
        assertEquals(2160, automaticDownloadStream(options, null)?.resolution)
        assertEquals(720, automaticDownloadStream(options, 1080)?.resolution)
        assertEquals(480, automaticDownloadStream(options, 360)?.resolution)
        assertNull(automaticDownloadStream(listOf(stream(720).copy(url = "")), 1080))
        assertEquals(0, automaticDownloadStream(listOf(stream(0)), 1080)?.resolution)
    }

    @Test
    fun `existing finished active and paused downloads are not overwritten`() {
        assertTrue(shouldQueueAutomaticDownload(null))
        DownloadState.entries.forEach { state ->
            assertEquals(state == DownloadState.FAILED, shouldQueueAutomaticDownload(entry(detail(), "ep1", state)))
        }
    }

    @Test
    fun `whole-series requests queue every episode once in source order`() = runBlocking {
        val title = detail()
        val fixture = Fixture(this)
        fixture.queue.enqueue(title, title.episodes + title.episodes.first())
        fixture.queue.enqueue(title, title.episodes)
        val state = finished(fixture.queue, title)

        assertEquals(listOf("ep1", "ep2", "ep3"), fixture.prepared)
        assertEquals(3, state.total)
        assertEquals(3, state.queued)
        assertEquals(0, state.failed)
        assertEquals(1, fixture.starts)
        assertEquals(3, fixture.entries.size)
    }

    @Test
    fun `batch skips existing downloads and retries failed episodes only`() = runBlocking {
        val title = detail(count = 5)
        val fixture = Fixture(this)
        listOf(DownloadState.COMPLETED, DownloadState.QUEUED, DownloadState.PAUSED, DownloadState.FAILED)
            .forEachIndexed { index, status ->
                val saved = entry(title, "ep${index + 1}", status)
                fixture.entries[saved.key] = saved
            }
        fixture.queue.enqueue(title, title.episodes)
        val state = finished(fixture.queue, title)

        assertEquals(listOf("ep4", "ep5"), fixture.prepared)
        assertEquals(3, state.skipped)
        assertEquals(2, state.queued)
        assertEquals(DownloadState.PAUSED, fixture.entries.getValue("${title.key}::ep3").state)
    }

    @Test
    fun `one episode failing does not stop the rest of the series`() = runBlocking {
        val title = detail()
        val fixture = Fixture(this, beforePrepare = { _, episode ->
            if (episode.url == "ep2") error("Episode unavailable")
        })
        fixture.queue.enqueue(title, title.episodes)
        val state = finished(fixture.queue, title)

        assertEquals(listOf("ep1", "ep3"), fixture.prepared)
        assertEquals(2, state.queued)
        assertEquals(1, state.failed)
        assertEquals("Episode unavailable", state.lastError)
        assertEquals(3, state.processed)
    }

    @Test
    fun `stopping a batch releases pending reservations and never queues the remaining episodes`() = runBlocking {
        val title = detail()
        val preparing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture(this, beforePrepare = { _, episode ->
            if (episode.url == "ep1") {
                preparing.complete(Unit)
                release.await()
            }
        })
        fixture.queue.enqueue(title, title.episodes)
        withTimeout(5_000) { preparing.await() }
        fixture.queue.cancel(title.key)
        val state = finished(fixture.queue, title)
        assertEquals(3, state.cancelled)
        assertTrue(fixture.prepared.isEmpty())

        release.complete(Unit)
        fixture.queue.enqueue(title, listOf(title.episodes[1]))
        assertEquals(1, finished(fixture.queue, title).queued)
        assertEquals(listOf("ep2"), fixture.prepared)
    }

    @Test
    fun `a full registry refuses a batch rather than losing older downloads`() = runBlocking {
        val title = detail()
        val fixture = Fixture(this)
        repeat(DownloadEntry.MAX_ENTRIES) { index ->
            val saved = entry(title, "older-$index", DownloadState.COMPLETED)
            fixture.entries[saved.key] = saved
        }
        fixture.queue.enqueue(title, title.episodes)
        val state = finished(fixture.queue, title)

        assertEquals(DownloadEntry.MAX_ENTRIES, fixture.entries.size)
        assertTrue(fixture.prepared.isEmpty())
        assertEquals(3, state.failed)
        assertTrue(state.lastError.orEmpty().contains("limit", ignoreCase = true))
    }

    @Test
    fun `network requirements are checked before any episode stream is requested`() = runBlocking {
        val title = detail()
        val fixture = Fixture(this, startError = "Wi-Fi required")
        fixture.queue.enqueue(title, title.episodes)
        val state = finished(fixture.queue, title)

        assertTrue(fixture.prepared.isEmpty())
        assertEquals(3, state.failed)
        assertEquals("Wi-Fi required", state.lastError)
    }

    @Test
    fun `already downloaded series do not start preparation or a foreground service`() = runBlocking {
        val title = detail()
        val fixture = Fixture(this)
        title.episodes.forEach {
            val saved = entry(title, it.url, DownloadState.COMPLETED)
            fixture.entries[saved.key] = saved
        }
        fixture.queue.enqueue(title, title.episodes)
        val state = finished(fixture.queue, title)

        assertEquals(0, fixture.starts)
        assertEquals(3, state.skipped)
        assertEquals(0, state.queued)
    }

    @Test
    fun `concurrent requests serialize provider resolution while preserving both series`() = runBlocking {
        val first = detail()
        val second = detail().copy(url = "/another")
        var active = 0
        var maximum = 0
        val fixture = Fixture(this, beforePrepare = { _, _ ->
            active++
            maximum = maxOf(maximum, active)
            delay(5)
            active--
        })
        fixture.queue.enqueue(first, first.episodes)
        fixture.queue.enqueue(second, second.episodes)

        assertEquals(3, finished(fixture.queue, first).queued)
        assertEquals(3, finished(fixture.queue, second).queued)
        assertEquals(1, maximum)
        assertEquals(6, fixture.entries.size)
    }

    @Test
    fun `an empty episode list does not create a phantom running batch`() = runBlocking {
        val fixture = Fixture(this)
        fixture.queue.enqueue(detail(), emptyList())
        assertTrue(fixture.queue.states.value.isEmpty())
        assertEquals(0, fixture.starts)
    }

    @Test
    fun `completed queue state allows Back and page disposal without owning the download jobs`() = runBlocking {
        val title = detail()
        val fixture = Fixture(this)
        fixture.queue.enqueue(title, title.episodes)
        assertFalse(finished(fixture.queue, title).isRunning)
        assertEquals(3, fixture.entries.values.count { it.state == DownloadState.QUEUED })
    }

    private class Fixture(
        scope: CoroutineScope,
        beforePrepare: suspend (AnimeDetail, EpisodeEntry) -> Unit = { _, _ -> },
        startError: String? = null,
    ) {
        val entries = mutableMapOf<String, DownloadEntry>()
        val prepared = mutableListOf<String>()
        var starts = 0
        val queue = ShortReelsDownloadQueue(
            scope = scope,
            existingDownloads = { entries.values.toList() },
            onStart = {
                if (startError != null) error(startError)
                starts++
            },
            prepare = { title, episode ->
                beforePrepare(title, episode)
                val saved = entry(title, episode.url, DownloadState.QUEUED)
                entries[saved.key] = saved
                prepared += episode.url
            },
            onIdle = {},
        )
    }

    private suspend fun finished(queue: ShortReelsDownloadQueue, title: AnimeDetail) =
        withTimeout(5_000) {
            queue.states.first { it[title.key]?.let { state -> state.total > 0 && !state.isRunning } == true }
                .getValue(title.key)
        }

    private fun stream(height: Int) = StreamOption(
        label = "${height}p",
        url = "https://video.test/$height.mp4",
        headers = emptyMap(),
        subtitles = emptyList(),
        audioTracks = emptyList(),
        resolution = height,
    )

    private companion object {
        fun detail(count: Int = 3) = AnimeDetail(
            sourceId = 1L,
            sourceName = "Arvin-RF",
            url = "/series",
            title = "Short drama",
            posterUrl = null,
            description = "",
            author = null,
            artist = null,
            genres = emptyList(),
            status = AnimeStatus.ONGOING,
            episodes = List(count) {
                EpisodeEntry("ep${it + 1}", "Episode ${it + 1}", it + 1f, dateUpload = 0L, scanlator = null)
            },
        )

        fun entry(title: AnimeDetail, episodeUrl: String, state: DownloadState) = DownloadEntry(
            sourceId = title.sourceId,
            animeUrl = title.url,
            title = title.title,
            episodeUrl = episodeUrl,
            sourceName = title.sourceName,
            state = state,
        )
    }
}
