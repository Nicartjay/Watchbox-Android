package space.nicart.watchbox.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the release-note cleanup shown in Settings.
 *
 * The notes are generated from commit subjects, so they arrive with conventional-commit
 * prefixes and short hashes - correct for a release page a developer reads, noise in a card
 * inside the app. These pin that the machinery is stripped while a hand-written line is
 * left exactly as its author wrote it.
 */
class ChangelogNotesTest {

    @Test
    fun `the duplicated version heading is dropped`() {
        // The version is already the label above the notes, so printing it again is waste.
        val notes = "## WatchBox 4.1.0\n\n### Changes\n\n- fix(player): a thing (abc1234)"

        val plain = notes.asPlainNotes()

        assertFalse("WatchBox 4.1.0" in plain)
        assertFalse("Changes" in plain)
    }

    @Test
    fun `a conventional commit loses its type and scope`() {
        val plain = "- feat(player): subtitle timing (4d928c4)".asPlainNotes()

        assertEquals("\u00B7 Subtitle timing", plain)
    }

    @Test
    fun `a scopeless commit is handled too`() {
        assertEquals("\u00B7 Bump version to 4.1.0", "- chore: bump version to 4.1.0 (fe2c8b5)".asPlainNotes())
    }

    @Test
    fun `a breaking-change marker is stripped`() {
        assertEquals("\u00B7 Rework the player", "- feat(player)!: rework the player (0000000)".asPlainNotes())
    }

    @Test
    fun `the trailing short hash is removed`() {
        val plain = "- fix: something (1a2b3c4)".asPlainNotes()

        assertFalse("1a2b3c4" in plain)
        assertTrue(plain.endsWith("Something"))
    }

    @Test
    fun `a hand-written line survives untouched`() {
        // Only conventional commits are rewritten; prose written for humans is left alone.
        val plain = "- Subtitles can now be delayed on embedded tracks".asPlainNotes()

        assertEquals("\u00B7 Subtitles can now be delayed on embedded tracks", plain)
    }

    @Test
    fun `a line that merely contains a colon is not truncated`() {
        // "Note:" is not a commit type, so nothing should be stripped.
        val plain = "- Note: this needs a restart".asPlainNotes()

        assertEquals("\u00B7 Note: this needs a restart", plain)
    }

    @Test
    fun `headings become plain lines`() {
        assertEquals("Fixed", "### Fixed".asPlainNotes())
    }

    @Test
    fun `blank input is safe`() {
        assertEquals("", "".asPlainNotes())
        assertEquals("", "   \n\n  ".asPlainNotes())
    }
}
