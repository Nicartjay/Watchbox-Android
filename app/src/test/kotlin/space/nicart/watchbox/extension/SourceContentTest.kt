package space.nicart.watchbox.extension

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceContentTest {
    @Test
    fun `all current and future Arvin suffixes are short form`() {
        listOf("Arvin-HD", "Arvin-RF", "Arvin-ND", "Arvin-KD", "Arvin-ZZ", "Arvin-NewProvider")
            .forEach { assertTrue(isShortReelsProvider(it), it) }
    }

    @Test
    fun `matching tolerates casing and surrounding whitespace`() {
        assertTrue(isShortReelsProvider("  arvin-hd  "))
        assertTrue(isShortReelsProvider("ARVIN-RF"))
    }

    @Test
    fun `the prefix must be at the start and include the hyphen`() {
        listOf("", "Arvin", "ArvinHD", "My Arvin-HD", "Rentaro", "Cineby")
            .forEach { assertFalse(isShortReelsProvider(it), it) }
    }

    @Test
    fun `an Arvin factory classifies sources with other display names`() {
        assertTrue(isShortReelsProvider(sourceName = "Drama provider", extensionName = "Arvin-ZZ"))
    }

    @Test
    fun `an Arvin source is recognized inside a differently named extension`() {
        assertTrue(isShortReelsProvider(sourceName = "Arvin-HD", extensionName = "Provider bundle"))
    }

    @Test
    fun `standard and short collections are disjoint and all includes both`() {
        listOf(false, true).forEach { short ->
            assertTrue(SourceCollection.ALL.includes(short))
            assertTrue(SourceCollection.STANDARD.includes(short) != SourceCollection.SHORT_REELS.includes(short))
        }
        assertTrue(SourceCollection.STANDARD.includes(false))
        assertTrue(SourceCollection.SHORT_REELS.includes(true))
    }
}
