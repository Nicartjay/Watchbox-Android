package space.nicart.watchbox.ui.player

/**
 * One subtitle line and the window it is shown in.
 *
 * Times are milliseconds from the start of the media, matching the player's own clock.
 */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {
    fun contains(positionMs: Long): Boolean = positionMs in startMs until endMs
}

/**
 * Parses WebVTT and SubRip into a cue list.
 *
 * Exists so subtitle timing can be shifted without reloading anything. ExoPlayer reports
 * cues through `Player.Listener.onCues` only as they become current, which is enough to
 * *delay* a line but not to show one earlier - there is no way to ask it for a cue that
 * has not arrived yet. Owning the list makes an offset in either direction pure
 * arithmetic against the player position.
 *
 * WebVTT and SubRip differ only in the fractional separator (`.` versus `,`) and some
 * optional header lines, so one timing pattern covers both. ASS/SSA is deliberately not
 * handled: its timing lives inside comma-separated `Dialogue:` records alongside styling
 * and layout, and parsing it properly is a different job. Callers get an empty list and
 * must keep using the player's own rendering.
 */
object SubtitleParser {

    /** Byte-order mark, legal in both formats and not part of the first cue. */
    private const val BOM = '\uFEFF'

    /**
     * A timing line, capturing both endpoints.
     *
     * Accepts `,` or `.` before the fraction, an optional hour field, and one or two
     * fractional digits as well as three - all of which occur in files in the wild.
     * Trailing WebVTT cue settings (`align:start position:10%`) are matched and ignored.
     */
    private val TIMING = Regex(
        """^\s*(?:(\d{1,3}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})\s*-->\s*""" +
            """(?:(\d{1,3}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})""",
    )

    /** Inline markup both formats allow: `<i>`, `<b>`, `{\an8}`. */
    private val MARKUP = Regex("""</?[a-zA-Z][^>]*>|\{[^}]*}""")

    /**
     * Parses [content] into cues, or returns an empty list when the format is not one of
     * the two supported.
     *
     * Malformed cues are skipped rather than failing the whole file: a single bad
     * timestamp in a long subtitle should not cost every other line.
     */
    fun parse(content: String): List<SubtitleCue> {
        val text = content.removePrefix(BOM.toString())
        if (text.isBlank()) return emptyList()

        // An ASS/SSA file is recognisable from its section header and has no `-->` lines
        // at all, so bail rather than returning a confusingly empty result later.
        if ("[Script Info]" in text || "[Events]" in text) return emptyList()

        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = mutableListOf<SubtitleCue>()

        var index = 0
        while (index < lines.size) {
            val match = TIMING.find(lines[index])
            if (match == null) {
                index++
                continue
            }

            val start = match.startMs()
            val end = match.endMs()

            // Collect the body up to the blank line that ends the cue.
            val body = StringBuilder()
            index++
            while (index < lines.size && lines[index].isNotBlank()) {
                // A following timing line means the previous cue had no body and this is
                // the next one; do not swallow it.
                if (TIMING.containsMatchIn(lines[index])) break
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[index].trim())
                index++
            }

            val cleaned = body.toString().replace(MARKUP, "").trim()
            // A zero-length or reversed window would never match a position.
            if (cleaned.isNotEmpty() && end > start) {
                cues += SubtitleCue(startMs = start, endMs = end, text = cleaned)
            }
        }

        // Sorted so a lookup can rely on order; files are usually sorted already, but
        // merged or appended ones are not.
        return cues.sortedBy { it.startMs }
    }

    private fun MatchResult.startMs(): Long = timeMs(1, 2, 3, 4)

    private fun MatchResult.endMs(): Long = timeMs(5, 6, 7, 8)

    private fun MatchResult.timeMs(hour: Int, min: Int, sec: Int, frac: Int): Long {
        val h = groupValues[hour].toLongOrNull() ?: 0L
        val m = groupValues[min].toLongOrNull() ?: 0L
        val s = groupValues[sec].toLongOrNull() ?: 0L
        // Two-digit fractions are centiseconds, one digit is deciseconds.
        val rawFrac = groupValues[frac]
        val ms = rawFrac.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return ((h * 60 + m) * 60 + s) * 1_000 + ms
    }
}

/**
 * The cues showing at [positionMs] once [offsetMs] is applied.
 *
 * A positive offset delays the subtitles - use it when a line appears before the actor
 * speaks. Shifting the *lookup* rather than the cues themselves keeps the parsed list
 * immutable, so repeated adjustments never accumulate rounding error.
 */
fun List<SubtitleCue>.activeAt(positionMs: Long, offsetMs: Long): List<String> {
    if (isEmpty()) return emptyList()
    val lookup = positionMs - offsetMs
    return filter { it.contains(lookup) }.map { it.text }
}
