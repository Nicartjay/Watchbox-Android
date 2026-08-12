package space.nicart.watchbox.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import space.nicart.watchbox.data.remote.SubtitleApi.Companion.toIso639_2

/**
 * Tests for online subtitle search behaviour.
 *
 * The ordering and the gzip check are the parts worth pinning. Ordering decides which subtitle
 * a user picks - almost nobody scrolls a list of thirty release names - and the gzip check
 * decides whether the downloaded file is readable at all. Both are silent when wrong: a
 * mis-ranked list still looks like a list, and a wrongly-decompressed subtitle just never
 * displays.
 */
class SubtitleApiTest {

    private fun result(
        id: String = "1",
        name: String = "Release",
        downloads: Long = 0L,
        hearingImpaired: Boolean = false,
        language: String = "en",
        format: String = "srt",
    ) = SubtitleResult(
        id = id,
        name = name,
        language = language,
        languageName = language,
        downloadUrl = "https://example.test/$id",
        format = format,
        downloads = downloads,
        hearingImpaired = hearingImpaired,
    )

    @Test
    fun `the most downloaded release comes first`() {
        val ranked = rankSubtitles(
            listOf(
                result(id = "a", downloads = 10),
                result(id = "b", downloads = 9_000),
                result(id = "c", downloads = 300),
            ),
        )

        assertEquals(listOf("b", "c", "a"), ranked.map { it.id })
    }

    /**
     * Hearing-impaired releases are correct subtitles, but they annotate sounds. Someone who
     * wanted them would have asked, so they sort below everything else regardless of how
     * popular they are.
     */
    @Test
    fun `hearing-impaired releases sort last even when more popular`() {
        val ranked = rankSubtitles(
            listOf(
                result(id = "hi", downloads = 1_000_000, hearingImpaired = true),
                result(id = "plain", downloads = 5),
            ),
        )

        assertEquals(listOf("plain", "hi"), ranked.map { it.id })
    }

    @Test
    fun `the list is capped`() {
        val many = (1..100).map { result(id = it.toString(), downloads = it.toLong()) }

        assertEquals(30, rankSubtitles(many, limit = 30).size)
    }

    @Test
    fun `ranking an empty list yields nothing`() {
        assertTrue(rankSubtitles(emptyList()).isEmpty())
    }

    /** The legacy endpoint is positional and wants three-letter codes. */
    @Test
    fun `two-letter language codes become three-letter ones`() {
        assertEquals("eng", "en".toIso639_2())
        assertEquals("jpn", "ja".toIso639_2())
        assertEquals("por", "pt".toIso639_2())
    }

    @Test
    fun `language codes are case insensitive`() {
        assertEquals("eng", "EN".toIso639_2())
        assertEquals("spa", "Es".toIso639_2())
    }

    /**
     * An unmapped code is passed through rather than dropped. The API treats an unknown value
     * as no language filter, which returns too much - still better than returning nothing
     * because the app refused to ask.
     */
    @Test
    fun `an unmapped language code passes through unchanged`() {
        assertEquals("xyz", "xyz".toIso639_2())
        assertEquals("eng", "eng".toIso639_2())
    }

    @Test
    fun `gzip is detected from the magic bytes`() {
        assertTrue(isGzipped(byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00)))
    }

    /** A plain SRT begins with a cue number, and must not be run through the decompressor. */
    @Test
    fun `plain text is not treated as gzip`() {
        assertFalse(isGzipped("1\n00:00:01,000 --> 00:00:02,000\n".toByteArray()))
        assertFalse(isGzipped("WEBVTT\n\n".toByteArray()))
    }

    /** A truncated or empty download must not index past the end of the array. */
    @Test
    fun `an empty or one-byte payload is not gzip`() {
        assertFalse(isGzipped(byteArrayOf()))
        assertFalse(isGzipped(byteArrayOf(0x1f)))
    }

    /**
     * The cached filename keeps the format extension. The player infers the MIME type from the
     * path, and an extensionless file is assumed to be WebVTT - so a SubRip download saved
     * without one parses as nothing and displays no text.
     */
    @Test
    fun `the cache filename keeps the subtitle format`() {
        assertTrue(result(id = "12345", format = "srt").cacheFileName().endsWith(".srt"))
        assertTrue(result(id = "12345", format = "vtt").cacheFileName().endsWith(".vtt"))
        assertTrue(result(id = "12345", format = "ass").cacheFileName().endsWith(".ass"))
    }

    /** Ids come from a remote payload, so they cannot be trusted as path segments. */
    @Test
    fun `the cache filename strips characters that are unsafe in a path`() {
        val name = result(id = "../../etc/passwd").cacheFileName()

        assertFalse(name.contains('/'))
        assertFalse(name.contains('.') && name.substringBeforeLast('.').contains('.'))
        assertTrue(name.startsWith("sub-"))
    }

    @Test
    fun `a result with no stated format defaults to subrip`() {
        assertTrue(result(id = "9", format = "").cacheFileName().endsWith(".srt"))
    }

    /**
     * A query with neither id is unusable: the legacy provider needs IMDb and the REST one
     * needs TMDB, so there is nothing to search by and the UI should say so rather than fire a
     * request that cannot match.
     */
    @Test
    fun `a query with no ids is unusable`() {
        assertTrue(
            SubtitleQuery(
                imdbId = null,
                tmdbId = null,
                season = 1,
                episode = 1,
                language = "en",
            ).isUnusable,
        )
    }

    @Test
    fun `a query with either id is usable`() {
        val byImdb = SubtitleQuery("tt0133093", null, null, null, "en")
        val byTmdb = SubtitleQuery(null, 603, null, null, "en")

        assertFalse(byImdb.isUnusable)
        assertFalse(byTmdb.isUnusable)
    }

    /** A blank string is what TMDB returns for a title it has no IMDb match for. */
    @Test
    fun `a blank imdb id does not count as an id`() {
        assertTrue(SubtitleQuery("", null, null, null, "en").isUnusable)
        assertTrue(SubtitleQuery("   ", null, null, null, "en").isUnusable)
    }
}
