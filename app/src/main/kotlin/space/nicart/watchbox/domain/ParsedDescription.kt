package space.nicart.watchbox.domain

/**
 * Splits a source description into readable prose and the metadata it was padded with.
 *
 * Several extensions build the description field out of markdown rather than returning
 * only a synopsis. Miruro emits a block like:
 *
 *     **Airing** • TV Short • 2 min • Spring 2025
 *     **Studio:** Lesprit
 *     **Genres:** Slice of Life
 *     **Tags:** Iyashikei, Animals, Chibi
 *     **CHARACTER:** [Natsu no Uta](https://anilist.co/anime/145291) — Music, 1 episodes
 *     [▶ Watch Trailer](https://www.youtube.com/watch?v=L4wurjkd0JM)
 *     ---
 *     The story of the series follows the everyday adventures of Koupen-chan...
 *
 * AniDB does something similar with `**Type:**` and `**Links:**`. Rendered as plain
 * text - which is what a `Text` composable does - every asterisk, bracket and URL
 * appears verbatim.
 *
 * The fields are worth keeping rather than discarding: studio and tags are real
 * information the app has nowhere else. So this separates them instead of stripping
 * them, letting the UI show the synopsis as prose and the rest as labelled data.
 *
 * Deliberately not a markdown parser. The input is a handful of predictable shapes
 * from extension code, and a general parser would still not know which paragraphs are
 * metadata and which are the summary.
 */
data class ParsedDescription(
    /** The synopsis, with markdown decoration removed. Blank when there was none. */
    val summary: String,
    /** `label` to `value`, in the order the source listed them. */
    val fields: List<Pair<String, String>> = emptyList(),
) {
    fun field(name: String): String? =
        fields.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
}

/**
 * Labels that repeat information already shown elsewhere on the detail page.
 *
 * Genres have their own chips, and the status/episode counts are in the meta line, so
 * echoing them under the synopsis is noise.
 */
private val REDUNDANT_LABELS = setOf("genres", "genre", "status", "episodes", "type")

/** A `**Label:**` or `**Label**` prefix at the start of a line. */
private val LABEL_PATTERN = Regex("""^\*\*([^*]{1,40}?)\*\*:?\s*""")

/** A markdown link: `[text](url)`. */
private val LINK_PATTERN = Regex("""\[([^]]*)]\((https?://[^)]*)\)""")

/** A horizontal rule, which sources use to separate metadata from the synopsis. */
private val RULE_PATTERN = Regex("""^\s*-{3,}\s*$""")

/** Leftover emphasis markers once labels and links are handled. */
private val EMPHASIS_PATTERN = Regex("""\*{1,3}([^*]+)\*{1,3}""")

/**
 * Parses [raw] into a summary and its metadata fields.
 *
 * Everything after a horizontal rule is treated as prose, because that is exactly what
 * the rule marks. Without one, a paragraph counts as prose when it does not open with
 * a markdown marker - the same rule [hasSummary] already uses.
 */
fun parseDescription(raw: String?): ParsedDescription {
    val text = raw?.replace("\\n", "\n")?.trim().orEmpty()
    if (text.isEmpty()) return ParsedDescription(summary = "")

    val lines = text.lines()
    val ruleIndex = lines.indexOfFirst { RULE_PATTERN.matches(it) }

    val metaLines: List<String>
    val proseLines: List<String>
    if (ruleIndex >= 0) {
        metaLines = lines.take(ruleIndex)
        proseLines = lines.drop(ruleIndex + 1)
    } else {
        // No rule: a line is metadata when it opens with a markdown marker. This is the
        // same test `hasSummary` applies per paragraph, kept in one place so the two
        // cannot disagree about what counts as prose.
        metaLines = lines.filter { it.isMarkdownMeta() }
        proseLines = lines.filterNot { it.isMarkdownMeta() }
    }

    val fields = buildList<Pair<String, String>> {
        for (line in metaLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val match = LABEL_PATTERN.find(trimmed) ?: continue
            val label = match.groupValues[1].trim().trimEnd(':')
            if (label.lowercase() in REDUNDANT_LABELS) continue

            val value = trimmed.removeRange(match.range).cleanInline()
            if (value.isBlank()) continue

            // Sources repeat a label per entry - Miruro emits one `**CHARACTER:**`
            // line each. Merged so the UI shows one row rather than a stack of
            // identically-labelled ones.
            val existing = indexOfFirst { it.first.equals(label, ignoreCase = true) }
            if (existing >= 0) {
                set(existing, this[existing].first to "${this[existing].second}, $value")
            } else {
                add(label to value)
            }
        }
    }

    val summary = proseLines
        .joinToString("\n") { it.cleanInline() }
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

    return ParsedDescription(summary = summary, fields = fields)
}

/**
 * Removes inline markdown from one line.
 *
 * A link keeps its text and loses its URL: the text is the useful half, and a bare URL
 * in running prose is unreadable and not tappable here anyway.
 */
private fun String.cleanInline(): String = this
    .replace(LINK_PATTERN) { it.groupValues[1] }
    .replace(EMPHASIS_PATTERN) { it.groupValues[1] }
    .replace("•", "·")
    .replace(Regex("""[ \t]{2,}"""), " ")
    .trim()

/**
 * True when a line opens with a markdown marker rather than prose.
 *
 * `*` covers emphasis-wrapped labels and asterisk bullets, `[` a bare link line (used
 * for trailers), `#` a heading.
 *
 * `- ` needs the trailing space: a hyphen bullet is metadata, but a horizontal rule
 * (`---`) is a separator, and prose can legitimately open with a dash. Requiring the
 * space distinguishes "- Foo" from "--- ".
 */
private fun String.isMarkdownMeta(): Boolean {
    val text = trimStart()
    return text.startsWith("*") ||
        text.startsWith("[") ||
        text.startsWith("#") ||
        text.startsWith("- ")
}
