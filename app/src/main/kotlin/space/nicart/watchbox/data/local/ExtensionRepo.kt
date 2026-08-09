package space.nicart.watchbox.data.local

import kotlinx.serialization.Serializable
import space.nicart.watchbox.extension.ExtensionRepoApi

/**
 * One extension repository the user has configured.
 *
 * Identified by its normalised [url] rather than a generated id: the URL is what
 * makes a repository unique, and keying on it means adding the same repo twice is
 * naturally a no-op instead of producing two rows that fetch identical indexes.
 *
 * [enabled] is stored rather than implied by presence in the list so a repository
 * can be switched off without losing its URL - the common case is temporarily
 * silencing a repo that is down, not deleting it.
 */
@Serializable
data class ExtensionRepo(
    val url: String,
    val enabled: Boolean = true,
) {

    /**
     * Short label for the UI.
     *
     * Repository indexes carry no name of their own, so one is derived from the
     * URL. GitHub-hosted repos - which is nearly all of them - put the owner and
     * project in the path, and that pair is far more recognisable than the raw
     * URL or the bare host (`raw.githubusercontent.com` for every one of them).
     */
    val displayName: String
        get() = runCatching {
            val withoutScheme = url
                .removePrefix("https://")
                .removePrefix("http://")

            val segments = withoutScheme.split('/').filter { it.isNotBlank() }
            val host = segments.firstOrNull().orEmpty()

            when {
                // raw.githubusercontent.com/<owner>/<project>/<branch>
                host.contains("githubusercontent") || host.contains("github") ->
                    segments.drop(1).take(2).joinToString("/").ifBlank { host }

                else -> host
            }
        }.getOrDefault(url)

    companion object {

        /**
         * Accepts either a repository root or a direct link to its index file.
         *
         * Normalising on the way in means the same repository pasted in three
         * different forms collapses to one entry, so duplicate detection works.
         */
        fun normaliseUrl(raw: String): String = raw.trim()
            .removeSuffix("/")
            .removeSuffix("/index.min.json")
            .removeSuffix("/index.json")
            .removeSuffix("/")

        /** The repository list a fresh install starts with. */
        val DEFAULT: List<ExtensionRepo> =
            listOf(ExtensionRepo(url = ExtensionRepoApi.DEFAULT_REPO))
    }
}
