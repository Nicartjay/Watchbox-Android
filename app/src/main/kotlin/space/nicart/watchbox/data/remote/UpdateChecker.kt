package space.nicart.watchbox.data.remote

import io.ktor.client.HttpClient
import space.nicart.watchbox.BuildConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Checks GitHub Releases for a newer build.
 *
 * ## Why versionName and not versionCode
 *
 * CI derives `versionCode` from the workflow run number, and the GitHub API does
 * not expose the `versionCode` inside a release asset — reading it would mean
 * downloading the whole APK first. So the comparison is done on the release tag
 * (`v2.1.0`) against `BuildConfig.VERSION_NAME`, parsed as semver. That is also
 * the value a user actually recognises.
 *
 * Draft and pre-release entries are skipped: `/releases/latest` already excludes
 * them, but the list endpoint is used as a fallback when a repository has no
 * "latest" marker, and that one does not.
 */
class UpdateChecker(
    private val client: HttpClient,
    private val currentVersion: String,
    /** Which APK to pick from a release that carries several. */
    private val formFactor: String = BuildConfig.FORM_FACTOR,
    private val owner: String = REPO_OWNER,
    private val repo: String = REPO_NAME,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        val release = fetchLatest()
            ?: return@withContext UpdateResult.Failed("Could not reach GitHub.")

        val remote = release.tagName.removePrefix("v").trim()
        if (remote.isEmpty()) {
            return@withContext UpdateResult.Failed("Release has no version tag.")
        }

        // Matched to this build's form factor. A release carries both a phone and a
        // TV APK, and taking the first .apk would install the wrong UI - a TV would
        // download the touch build and become unusable with a remote.
        val asset = selectApkAsset(release.assets.orEmpty(), formFactor)

        when {
            compareVersions(remote, currentVersion) <= 0 -> UpdateResult.UpToDate
            asset == null -> UpdateResult.Failed("Release $remote has no APK attached.")
            else -> UpdateResult.Available(
                AppUpdate(
                    versionName = remote,
                    releaseNotes = release.body.orEmpty().trim(),
                    apkUrl = asset.downloadUrl,
                    apkSizeBytes = asset.size,
                    releaseUrl = release.htmlUrl,
                ),
            )
        }
    }

    private suspend fun fetchLatest(): GithubRelease? {
        latest()?.let { return it }
        // Fallback: a repo whose newest release is a pre-release has no "latest".
        return firstStableFromList()
    }

    private suspend fun latest(): GithubRelease? =
        request("https://api.github.com/repos/$owner/$repo/releases/latest")
            ?.let { runCatching { json.decodeFromString<GithubRelease>(it) }.getOrNull() }
            ?.takeIf { !it.draft && !it.prerelease }

    private suspend fun firstStableFromList(): GithubRelease? =
        request("https://api.github.com/repos/$owner/$repo/releases?per_page=10")
            ?.let { runCatching { json.decodeFromString<List<GithubRelease>>(it) }.getOrNull() }
            ?.firstOrNull { !it.draft && !it.prerelease }

    /**
     * Recent releases, newest first, for the changelog in Settings.
     *
     * Reuses the release list this class already fetches for the update check, so it costs
     * no new endpoint and no new parsing. Pre-releases and drafts are skipped for the same
     * reason they are there: they describe builds nobody was offered.
     *
     * Returns an empty list on failure rather than throwing. A changelog is informational,
     * so an unreachable GitHub should leave the card without notes rather than break the
     * screen - and the version number above it is read locally and always correct.
     */
    suspend fun changelog(limit: Int = CHANGELOG_ENTRIES): List<ReleaseNote> {
        val body = request(
            "https://api.github.com/repos/$owner/$repo/releases?per_page=$limit",
        ) ?: return emptyList()

        return runCatching { json.decodeFromString<List<GithubRelease>>(body) }
            .getOrNull()
            .orEmpty()
            .filterNot { it.draft || it.prerelease }
            .map { release ->
                ReleaseNote(
                    // The tag is the reliable one: `name` is free text and is sometimes
                    // left empty or set to something that is not a version at all.
                    version = release.tagName.removePrefix("v").ifBlank { release.name.orEmpty() },
                    notes = release.body.orEmpty().asPlainNotes(),
                    url = release.htmlUrl,
                )
            }
            .filter { it.version.isNotBlank() }
    }


    private suspend fun request(url: String): String? = runCatching {
        val response = client.get(url) {
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (response.status.isSuccess()) response.bodyAsText() else null
    }.getOrNull()

    companion object {
        /**
         * How many past releases the changelog shows.
         *
         * Enough to cover recent history without turning Settings into a scrolling wall;
         * the full list is a tap away on GitHub.
         */
        const val CHANGELOG_ENTRIES = 5

        const val REPO_OWNER = "Nicartjay"
        const val REPO_NAME = "Watchbox-Android"

        /**
         * Compares two dotted version strings.
         *
         * Returns > 0 when [a] is newer, < 0 when older, 0 when equivalent.
         * Non-numeric suffixes are ignored so `2.1.0-debug` compares equal to
         * `2.1.0`; that matters because debug builds carry a `-debug` suffix and
         * must not be told they are perpetually out of date. Missing components
         * count as zero, so `2.1` equals `2.1.0`.
         */
        /**
         * Chooses the APK matching [formFactor].
         *
         * Named `-tv` for the television build; anything else is the phone build.
         * Falls back to the only APK present when there is exactly one, so a release
         * published before the split still updates rather than reporting no APK.
         *
         * Returns null rather than guessing when several APKs exist but none matches:
         * installing the wrong form factor is worse than reporting no update, because
         * a TV that installs the touch build cannot be navigated to fix it.
         */
        internal fun selectApkAsset(
            assets: List<GithubAsset>,
            formFactor: String,
        ): GithubAsset? {
            val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
            if (apks.isEmpty()) return null
            if (apks.size == 1) return apks.single()

            val wantsTv = formFactor.equals("tv", ignoreCase = true)
            val tvAssets = apks.filter { it.name.contains(TV_ASSET_MARKER, ignoreCase = true) }

            // Matched positively in both directions rather than by negation. Treating
            // "not TV" as "mobile" would accept any other variant that might appear in
            // a release - a wear or ABI-split APK - as the phone build.
            return if (wantsTv) {
                tvAssets.firstOrNull()
            } else {
                (apks - tvAssets.toSet()).firstOrNull { it.name.matchesMobileNaming() }
            }
        }

        /** Marks the television APK in a release. Set by the release workflow. */
        private const val TV_ASSET_MARKER = "-tv"

        /**
         * Whether a name looks like the phone APK.
         *
         * `watchbox-<version>.apk` and nothing more: a name carrying any other
         * qualifier is some third artifact, not this build.
         */
        private fun String.matchesMobileNaming(): Boolean =
            Regex("""^watchbox-[0-9][^-]*\.apk$""", RegexOption.IGNORE_CASE).matches(this)

        fun compareVersions(a: String, b: String): Int {
            val left = a.toVersionParts()
            val right = b.toVersionParts()

            for (i in 0 until maxOf(left.size, right.size)) {
                val l = left.getOrElse(i) { 0 }
                val r = right.getOrElse(i) { 0 }
                if (l != r) return l.compareTo(r)
            }
            return 0
        }

        private fun String.toVersionParts(): List<Int> = substringBefore('-')
            .substringBefore('+')
            .split('.')
            .map { part -> part.filter(Char::isDigit).toIntOrNull() ?: 0 }
    }
}

