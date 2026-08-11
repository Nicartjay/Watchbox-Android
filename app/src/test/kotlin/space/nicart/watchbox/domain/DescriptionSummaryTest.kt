package space.nicart.watchbox.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for deciding whether a source description actually contains a summary.
 *
 * Sources do not agree on what `description` means. Some return prose; others build it
 * out of markdown metadata - AniDB emits `**Type:** TV | **Rating:** 7.4`, then
 * `**Alternative Titles:**` and `**Links:**`, and appends the synopsis only when the
 * page had one. The detail page previously tested `isNotBlank`, so a metadata-only
 * description counted as present: it suppressed the TMDB overview fallback *and*
 * printed the raw `**Type:** ...` block where the summary belongs.
 *
 * The negative cases are the point. Reporting a summary that is not there is the bug
 * being fixed, and it is invisible until a source with no synopsis is opened.
 */
class DescriptionSummaryTest {

    @Test
    fun `plain prose is a summary`() {
        assertTrue("A high schooler finds a mysterious notebook.".hasSummary())
    }

    @Test
    fun `prose after a metadata line is a summary`() {
        val description = "**Type:** TV Series | **Rating:** 7.45\n\n" +
            "A high schooler finds a mysterious notebook."

        assertTrue(description.hasSummary())
    }

    @Test
    fun `metadata alone is not a summary`() {
        // AniDB's shape when the page carries no synopsis.
        val description = "**Type:** TV Series | **Season:** Winter 2024 | " +
            "**Rating:** 7.45\n\n**Alternative Titles:**\n- Foo\n\n" +
            "**Links:** anidb.net"

        assertFalse(description.hasSummary())
    }

    @Test
    fun `a blank description is not a summary`() {
        assertFalse("".hasSummary())
        assertFalse("   \n\n  ".hasSummary())
    }

    @Test
    fun `a markdown heading alone is not a summary`() {
        assertFalse("# Overview\n\n## Details".hasSummary())
    }

    @Test
    fun `escaped newlines separate paragraphs too`() {
        // Some sources emit literal backslash-n rather than real newlines, so the
        // metadata block and the synopsis arrive as one physical line.
        val description = "**Type:** Movie\\n\\nA quiet film about nothing."

        assertTrue(description.hasSummary())
    }

    @Test
    fun `indented metadata is still metadata`() {
        // Leading whitespace must not smuggle a metadata block past the check.
        assertFalse("   **Type:** TV Series".hasSummary())
    }
}
