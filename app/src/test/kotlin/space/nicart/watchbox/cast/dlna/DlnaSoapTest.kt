package space.nicart.watchbox.cast.dlna

import space.nicart.watchbox.cast.CastMedia
import space.nicart.watchbox.cast.CastSubtitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the DLNA SOAP and DIDL-Lite builders.
 *
 * Unit-tested rather than checked on hardware because a malformed envelope is
 * accepted by the renderer and then does nothing, or fails with a generic UPnP
 * error that points nowhere near the actual mistake. The quoting of SOAPAction
 * and the escaping of nested DIDL are both cases where the wrong output looks
 * entirely reasonable.
 */
class DlnaSoapTest {

    private val media = CastMedia(
        url = "http://192.168.1.5:8909/s/1.mp4",
        mimeType = "video/mp4",
        title = "Title & \"Quoted\" <Tag>",
        durationMs = 5_430_000,
        artworkUrl = "http://192.168.1.5:8909/art.jpg",
    )

    @Test
    fun `soap action value keeps its surrounding quotes`() {
        // Renderers reject the request when the quotes are missing, and the
        // header still looks correct in a log.
        assertEquals(
            "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"",
            DlnaSoap.soapAction("Play"),
        )
    }

    @Test
    fun `set uri envelope carries instance id, url and metadata`() {
        val envelope = DlnaSoap.setAvTransportUri("http://host/v.mp4", "<DIDL-Lite/>")

        assertTrue(envelope.contains("<InstanceID>0</InstanceID>"))
        assertTrue(envelope.contains("u:SetAVTransportURI"))
        assertTrue(envelope.contains("xmlns:u=\"${DlnaSoap.AV_TRANSPORT_SERVICE}\""))
        // Metadata is nested XML inside an XML element, so it must be escaped.
        assertTrue(envelope.contains("&lt;DIDL-Lite/&gt;"), envelope)
    }

    @Test
    fun `play envelope includes speed which some renderers require`() {
        assertTrue(DlnaSoap.play().contains("<Speed>1</Speed>"))
    }

    @Test
    fun `seek uses relative time in clock format`() {
        val envelope = DlnaSoap.seek(positionMs = 3_661_000)
        assertTrue(envelope.contains("<Unit>REL_TIME</Unit>"))
        assertTrue(envelope.contains("<Target>01:01:01</Target>"), envelope)
    }

    @Test
    fun `clock time formats hours minutes seconds with padding`() {
        assertEquals("00:00:00", 0L.asClockTime())
        assertEquals("00:00:59", 59_000L.asClockTime())
        assertEquals("01:30:05", 5_405_000L.asClockTime())
        // Negative input would otherwise produce a nonsense target.
        assertEquals("00:00:00", (-5_000L).asClockTime())
    }

    @Test
    fun `didl escapes title so the envelope stays valid xml`() {
        val didl = DlnaSoap.didlLite(media)
        assertTrue(didl.contains("Title &amp; &quot;Quoted&quot; &lt;Tag&gt;"), didl)
        assertFalse(didl.contains("<Tag>"), "raw angle brackets would break parsing")
    }

    @Test
    fun `progressive mp4 advertises byte-range seeking`() {
        val didl = DlnaSoap.didlLite(media)
        assertTrue(didl.contains("DLNA.ORG_OP=01"), didl)
    }

    @Test
    fun `hls does not advertise byte-range seeking`() {
        // Claiming OP=01 for HLS makes seeking fail in ways that look like a
        // renderer bug rather than a metadata mistake.
        val hls = media.copy(
            url = "http://192.168.1.5:8909/s/1.m3u8",
            mimeType = "application/vnd.apple.mpegurl",
        )
        val didl = DlnaSoap.didlLite(hls)
        assertTrue(didl.contains("DLNA.ORG_OP=00"), didl)
    }

    @Test
    fun `duration is emitted as a clock time attribute`() {
        assertTrue(DlnaSoap.didlLite(media).contains("duration=\"01:30:30\""))
    }

    @Test
    fun `duration attribute is omitted when unknown`() {
        val didl = DlnaSoap.didlLite(media.copy(durationMs = 0))
        assertFalse(didl.contains("duration="), "empty attributes break some renderers")
    }

    @Test
    fun `subtitles are exposed through both res and the samsung namespace`() {
        val withSubs = media.copy(
            subtitles = listOf(
                CastSubtitle(
                    url = "http://192.168.1.5:8909/sub.srt",
                    label = "English",
                    language = "en",
                ),
            ),
        )
        val didl = DlnaSoap.didlLite(withSubs)

        // Samsung reads its own namespace and ignores the second res element.
        assertTrue(didl.contains("sec:CaptionInfo"), didl)
        assertTrue(didl.contains("sec:CaptionInfoEx"), didl)
        assertTrue(didl.contains("text/srt"), didl)
    }

    @Test
    fun `no subtitle elements when there are no subtitles`() {
        val didl = DlnaSoap.didlLite(media)
        assertFalse(didl.contains("CaptionInfo"))
        assertFalse(didl.contains("text/srt"))
    }

    @Test
    fun `position info parsing reads reltime`() {
        val response = "<RelTime>00:01:30</RelTime><TrackDuration>01:00:00</TrackDuration>"
        assertEquals(90_000L, DlnaSoap.parseClockTime(response, "RelTime"))
        assertEquals(3_600_000L, DlnaSoap.parseClockTime(response, "TrackDuration"))
    }

    @Test
    fun `position info parsing tolerates unsupported values`() {
        // A renderer that cannot report position answers with this rather than
        // failing, so it must not be treated as a parse error.
        assertNull(DlnaSoap.parseClockTime("<RelTime>NOT_IMPLEMENTED</RelTime>", "RelTime"))
        assertNull(DlnaSoap.parseClockTime("<RelTime></RelTime>", "RelTime"))
        assertNull(DlnaSoap.parseClockTime("", "RelTime"))
    }

    @Test
    fun `transport state is parsed`() {
        val response = "<CurrentTransportState>PLAYING</CurrentTransportState>"
        assertEquals("PLAYING", DlnaSoap.parseTransportState(response))
        assertNull(DlnaSoap.parseTransportState("<Other>PLAYING</Other>"))
    }
}
