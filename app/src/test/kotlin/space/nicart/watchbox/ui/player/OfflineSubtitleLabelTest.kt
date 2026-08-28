package space.nicart.watchbox.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import space.nicart.watchbox.data.local.DownloadEntry
import space.nicart.watchbox.data.local.DownloadedSubtitle

/**
 * Tests for naming a subtitle that was downloaded with an episode.
 *
 * The reported failure was a track listed as "OFF (Downloaded)". Its language was read back from
 * the filename, and where that failed the code fell back to the *stored subtitle preference* -
 * so a viewer who had turned subtitles off had that word rendered as though it were a language.
 * A preference is not evidence of what is in a file, which is why the name is now recorded at
 * download time instead.
 */
class OfflineSubtitleLabelTest {

    private fun entry(
        tracks: List<DownloadedSubtitle> = emptyList(),
        legacyPaths: List<String> = emptyList(),
    ) = DownloadEntry(
        sourceId = 1L,
        animeUrl = "/anime",
        episodeUrl = "/ep1",
        title = "A Show",
        episodeName = "Episode 1",
        episodeNumber = 1f,
        posterUrl = null,
        sourceName = "Src",
        streamLabel = "Srv · 1080p",
        subtitleTracks = tracks,
        subtitlePaths = legacyPaths,
    )

    // ------------------------------------------------------------- labelling

    @Test
    fun `uses the name it was downloaded under`() {
        // What the viewer actually picked from the search, so the row matches what they chose.
        val label = offlineSubtitleLabel(
            DownloadedSubtitle(
                url = "file:///sub.srt",
                label = "Show.S01E01.1080p.WEB-DL",
                language = "en",
            ),
        )

        assertEquals("Show.S01E01.1080p.WEB-DL", label)
    }

    @Test
    fun `falls back to the language name when no label was stored`() {
        val label = offlineSubtitleLabel(
            DownloadedSubtitle(url = "file:///sub.srt", label = "", language = "ja"),
        )

        assertEquals("Japanese", label)
    }

    @Test
    fun `uppercases a language it cannot name`() {
        val label = offlineSubtitleLabel(
            DownloadedSubtitle(url = "file:///sub.srt", label = "", language = "xy"),
        )

        assertEquals("XY", label)
    }

    @Test
    fun `falls back to a generic name when nothing survived`() {
        val label = offlineSubtitleLabel(
            DownloadedSubtitle(url = "file:///sub-abc.srt", label = "", language = ""),
        )

        assertEquals(GENERIC_SUBTITLE_LABEL, label)
    }

    @Test
    fun `never renders off as a language`() {
        // The reported bug. Even if "off" reached the record, it must not be shown as a name -
        // and the row is no longer built from the stored preference at all.
        val label = offlineSubtitleLabel(
            DownloadedSubtitle(url = "file:///sub.srt", label = "English", language = "off"),
        )

        assertEquals("English", label)
    }

    @Test
    fun `the downloaded note is not part of the name`() {
        // It goes on a second line under the row, so a release name is not competing with a
        // badge - and a row can never be reduced to the badge alone.
        val label = offlineSubtitleLabel(
            DownloadedSubtitle(url = "file:///sub.srt", label = "English", language = "en"),
        )

        assertEquals("English", label)
    }

    // --------------------------------------------------- legacy entries

    @Test
    fun `recovers the language from an old source track filename`() {
        // Written as `src-<index>-<lang>.<ext>` before names were stored.
        val recovered = DownloadedSubtitle.fromLegacyPath("file:///dir/src-0-en.vtt")

        assertEquals("en", recovered.language)
        assertEquals("English", offlineSubtitleLabel(recovered))
    }

    @Test
    fun `an old searched file gives up nothing and reads generically`() {
        // `sub-<id>.srt` carries no language and no name, by design: provider ids are safe in a
        // path and release names are not.
        val recovered = DownloadedSubtitle.fromLegacyPath("file:///dir/sub-9912345.srt")

        assertEquals("", recovered.language)
        assertEquals(GENERIC_SUBTITLE_LABEL, offlineSubtitleLabel(recovered))
    }

    @Test
    fun `an old track with no language reads generically rather than as sub`() {
        // "sub" is the placeholder the downloader used for a blank language; it is not a name.
        val recovered = DownloadedSubtitle.fromLegacyPath("file:///dir/src-1-sub.vtt")

        assertEquals("", recovered.language)
        assertEquals(GENERIC_SUBTITLE_LABEL, offlineSubtitleLabel(recovered))
    }

    // ------------------------------------------------------ both fields

    @Test
    fun `reads new and legacy records together`() {
        // An entry can hold both: legacy paths from before the change, and named tracks from a
        // subtitle added afterwards.
        val combined = entry(
            tracks = listOf(
                DownloadedSubtitle("file:///new.srt", "Chosen Release", "en"),
            ),
            legacyPaths = listOf("file:///dir/src-0-ja.vtt"),
        ).allSubtitles

        assertEquals(2, combined.size)
        assertEquals(listOf("Chosen Release", "Japanese"), combined.map(::offlineSubtitleLabel))
    }

    @Test
    fun `named tracks come first so a new download is not buried`() {
        val combined = entry(
            tracks = listOf(DownloadedSubtitle("file:///new.srt", "Chosen", "en")),
            legacyPaths = listOf("file:///dir/src-0-ja.vtt", "file:///dir/sub-1.srt"),
        ).allSubtitles

        assertEquals("file:///new.srt", combined.first().url)
    }

    @Test
    fun `an entry with no subtitles reports none`() {
        assertEquals(0, entry().allSubtitles.size)
    }
}
