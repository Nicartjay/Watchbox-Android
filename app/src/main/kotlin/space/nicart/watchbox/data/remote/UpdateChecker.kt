package space.nicart.watchbox.data.remote

import io.ktor.client.HttpClient
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

        // The APK is matched by extension rather than by exact name so a rename
        // in the release workflow does not silently break updates.
        val asset = release.assets.orEmpty()
            .firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

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

    private suspend fun request(url: String): String? = runCatching {
        val response = client.get(url) {
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (response.status.isSuccess()) response.bodyAsText() else null
    }.getOrNull()

    companion object {
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

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GithubAsset>? = null,
)

@Serializable
private data class GithubAsset(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String = "",
)
