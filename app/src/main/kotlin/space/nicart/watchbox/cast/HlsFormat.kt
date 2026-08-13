package space.nicart.watchbox.cast

/**
 * Works out which segment format an HLS stream uses.
 *
 * ## Why the receiver needs telling
 *
 * A Cast receiver assumes HLS segments are MPEG2-TS unless the sender says otherwise. Handed
 * fragmented MP4 (fMP4/CMAF) instead, it loads the manifest, reports the duration, downloads
 * segments - and then sits in `PLAYER_STATE_LOADING` for ever without decoding a frame. There is
 * no error: `load()` succeeds, `mediaError` is null, and the only symptom is a receiver stuck at
 * 0:00 while bytes stream in.
 *
 * `MediaInfo.setHlsSegmentFormat` / `setHlsVideoSegmentFormat` are the documented remedy, so the
 * format has to be established before the load request is built.
 *
 * ## How it is detected
 *
 * `#EXT-X-MAP` is the definitive marker: an initialisation segment only exists for fMP4, and TS
 * playlists never carry one. Segment file extensions are the fallback, since a few packagers omit
 * the tag.
 *
 * A master playlist lists other playlists rather than segments, so the tag lives one level down -
 * [firstVariantUri] exists to follow that single hop.
 */
internal object HlsFormat {

    /** Cast's identifier for fragmented MP4 segments. */
    const val FMP4 = "fmp4"

    /** Cast's identifier for classic MPEG2-TS segments. */
    const val TS = "ts"

    /** Cast's video-specific identifier for fragmented MP4. */
    const val VIDEO_FMP4 = "fmp4"

    /** Cast's video-specific identifier for MPEG2-TS. */
    const val VIDEO_MPEG2_TS = "mpeg2_ts"

    /**
     * The segment format [manifest] describes, or null when it cannot be told.
     *
     * Null is returned rather than a guess: telling the receiver "TS" for a stream that is
     * really fMP4 fails exactly as badly as saying nothing, so an unknown format is left to the
     * receiver's own default.
     */
    fun segmentFormat(manifest: String): String? {
        // A master playlist describes no segments of its own, so nothing can be concluded here.
        if (isMaster(manifest)) return null

        if (manifest.contains("#EXT-X-MAP", ignoreCase = true)) return FMP4

        val segments = manifest.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .take(SEGMENTS_SAMPLED)
            .map { it.substringBefore('?').lowercase() }
            .toList()

        if (segments.isEmpty()) return null

        return when {
            segments.any { it.endsWith(".m4s") || it.endsWith(".mp4") || it.endsWith(".cmfv") } ->
                FMP4

            segments.any { it.endsWith(".ts") } -> TS

            else -> null
        }
    }

    /** The matching `hlsVideoSegmentFormat` for a [segmentFormat] result. */
    fun videoSegmentFormat(segmentFormat: String?): String? = when (segmentFormat) {
        FMP4 -> VIDEO_FMP4
        TS -> VIDEO_MPEG2_TS
        else -> null
    }

    /** True when [manifest] is a master playlist listing variant streams. */
    fun isMaster(manifest: String): Boolean =
        manifest.contains("#EXT-X-STREAM-INF", ignoreCase = true)

    /**
     * The first variant playlist URI in a master, so its format can be inspected.
     *
     * Only the bare-URI form is handled, which is where `#EXT-X-STREAM-INF` always puts it. The
     * `URI="..."` attribute belongs to `#EXT-X-MEDIA` and `#EXT-X-I-FRAME-STREAM-INF`, neither of
     * which is a playable video variant.
     */
    fun firstVariantUri(manifest: String): String? {
        val lines = manifest.lines()

        lines.forEachIndexed { index, line ->
            if (!line.trim().startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) return@forEachIndexed

            // The URI is the next line that is neither blank nor a tag.
            for (next in index + 1 until lines.size) {
                val candidate = lines[next].trim()
                if (candidate.isEmpty() || candidate.startsWith("#")) continue
                return candidate
            }
        }

        return null
    }

    /**
     * Resolves [uri] against the playlist it was found in.
     *
     * Kept here rather than reusing [HlsRewriter]'s copy because that one resolves against a
     * pre-split base path; this takes the full manifest URL, which is what the caller has.
     */
    fun resolve(uri: String, manifestUrl: String): String = when {
        uri.startsWith("http://") || uri.startsWith("https://") -> uri
        uri.startsWith("//") -> "https:$uri"

        uri.startsWith("/") -> {
            val schemeEnd = manifestUrl.indexOf("://")
            if (schemeEnd < 0) {
                uri
            } else {
                val hostEnd = manifestUrl.indexOf('/', schemeEnd + 3)
                val origin = if (hostEnd < 0) manifestUrl else manifestUrl.take(hostEnd)
                origin + uri
            }
        }

        else -> manifestUrl.substringBeforeLast('/', "").let { base ->
            if (base.isEmpty()) uri else "$base/$uri"
        }
    }

    /** How many segment lines are examined before giving up on the extension fallback. */
    private const val SEGMENTS_SAMPLED = 20
}
