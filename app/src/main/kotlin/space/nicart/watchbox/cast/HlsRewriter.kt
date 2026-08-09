package space.nicart.watchbox.cast

/**
 * Rewrites HLS manifests so every referenced URL routes back through the proxy.
 *
 * ## Why this is necessary
 *
 * A receiver handed an `.m3u8` fetches the manifest and then fetches each
 * segment, variant playlist and encryption key **itself**. Those follow-up
 * requests carry none of the headers the extension requires, so proxying only
 * the manifest fixes nothing: the manifest loads and every segment 403s.
 *
 * Each URI is therefore rewritten to a `/seg/<session>/<encoded-absolute-url>`
 * path on the proxy, which re-issues it upstream with the session's headers.
 *
 * ## What gets rewritten
 *
 * Three distinct shapes, all easy to miss:
 *  - bare URI lines (segments and variant playlists),
 *  - `URI="..."` attributes (`#EXT-X-KEY`, `#EXT-X-MEDIA`, `#EXT-X-MAP`),
 *  - `#EXT-X-I-FRAME-STREAM-INF` variants, whose URI is an attribute rather
 *    than a following line.
 *
 * Relative URIs are resolved against the manifest's own URL first, since a
 * rewritten absolute path would otherwise resolve against the proxy and 404.
 */
internal object HlsRewriter {

    fun rewrite(
        manifest: String,
        manifestUrl: String,
        sessionId: String,
        proxyBase: String,
    ): String {
        val base = manifestUrl.substringBeforeLast('/', "")

        return manifest.lineSequence().joinToString("\n") { rawLine ->
            val line = rawLine.trimEnd()

            when {
                line.isBlank() -> line

                // Tags may still embed a URI in an attribute.
                line.startsWith("#") -> rewriteAttributes(line, base, sessionId, proxyBase)

                // Anything else on its own line is a segment or variant URI.
                else -> proxied(line.trim(), base, sessionId, proxyBase)
            }
        }
    }

    private val uriAttribute = Regex("""URI="([^"]*)"""")

    private fun rewriteAttributes(
        line: String,
        base: String,
        sessionId: String,
        proxyBase: String,
    ): String = uriAttribute.replace(line) { match ->
        val original = match.groupValues[1]
        if (original.isBlank()) {
            match.value
        } else {
            """URI="${proxied(original, base, sessionId, proxyBase)}""""
        }
    }

    private fun proxied(
        uri: String,
        base: String,
        sessionId: String,
        proxyBase: String,
    ): String {
        // Already ours: re-wrapping would double-encode the target.
        if (uri.startsWith(proxyBase)) return uri

        val absolute = absolutise(uri, base) ?: return uri
        return "$proxyBase/seg/$sessionId/${absolute.pathEncoded()}"
    }

    /**
     * Resolves [uri] against the manifest location.
     *
     * Root-relative URIs need the scheme and host only, which is why the origin
     * is derived separately rather than by trimming the base path.
     */
    private fun absolutise(uri: String, base: String): String? = when {
        uri.startsWith("http://") || uri.startsWith("https://") -> uri

        uri.startsWith("//") -> "https:$uri"

        uri.startsWith("/") -> {
            val origin = base.originOrNull() ?: return null
            origin + uri
        }

        base.isBlank() -> null

        else -> "$base/$uri"
    }

    private fun String.originOrNull(): String? {
        val schemeEnd = indexOf("://")
        if (schemeEnd < 0) return null
        val hostEnd = indexOf('/', schemeEnd + 3)
        return if (hostEnd < 0) this else take(hostEnd)
    }
}
