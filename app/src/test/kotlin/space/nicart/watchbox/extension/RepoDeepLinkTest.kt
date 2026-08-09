package space.nicart.watchbox.extension

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for `aniyomi://add-repo` link parsing.
 *
 * Unit-tested because this is the primary way a repository gets added now that none
 * ships by default, and because the payload is itself a URL containing `://`, `/`
 * and `?` - the exact characters a naive parser mishandles. A link that silently
 * fails to parse looks like the app ignored the tap, with nothing to diagnose.
 *
 * The rejection cases matter as much as the happy path: this entry point is
 * reachable from any web page, so it must not accept a URL the app would then fetch.
 */
class RepoDeepLinkTest {

    private val repoUrl = "https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json"

    // ------------------------------------------------------------- accepting

    @Test
    fun `the documented aniyomi link is parsed`() {
        assertEquals(repoUrl, RepoDeepLink.parse("aniyomi://add-repo?url=$repoUrl"))
    }

    @Test
    fun `a percent-encoded url is decoded`() {
        val encoded = "https%3A%2F%2Fexample.com%2Frepo%2Findex.min.json"
        assertEquals(
            "https://example.com/repo/index.min.json",
            RepoDeepLink.parse("aniyomi://add-repo?url=$encoded"),
        )
    }

    @Test
    fun `the tachiyomi and watchbox schemes are accepted too`() {
        // Forks publish links under their own scheme; all mean the same thing.
        assertEquals(repoUrl, RepoDeepLink.parse("tachiyomi://add-repo?url=$repoUrl"))
        assertEquals(repoUrl, RepoDeepLink.parse("watchbox://add-repo?url=$repoUrl"))
    }

    @Test
    fun `the scheme and host are matched case-insensitively`() {
        assertEquals(repoUrl, RepoDeepLink.parse("ANIYOMI://ADD-REPO?url=$repoUrl"))
    }

    @Test
    fun `the url parameter is found among others, in any position`() {
        assertEquals(
            "https://example.com/repo",
            RepoDeepLink.parse("aniyomi://add-repo?foo=1&url=https://example.com/repo&bar=2"),
        )
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        // Links pasted from chat apps often carry it.
        assertEquals(repoUrl, RepoDeepLink.parse("  aniyomi://add-repo?url=$repoUrl  "))
    }

    @Test
    fun `a plain http url is accepted for self-hosted repositories`() {
        assertEquals(
            "http://192.168.1.10:8080/repo",
            RepoDeepLink.parse("aniyomi://add-repo?url=http://192.168.1.10:8080/repo"),
        )
    }

    @Test
    fun `a trailing host slash is tolerated`() {
        assertEquals(repoUrl, RepoDeepLink.parse("aniyomi://add-repo/?url=$repoUrl"))
    }

    // ------------------------------------------------------------- rejecting

    @Test
    fun `null and blank input are rejected`() {
        assertNull(RepoDeepLink.parse(null))
        assertNull(RepoDeepLink.parse(""))
        assertNull(RepoDeepLink.parse("   "))
    }

    @Test
    fun `an unrelated scheme is rejected`() {
        // Otherwise any http link the user opened would be treated as a repository.
        assertNull(RepoDeepLink.parse("https://add-repo?url=$repoUrl"))
        assertNull(RepoDeepLink.parse("myapp://add-repo?url=$repoUrl"))
    }

    @Test
    fun `a different host on a known scheme is rejected`() {
        // aniyomi:// carries other actions; only add-repo is ours to act on.
        assertNull(RepoDeepLink.parse("aniyomi://add-anime?url=$repoUrl"))
    }

    @Test
    fun `a link with no query or no url parameter is rejected`() {
        assertNull(RepoDeepLink.parse("aniyomi://add-repo"))
        assertNull(RepoDeepLink.parse("aniyomi://add-repo?"))
        assertNull(RepoDeepLink.parse("aniyomi://add-repo?other=1"))
        assertNull(RepoDeepLink.parse("aniyomi://add-repo?url="))
    }

    @Test
    fun `a non-http payload is rejected`() {
        // The URL gets fetched, so a file or content URI must never be accepted
        // just because it arrived wrapped in a valid-looking link.
        assertNull(RepoDeepLink.parse("aniyomi://add-repo?url=file:///data/local/tmp/x"))
        assertNull(RepoDeepLink.parse("aniyomi://add-repo?url=content://media/external/file/1"))
        assertNull(RepoDeepLink.parse("aniyomi://add-repo?url=javascript:alert(1)"))
        assertNull(RepoDeepLink.parse("aniyomi://add-repo?url=/relative/path"))
    }

    @Test
    fun `an encoded non-http payload is also rejected`() {
        // Encoding must not be a way past the scheme check.
        assertNull(RepoDeepLink.parse("aniyomi://add-repo?url=file%3A%2F%2F%2Fetc%2Fpasswd"))
    }
}
