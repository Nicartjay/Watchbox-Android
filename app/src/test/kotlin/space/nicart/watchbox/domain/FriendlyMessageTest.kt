package space.nicart.watchbox.domain

import eu.kanade.tachiyomi.network.HttpException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the wording shown when a source fails.
 *
 * This is the only text a user gets when a catalogue comes back empty, so it has to
 * distinguish causes that need different responses. The case that prompted these:
 * Anikage's API started returning 404 for every endpoint, and the screen said
 * "HTTP error 404" - which reads like a bug in WatchBox rather than a source whose
 * API moved and whose extension needs an update.
 *
 * Asserted on substance, not exact phrasing: the tests pin the status code and the
 * actionable distinction, so wording can be reworded without breaking them.
 */
class FriendlyMessageTest {

    @Test
    fun `a 404 points at the extension being out of date`() {
        val message = HttpException(404).friendlyMessage()

        assertTrue("404" in message, "should name the status code: $message")
        assertTrue("update" in message.lowercase(), "should suggest an update: $message")
    }

    @Test
    fun `a 403 is described as blocking, not as a missing page`() {
        val message = HttpException(403).friendlyMessage()

        assertTrue("403" in message, message)
        assertTrue("blocking" in message.lowercase(), message)
        // Must not be conflated with the 404 advice: updating fixes nothing here.
        assertTrue("update" !in message.lowercase(), message)
    }

    @Test
    fun `a 429 is described as rate limiting`() {
        val message = HttpException(429).friendlyMessage()

        assertTrue("429" in message, message)
        assertTrue("rate limited" in message.lowercase(), message)
    }

    @Test
    fun `server errors are attributed to the site`() {
        for (code in listOf(500, 502, 503, 599)) {
            val message = HttpException(code).friendlyMessage()

            assertTrue(code.toString() in message, message)
            assertTrue("site" in message.lowercase(), message)
        }
    }

    @Test
    fun `an unmapped status still reports its code`() {
        val message = HttpException(418).friendlyMessage()

        assertTrue("418" in message, message)
    }

    @Test
    fun `a link error is identified as an incompatible extension`() {
        // The rate-limit ABI failure mode: extensions are linked at runtime, so a
        // signature mismatch arrives as an Error, not an Exception.
        val message = NoSuchMethodError("rateLimit-SxA4cEA").friendlyMessage()

        assertTrue("Incompatible extension" in message, message)
        assertTrue("NoSuchMethodError" in message, message)
    }

    @Test
    fun `network failures get plain wording`() {
        assertEquals("Host not found", java.net.UnknownHostException("x").friendlyMessage())
        assertEquals("Timed out", java.net.SocketTimeoutException().friendlyMessage())
        assertTrue(
            "TLS" in javax.net.ssl.SSLException("x").friendlyMessage(),
            "TLS failures must be named as such",
        )
    }

    @Test
    fun `an unknown throwable falls back to its message then its class name`() {
        assertEquals("boom", IllegalStateException("boom").friendlyMessage())
        // A blank message would render as an empty error box, so the class name is
        // used instead - it is at least identifiable in a report.
        assertEquals("IllegalStateException", IllegalStateException("").friendlyMessage())
        assertEquals("IllegalStateException", IllegalStateException().friendlyMessage())
    }
}
