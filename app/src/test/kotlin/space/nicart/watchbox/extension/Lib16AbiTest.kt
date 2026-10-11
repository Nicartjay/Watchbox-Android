package space.nicart.watchbox.extension

import android.net.Uri
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import kotlin.jvm.internal.DefaultConstructorMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import okhttp3.Headers
import space.nicart.watchbox.domain.toStreamOption

/**
 * Tests for the parts of the lib 16 ABI a compiler cannot check.
 *
 * Extensions link against exact JVM signatures, including the synthetic default-argument
 * constructors and `copy$default` Kotlin generates. A signature that drifts still compiles
 * here and fails only in an extension, with `NoSuchMethodError`. The shapes below are the
 * ones `dexdump` shows real extensions in the default repo calling.
 */
class Lib16AbiTest {

    private val string = String::class.java
    private val list = List::class.java
    private val int = Int::class.javaPrimitiveType!!
    private val boolean = Boolean::class.javaPrimitiveType!!
    private val boxedInt = Int::class.javaObjectType
    private val headers = Headers::class.java
    private val marker = DefaultConstructorMarker::class.java

    private val lib16Fields = arrayOf(
        string, string, boxedInt, boxedInt, headers, boolean,
        list, list, list, list, list, list, string, boolean,
    )

    @Test
    fun `every Video constructor shape extensions call exists`() {
        val video = Video::class.java

        // Lib 14, and the synthetic its default arguments compile to.
        assertNotNull(video.getConstructor(string, string, string, headers, list, list))
        assertNotNull(
            video.getConstructor(string, string, string, headers, list, list, int, marker),
        )
        // Legacy Uri shape, synthetic only.
        assertNotNull(
            video.getConstructor(string, string, string, Uri::class.java, headers, int, marker),
        )
        // Lib 16, plain and synthetic.
        assertNotNull(video.getConstructor(*lib16Fields))
        assertNotNull(video.getConstructor(*lib16Fields, int, marker))
    }

    @Test
    fun `the lib 16 data-class copy exists`() {
        assertNotNull(
            Video::class.java.getMethod(
                "copy\$default",
                Video::class.java,
                *lib16Fields,
                int,
                Any::class.java,
            ),
        )
    }

    @Test
    fun `a lib 14 video reads back through both generations of names`() {
        val video = Video("https://site/ep/1", "1080p", "https://cdn/1.m3u8", null as Headers?)

        assertEquals("1080p", video.quality)
        assertEquals("1080p", video.videoTitle)
        assertEquals("https://site/ep/1", video.url)
        assertEquals("https://cdn/1.m3u8", video.videoUrl)
        assertEquals(1080, video.resolutionOrZero)
    }

    /** The behaviour from before `videoUrl` was non-null: fall back to the page URL. */
    @Test
    fun `a lib 14 video with no media url still plays from its page`() {
        val video = Video("https://site/ep/1", "720p", null as String?, null as Headers?)

        assertEquals("null", video.videoUrl)
        assertEquals("https://site/ep/1", video.toStreamOption().url)
    }

    @Test
    fun `a source's own resolution beats the one in its label`() {
        assertEquals(720, Video(videoTitle = "1080p", resolution = 720).resolutionOrZero)
    }

    @Test
    fun `hoster constructor shapes exist and a plain list wraps as one`() {
        val hoster = Hoster::class.java
        assertNotNull(hoster.getConstructor(string, string, list, string, boolean))
        assertNotNull(hoster.getConstructor(string, string, list, string, boolean, int, marker))

        val wrapped = listOf(Video(videoUrl = "https://cdn/a")).toHosterList().single()
        assertEquals(Hoster.NO_HOSTER_LIST, wrapped.hosterName)
        assertEquals(1, wrapped.videoList?.size)
    }

    @Test
    fun `new SAnime and SEpisode fields default to what lib 14 entries mean`() {
        val anime = SAnime.create()
        assertEquals(FetchType.Episodes, anime.fetch_type)
        assertEquals(-1.0, anime.season_number)
        assertEquals(AnimeUpdateStrategy.ALWAYS_UPDATE, anime.update_strategy)

        val episode = SEpisode.create()
        assertFalse(episode.fillermark)
        assertEquals(null, episode.summary)
        assertEquals(null, episode.preview_url)
    }

    /** Four lib 14 extensions call `getList()` directly. */
    @Test
    fun `the filter list is readable by name`() {
        assertNotNull(AnimeFilterList::class.java.getMethod("getList"))
    }
}
