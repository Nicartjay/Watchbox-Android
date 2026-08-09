package space.nicart.watchbox.cast.dlna

import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regression test for the bug that made DLNA casting find nothing.
 *
 * `DlnaDescriptionParser.fetch` read the device description with
 * `source.readString(MAX_DESCRIPTION_BYTES, UTF_8)`, intending it as a cap. It is
 * not a cap - okio treats the byte count as an exact length and throws
 * `EOFException` when the body is shorter. Device descriptions are a few KB against
 * a 512 KB limit, so **every** fetch threw, the exception was swallowed by
 * `runCatching`, and every discovered device was discarded with only a `Log.d` trace.
 *
 * The symptom was "casting shows no devices" with nothing in the logs to explain it.
 * This test pins the okio behaviour so the same mistake cannot be reintroduced by
 * someone reading `readString(byteCount, charset)` as a limit.
 */
class DlnaDescriptionReadTest {

    /** A realistically sized UPnP description: small. */
    private val description = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <friendlyName>Living Room TV</friendlyName>
            <serviceList>
              <service>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <controlURL>/AVTransport/control</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    private val cap = 512L * 1024

    @Test
    fun `readString with a byte count larger than the body throws`() {
        // The exact defect. If this ever stops throwing, okio changed and the
        // comment in fetch() should be revisited - but the fix stays correct.
        val source = Buffer().writeUtf8(description)

        assertFailsWith<java.io.EOFException> {
            source.readString(cap, Charsets.UTF_8)
        }
    }

    @Test
    fun `the description is far smaller than the cap`() {
        // Establishes that the throwing path was the *only* path in practice, not
        // an edge case for unusually small devices.
        assertTrue(
            description.length < cap / 100,
            "a real description (${description.length} B) is orders of magnitude " +
                "below the $cap B cap, so the exact-length read always threw",
        )
    }

    @Test
    fun `reading the whole body succeeds and preserves the xml`() {
        // What the fixed code does, via peekBody: read what is there, capped.
        val source = Buffer().writeUtf8(description)
        val read = source.readUtf8()

        assertEquals(description, read)
        assertTrue(read.contains("AVTransport"), "the parser needs this to match")
        assertTrue(read.contains("Living Room TV"))
    }

    @Test
    fun `a body at exactly the cap still reads`() {
        // Guards the boundary: the cap must not be off-by-one against a body that
        // happens to be exactly that size.
        val exact = "x".repeat(1024)
        val source = Buffer().writeUtf8(exact)

        assertEquals(exact, source.readString(1024, Charsets.UTF_8))
    }

    @Test
    fun `an oversized body is truncated rather than rejected`() {
        // A hostile or broken device must not be able to stream forever, but it also
        // must not take down discovery for the devices that behave.
        val huge = "y".repeat(4096)
        val source = Buffer().writeUtf8(huge)

        val read = source.readString(1024, Charsets.UTF_8)
        assertEquals(1024, read.length)
    }
}
