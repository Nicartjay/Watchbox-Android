package space.nicart.watchbox.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests the address check behind the "open site" button.
 *
 * Worth testing because the input is third-party: the URL comes from an extension's
 * `baseUrl`, which is arbitrary code, and the output is handed to `ACTION_VIEW` - an
 * implicit intent. Allowing a non-web scheme through would let an extension aim that
 * intent at other installed apps, so the scheme restriction is a boundary rather
 * than a formatting nicety.
 */
class SanitiseWebUrlTest {

    @Test
    fun `https and http addresses pass through unchanged`() {
        assertEquals("https://anikage.cc", sanitiseWebUrl("https://anikage.cc"))
        assertEquals("http://example.com/x?y=1", sanitiseWebUrl("http://example.com/x?y=1"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("https://anizone.to", sanitiseWebUrl("  https://anizone.to \n"))
    }

    @Test
    fun `a schemeless domain is promoted to https`() {
        // Some extensions store a bare domain; dropping the link would be worse than
        // assuming the modern default.
        assertEquals("https://anikage.cc", sanitiseWebUrl("anikage.cc"))
        assertEquals("https://www.miruro.tv/browse", sanitiseWebUrl("www.miruro.tv/browse"))
    }

    @Test
    fun `an uppercase scheme is still recognised`() {
        assertEquals("HTTPS://anikage.cc", sanitiseWebUrl("HTTPS://anikage.cc"))
    }

    @Test
    fun `non-web schemes are rejected`() {
        // The security-relevant case: these must not reach ACTION_VIEW.
        assertNull(sanitiseWebUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(sanitiseWebUrl("file:///data/data/space.nicart.watchbox/databases"))
        assertNull(sanitiseWebUrl("javascript:alert(1)"))
        assertNull(sanitiseWebUrl("market://details?id=com.example"))
        assertNull(sanitiseWebUrl("content://media/external/images/1"))
        assertNull(sanitiseWebUrl("tel:+15551234"))
    }

    @Test
    fun `blank and null input is rejected`() {
        assertNull(sanitiseWebUrl(null))
        assertNull(sanitiseWebUrl(""))
        assertNull(sanitiseWebUrl("   "))
    }

    @Test
    fun `a scheme with no host is rejected`() {
        assertNull(sanitiseWebUrl("https://"))
        assertNull(sanitiseWebUrl("http://"))
    }

    @Test
    fun `embedded whitespace is rejected`() {
        assertNull(sanitiseWebUrl("https://exa mple.com"))
        assertNull(sanitiseWebUrl("https://example.com/a b"))
    }
}
