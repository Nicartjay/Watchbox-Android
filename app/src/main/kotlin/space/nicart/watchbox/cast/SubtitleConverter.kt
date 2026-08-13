package space.nicart.watchbox.cast

/**
 * Converts SubRip to WebVTT.
 *
 * A Cast receiver only accepts WebVTT, and the catalogues the subtitle search uses serve almost
 * exclusively SubRip. Without this, a downloaded subtitle appears in the receiver's track menu
 * and shows nothing when selected - which reads as a broken feature rather than an unsupported
 * format.
 *
 * The two formats are close enough that this is a small transformation rather than a parser:
 *
 *  - WebVTT requires a `WEBVTT` header line.
 *  - Timestamps use `.` for fractional seconds; SubRip uses `,`.
 *  - WebVTT hours are optional but permitted, so `00:01:02.500` passes through unchanged.
 *  - A UTF-8 BOM is legal in SubRip and rejected by some WebVTT parsers.
 *
 * Cue numbers are kept: WebVTT allows an optional identifier before the timing line, and
 * dropping them would mean rebuilding the block structure for no benefit.
 */
internal object SubtitleConverter {

    /** Byte-order mark, legal in SubRip and unwelcome in WebVTT. */
    private const val BOM = '\uFEFF'

    /** Matches a SubRip timing line, capturing both endpoints. */
    private val timing = Regex(
        """^\s*(\d{1,2}:\d{2}:\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}:\d{2}:\d{2})[,.](\d{1,3})(.*)$""",
    )

    /**
     * True when [name] looks like SubRip and therefore needs converting.
     *
     * Matched on the extension because that is all a URL offers, and the subtitle search records
     * the format in the cached filename precisely so this decision can be made later.
     */
    fun needsConversion(name: String): Boolean {
        val path = name.substringBefore('?').lowercase()
        return path.endsWith(".srt") || path.endsWith(".sub")
    }

    /**
     * Rewrites [subrip] as WebVTT.
     *
     * Content that is already WebVTT is returned untouched, so this is safe to apply to anything
     * whose format is uncertain.
     */
    fun toWebVtt(subrip: String): String {
        val text = subrip.removePrefix(BOM.toString())

        if (text.trimStart().startsWith("WEBVTT")) return text

        val body = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { line ->
                val match = timing.matchEntire(line)
                    ?: return@map line

                val (start, startMs, end, endMs, trailing) = match.destructured

                // Padded to milliseconds: WebVTT requires exactly three fractional digits,
                // while SubRip files in the wild sometimes carry two.
                "$start.${startMs.padEnd(3, '0')} --> $end.${endMs.padEnd(3, '0')}$trailing"
            }
            .joinToString("\n")

        return "WEBVTT\n\n$body"
    }
}