/** An update the user can install. */
data class AppUpdate(
    val versionName: String,
    val releaseNotes: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val releaseUrl: String,
)

sealed interface UpdateResult {
    data class Available(val update: AppUpdate) : UpdateResult
    data object UpToDate : UpdateResult
    data class Failed(val message: String) : UpdateResult
}

// ---------------------------------------------------------------- wire format

/** One release's notes, for the changelog card. */
data class ReleaseNote(
    val version: String,
    val notes: String,
    val url: String,
)

@Serializable
internal data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GithubAsset>? = null,
)

@Serializable
internal data class GithubAsset(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String = "",
)

/**
 * Turns a GitHub release body into something readable in a settings card.
 *
 * The notes are generated from commit subjects, so they arrive as conventional-commit
 * lines with a trailing short hash - `feat(player): subtitle timing (4d928c4)`. That is
 * right for a release page a developer reads and wrong for a card in an app: the scope
 * prefix and the hash are noise to someone who only wants to know what changed.
 *
 * So the prefix and hash are dropped, the leading `## WatchBox <version>` heading goes
 * (the version is already the label above the notes), and `-` bullets become `·` so the
 * list reads without a markdown renderer.
 *
 * Top-level and internal so it can be tested without the network.
 */
internal fun String.asPlainNotes(): String = trim()
    .lineSequence()
    .map { it.trim() }
    .filterNot { it.startsWith("## WatchBox") }
    .filterNot { it.equals("### Changes", ignoreCase = true) }
    .map { line ->
        when {
            line.startsWith("#") -> line.trimStart('#', ' ')
            line.startsWith("- ") -> "\u00B7 " + line.removePrefix("- ").asCommitSummary()
            else -> line
        }
    }
    .joinToString("\n")
    .replace(Regex("""\n{3,}"""), "\n\n")
    .trim()

/**
 * Strips the machinery from one commit subject.
 *
 * `feat(player): subtitle timing (4d928c4)` becomes `Subtitle timing`. The type and scope
 * describe where a change landed in the source, which the reader of a settings card has no
 * use for, and the hash is only meaningful next to a repository.
 *
 * Left alone when the line does not look like a conventional commit, so a hand-written note
 * survives untouched.
 */
private fun String.asCommitSummary(): String {
    val withoutHash = replace(Regex("""\s*\([0-9a-f]{7,40}\)\s*$"""), "")
    val summary = CONVENTIONAL_PREFIX.replace(withoutHash, "").trim()
    return summary.replaceFirstChar { it.uppercase() }
}

/** `type(scope):` or `type:` at the start of a commit subject. */
private val CONVENTIONAL_PREFIX =
    Regex("""^(feat|fix|docs|style|refactor|perf|test|build|ci|chore)(\([^)]*\))?!?:\s*""")
