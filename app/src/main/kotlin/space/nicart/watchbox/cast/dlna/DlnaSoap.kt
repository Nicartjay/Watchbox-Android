package space.nicart.watchbox.cast.dlna

import space.nicart.watchbox.cast.CastMedia

/**
 * Builds the SOAP envelopes and DIDL-Lite metadata a renderer expects.
 *
 * Kept separate from the transport so the XML shapes can be reasoned about — and
 * unit tested — without opening a socket.
 */
internal object DlnaSoap {

    const val AV_TRANSPORT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"

    /**
     * `SOAPAction` header value.
     *
     * The surrounding quotes are part of the value, not formatting. Renderers
     * reject the request without them.
     */
    fun soapAction(action: String): String = "\"$AV_TRANSPORT_SERVICE#$action\""

    fun setAvTransportUri(url: String, metadata: String): String = envelope(
        action = "SetAVTransportURI",
        body = """
            <InstanceID>0</InstanceID>
            <CurrentURI>${url.xmlEscaped()}</CurrentURI>
            <CurrentURIMetaData>${metadata.xmlEscaped()}</CurrentURIMetaData>
        """.trimIndent(),
    )

    fun play(): String = envelope(
        action = "Play",
        body = "<InstanceID>0</InstanceID>\n<Speed>1</Speed>",
    )

    fun pause(): String = envelope(action = "Pause", body = "<InstanceID>0</InstanceID>")

    fun stop(): String = envelope(action = "Stop", body = "<InstanceID>0</InstanceID>")

    /** Seek target is `HH:MM:SS` relative time; renderers vary on other units. */
    fun seek(positionMs: Long): String = envelope(
        action = "Seek",
        body = """
            <InstanceID>0</InstanceID>
            <Unit>REL_TIME</Unit>
            <Target>${positionMs.asClockTime()}</Target>
        """.trimIndent(),
    )

    fun getPositionInfo(): String =
        envelope(action = "GetPositionInfo", body = "<InstanceID>0</InstanceID>")

    fun getTransportInfo(): String =
        envelope(action = "GetTransportInfo", body = "<InstanceID>0</InstanceID>")

    private fun envelope(action: String, body: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                    s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            <u:$action xmlns:u="$AV_TRANSPORT_SERVICE">
              $body
            </u:$action>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    /**
     * DIDL-Lite metadata describing the item.
     *
     * Several details matter more than they look:
     *  - `DLNA.ORG_OP=01` advertises byte-range seeking. Claimed only for
     *    progressive MP4; HLS through the proxy is not seekable by range, and
     *    claiming otherwise makes seeking fail in confusing ways.
     *  - Samsung reads subtitles from its own `sec:CaptionInfo` namespace rather
     *    than the second `<res>` element, so both are emitted.
     *  - Empty attributes are omitted entirely; some renderers choke on them.
     */
    fun didlLite(media: CastMedia): String {
        val opFlag = if (media.isHls) "00" else "01"
        val protocolInfo =
            "http-get:*:${media.mimeType}:DLNA.ORG_OP=$opFlag;DLNA.ORG_CI=0;" +
                "DLNA.ORG_FLAGS=01700000000000000000000000000000"

        val subtitle = media.subtitles.firstOrNull()

        return buildString {
            append("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """)
            append("""xmlns:dc="http://purl.org/dc/elements/1.1/" """)
            append("""xmlns:sec="http://www.sec.co.kr/" """)
            append("""xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""")
            append("""<item id="1" parentID="0" restricted="1">""")

            subtitle?.let {
                append("""<sec:CaptionInfo sec:type="srt">${it.url.xmlEscaped()}</sec:CaptionInfo>""")
                append("""<sec:CaptionInfoEx sec:type="srt">${it.url.xmlEscaped()}</sec:CaptionInfoEx>""")
            }

            append("<dc:title>${media.title.xmlEscaped()}</dc:title>")
            append("<upnp:class>object.item.videoItem.movie</upnp:class>")

            media.artworkUrl?.let {
                append("<upnp:albumArtURI>${it.xmlEscaped()}</upnp:albumArtURI>")
            }

            append("""<res protocolInfo="${protocolInfo.xmlEscaped()}"""")
            media.durationMs.takeIf { it > 0 }?.let {
                append(""" duration="${it.asClockTime()}"""")
            }
            append(">${media.url.xmlEscaped()}</res>")

            subtitle?.let {
                append("""<res protocolInfo="http-get:*:text/srt:*">""")
                append(it.url.xmlEscaped())
                append("</res>")
            }

            append("</item></DIDL-Lite>")
        }
    }

    /** Parses `<RelTime>` / `<TrackDuration>` out of a GetPositionInfo response. */
    fun parseClockTime(response: String, tag: String): Long? {
        val raw = Regex("<$tag>(.*?)</$tag>", RegexOption.IGNORE_CASE)
            .find(response)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?: return null

        // "NOT_IMPLEMENTED" is a valid answer from renderers that cannot report.
        val parts = raw.split(':')
        if (parts.size != 3) return null

        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = parts[2].substringBefore('.').toLongOrNull() ?: return null

        return ((hours * 3600) + (minutes * 60) + seconds) * 1000
    }

    fun parseTransportState(response: String): String? =
        Regex("<CurrentTransportState>(.*?)</CurrentTransportState>", RegexOption.IGNORE_CASE)
            .find(response)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
}

/** `HH:MM:SS`, which is the only duration form renderers reliably accept. */
internal fun Long.asClockTime(): String {
    val total = (this / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
}

internal fun String.xmlEscaped(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

/** True when the URL is an HLS playlist, which affects the seek advertisement. */
internal val CastMedia.isHls: Boolean
    get() = url.contains(".m3u8", ignoreCase = true) ||
        mimeType.contains("mpegurl", ignoreCase = true)
