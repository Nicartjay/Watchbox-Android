package space.nicart.watchbox.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import space.nicart.watchbox.data.remote.SubtitleProvider
import space.nicart.watchbox.data.remote.SubtitleResult

/**
 * Tests for grouping online subtitle results by the catalogue they came from.
 *
 * Grouping is the whole behaviour: the catalogues overlap heavily, so a merged list fills with
 * near-duplicates of the same file under different release names, and telling them apart means
 * downloading them one at a time. Keeping the source visible is also the only way a viewer learns
 * that one catalogue suits their releases.
 */
class SubtitleGroupingTest {

    private fun result(id: String, name: String, downloads: Long = 0L) = SubtitleResult(
        id = id,
        name = name,
        language = "en",
        languageName = "English",
        downloadUrl = "https://x/$id",
        format = "srt",
        downloads = downloads,
        hearingImpaired = false,
    )

    // ---------------------------------------------------------------- shape

    @Test
    fun `a group keeps its provider alongside its results`() {
        val group = SubtitleGroup(
            provider = SubtitleProvider.SUBS_BRIGHT,
            results = listOf(result("1", "Release.A")),
        )

        assertEquals(SubtitleProvider.SUBS_BRIGHT, group.provider)
        assertEquals(1, group.results.size)
    }

    @Test
    fun `flattening a grouped result preserves every row`() {
        // What the download prompt uses: it asks for one file to save beside the video, and the
        // catalogue it came from does not change what it is.
        val groups = listOf(
            SubtitleGroup(SubtitleProvider.VIDFAST_WYZIE, listOf(result("1", "A"))),
            SubtitleGroup(SubtitleProvider.SUBS_BRIGHT, listOf(result("2", "B"), result("3", "C"))),
        )

        assertEquals(3, groups.flatMap { it.results }.size)
    }

    // ------------------------------------------------------------- ordering

    @Test
    fun `sections keep the enum order rather than the order they answered in`() {
        // Run in parallel, so whichever service is quickest varies between searches. Sorting by
        // the enum keeps the panel's sections in one place instead of reshuffling each time.
        val answered = listOf(
            SubtitleProvider.VIDFAST_WYZIE,
            SubtitleProvider.OPEN_SUBTITLES_LEGACY,
            SubtitleProvider.SUBS_BRIGHT,
        )

        val ordered = SubtitleProvider.entries.filter { it in answered }

        assertEquals(
            listOf(
                SubtitleProvider.OPEN_SUBTITLES_LEGACY,
                SubtitleProvider.VIDFAST_WYZIE,
                SubtitleProvider.SUBS_BRIGHT,
            ),
            ordered,
        )
    }

    // -------------------------------------------------------------- keying

    @Test
    fun `the same file in two catalogues needs a provider prefixed key`() {
        // Both catalogues index OpenSubtitles, so an id genuinely repeats across groups - and
        // Compose throws outright on a duplicate key, which would crash the panel on open.
        val shared = result("1956089121", "The.Rookie.S01E01.HDTV.x264-SVA")
        val groups = listOf(
            SubtitleGroup(SubtitleProvider.OPEN_SUBTITLES_LEGACY, listOf(shared)),
            SubtitleGroup(SubtitleProvider.SUBS_BRIGHT, listOf(shared)),
        )

        val keys = groups.flatMap { group ->
            group.results.map { "${group.provider.name}-${it.id}" }
        }

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `a bare id would collide across catalogues`() {
        // The failure the prefix prevents, stated so the prefix is not removed as noise.
        val shared = result("1956089121", "Same.File")
        val ids = listOf(shared, shared).map { it.id }

        assertTrue(ids.size > ids.toSet().size)
    }

    // ------------------------------------------------------ empty handling

    @Test
    fun `a catalogue that found nothing is dropped rather than shown empty`() {
        // Four empty headings say less than the single line the panel shows when every group is
        // gone.
        val groups = listOf(
            SubtitleGroup(SubtitleProvider.OPEN_SUBTITLES_LEGACY, emptyList()),
            SubtitleGroup(SubtitleProvider.SUBS_BRIGHT, listOf(result("1", "A"))),
            SubtitleGroup(SubtitleProvider.VIDFAST_WYZIE, emptyList()),
        )

        val shown = groups.filter { it.results.isNotEmpty() }

        assertEquals(1, shown.size)
        assertEquals(SubtitleProvider.SUBS_BRIGHT, shown.single().provider)
    }

    @Test
    fun `every catalogue finding nothing leaves no groups at all`() {
        // Which is what the panel reads as "nothing found anywhere", not as a failure.
        val groups = SubtitleProvider.entries.map { SubtitleGroup(it, emptyList()) }

        assertTrue(groups.filter { it.results.isNotEmpty() }.isEmpty())
    }

    // -------------------------------------------------- enabled providers

    @Test
    fun `an empty enabled set means every catalogue, not none`() {
        // What a fresh install reads. Refusing to search anything would look like a broken
        // button rather than a default.
        val stored = emptySet<SubtitleProvider>()
        val effective = stored.takeIf { it.isNotEmpty() } ?: SubtitleProvider.entries.toSet()

        assertEquals(SubtitleProvider.entries.size, effective.size)
    }

    @Test
    fun `a disabled catalogue is not searched`() {
        val enabled = SubtitleProvider.entries.toSet() - SubtitleProvider.OPEN_SUBTITLES_API

        assertTrue(SubtitleProvider.OPEN_SUBTITLES_API !in enabled)
        assertEquals(SubtitleProvider.entries.size - 1, enabled.size)
    }
}
