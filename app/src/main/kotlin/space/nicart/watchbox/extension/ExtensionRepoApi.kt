package space.nicart.watchbox.extension

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import space.nicart.watchbox.extension.loader.ExtensionLoader
import space.nicart.watchbox.extension.model.AvailableSource
import space.nicart.watchbox.extension.model.Extension

/**
 * Reads an extension repository index.
 *
 * A repo is a static file tree: `index.min.json` at the root, with sibling
 * `apk/` and `icon/` directories. That layout is a convention of the ecosystem,
 * so the URLs are derived rather than configured.
 *
 * Extensions outside the supported library range are dropped here rather than at
 * install time, so the browse list never offers something that cannot load.
 */
class ExtensionRepoApi(private val client: HttpClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    suspend fun fetchIndex(repoUrl: String): Result<List<Extension.Available>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = repoUrl.normalisedRepoBase()
                val response = client.get("$base/index.min.json")

                if (!response.status.isSuccess()) {
                    error("Repository returned HTTP ${response.status.value}")
                }

                // Parsed explicitly rather than via ContentNegotiation: repo
                // indexes are static files, and GitHub serves them as
                // text/plain, which makes content negotiation refuse to
                // deserialize them.
                val raw = response.bodyAsText()
                json.decodeFromString<List<RepoEntry>>(raw)
                    .mapNotNull { it.toAvailable(base) }
                    .sortedBy { it.name.lowercase() }
            }
        }

    private fun RepoEntry.toAvailable(base: String): Extension.Available? {
        // "14.46" -> 14.0
        val libVersion = version.substringBeforeLast('.').toDoubleOrNull() ?: return null
        if (libVersion < ExtensionLoader.LIB_VERSION_MIN ||
            libVersion > ExtensionLoader.LIB_VERSION_MAX
        ) {
            return null
        }

        return Extension.Available(
            name = name.removePrefix("Aniyomi: "),
            pkgName = pkg,
            versionName = version,
            versionCode = code,
            libVersion = libVersion,
            lang = lang,
            isNsfw = nsfw == 1,
            apkName = apk,
            iconUrl = "$base/icon/$pkg.png",
            apkUrl = "$base/apk/$apk",
            sources = sources.orEmpty().map {
                AvailableSource(
                    id = it.id,
                    name = it.name,
                    lang = it.lang,
                    baseUrl = it.baseUrl,
                )
            },
        )
    }

    /** Accepts either the repo root or a direct link to its index file. */
    private fun String.normalisedRepoBase(): String = trim()
        .removeSuffix("/")
        .removeSuffix("/index.min.json")
        .removeSuffix("/index.json")
        .removeSuffix("/")

    companion object {
        /**
         * Ships as the default so a fresh install has something to browse.
         * Overridable at build time and editable at runtime in Settings.
         */
        val DEFAULT_REPO: String = space.nicart.watchbox.BuildConfig.DEFAULT_REPO_URL
    }
}

// ------------------------------------------------------------- wire format

@Serializable
private data class RepoEntry(
    val name: String = "",
    val pkg: String = "",
    val apk: String = "",
    val lang: String = "",
    val code: Long = 0,
    val version: String = "",
    val nsfw: Int = 0,
    val sources: List<RepoSource>? = null,
)

@Serializable
private data class RepoSource(
    @SerialName("id") val id: Long = 0,
    val name: String = "",
    val lang: String = "",
    val baseUrl: String = "",
)
