package space.nicart.watchbox.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Online subtitle search.
 *
 * Two providers, because neither is sufficient alone:
 *
 * - [SubtitleProvider.OPEN_SUBTITLES_LEGACY] needs no key and works out of the box, which is
 *   the only reason it is the default. It indexes by IMDb id, so it can only search titles
 *   TMDB has an IMDb match for, and its download links are gzipped.
 * - [SubtitleProvider.OPEN_SUBTITLES_API] is the modern REST API. Better coverage and it
 *   accepts TMDB ids directly, but it requires a free key and caps downloads per day.
 *
 * Parsing follows the house convention of an explicit local [Json] and `decodeFromString`
 * rather than content negotiation: the legacy endpoint's content type is not reliably
 * `application/json`, which makes negotiation refuse the body outright.
 */
class SubtitleApi(private val client: HttpClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * Finds subtitles for one episode, or for a film when [season] and [episode] are null.
     *
     * Returns an empty list rather than failing: no subtitles is an ordinary outcome, and the
     * caller shows the same "nothing found" either way. Genuine transport failures are logged.
     */
    suspend fun search(query: SubtitleQuery, provider: SubtitleProvider, apiKey: String): List<SubtitleResult> =
        runCatching {
            when (provider) {
                SubtitleProvider.OPEN_SUBTITLES_LEGACY -> searchLegacy(query)
                SubtitleProvider.OPEN_SUBTITLES_API -> searchRest(query, apiKey)
            }
        }.onFailure {
            android.util.Log.w(TAG, "subtitle search failed: ${it::class.java.simpleName}: ${it.message}")
        }.getOrDefault(emptyList())

    /**
     * The keyless endpoint, keyed by IMDb id.
     *
     * The path is built from segments in a fixed order because this API is positional, not
     * query-based: `episode-1/imdbid-tt0944947/season-1/sublanguageid-eng`. Sending the
     * segments in the wrong order returns results for the wrong thing rather than an error.
     */
    private suspend fun searchLegacy(query: SubtitleQuery): List<SubtitleResult> {
        val imdb = query.imdbId?.removePrefix("tt")?.takeIf { it.isNotBlank() } ?: return emptyList()

        val segments = buildList {
            if (query.episode != null) add("episode-${query.episode}")
            add("imdbid-$imdb")
            if (query.season != null) add("season-${query.season}")
            add("sublanguageid-${query.language.toIso639_2()}")
        }

        val response = client.get("$LEGACY_BASE/search/${segments.joinToString("/")}") {
            // This endpoint rejects a default Ktor agent. Any stable identifier works.
            header("User-Agent", LEGACY_AGENT)
        }
        if (!response.status.isSuccess()) return emptyList()

        return json.decodeFromString<List<LegacyDto>>(response.bodyAsText())
            .mapNotNull { it.toResult() }
            .ranked()
    }

    /** The modern REST API, keyed by TMDB id so it needs no IMDb match. */
    private suspend fun searchRest(query: SubtitleQuery, apiKey: String): List<SubtitleResult> {
        if (apiKey.isBlank()) return emptyList()

        val params = buildList {
            query.tmdbId?.let { add("tmdb_id=$it") }
            if (query.tmdbId == null) query.imdbId?.let { add("imdb_id=${it.removePrefix("tt")}") }
            query.season?.let { add("season_number=$it") }
            query.episode?.let { add("episode_number=$it") }
            add("languages=${query.language.lowercase()}")
        }
        if (params.isEmpty()) return emptyList()

        val response = client.get("$REST_BASE/subtitles?${params.joinToString("&")}") {
            header("Api-Key", apiKey)
            header("User-Agent", REST_AGENT)
        }
        if (!response.status.isSuccess()) return emptyList()

        return json.decodeFromString<RestEnvelope>(response.bodyAsText())
            .data.orEmpty()
            .mapNotNull { it.toResult() }
            .ranked()
    }

    /**
     * Downloads [result] into [dir] and returns the file.
     *
     * The extension is preserved from the provider's stated format because the player infers
     * the MIME type from the path; a subtitle saved without one is treated as WebVTT and an
     * SRT would silently fail to parse.
     *
     * Gzip is detected from the payload's magic bytes rather than the URL. The legacy provider
     * serves `.gz` links, but not always, and a wrongly-decompressed file is unreadable.
     */
    suspend fun download(
        result: SubtitleResult,
        dir: File,
        apiKey: String = "",
    ): File? = runCatching {
        dir.mkdirs()

        // The REST provider does not expose a direct link: the file id has to be exchanged
        // for a short-lived URL through an authenticated POST first.
        val url = when {
            result.fileId != null -> resolveRestLink(result.fileId, apiKey) ?: return null
            else -> result.downloadUrl
        }

        val response = client.get(url) { header("User-Agent", LEGACY_AGENT) }
        if (!response.status.isSuccess()) return null

        val target = File(dir, result.cacheFileName())
        val bytes = response.bodyAsChannel().toInputStream().use { it.readBytes() }
        if (bytes.isEmpty()) return null

        val decoded = if (bytes.isGzip()) {
            GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        } else {
            bytes
        }
        if (decoded.isEmpty()) return null

        target.writeBytes(decoded)
        target
    }.onFailure {
        android.util.Log.w(TAG, "subtitle download failed: ${it::class.java.simpleName}")
    }.getOrNull()

    /**
     * Fetches a subtitle URL as text.
     *
     * Used for timing adjustment, which needs the cue list rather than a file. Gzip is
     * detected from the payload's magic bytes for the same reason [download] does it: the
     * legacy provider serves compressed files without always saying so.
     */
    suspend fun fetchText(url: String): String {
        val response = client.get(url) { header("User-Agent", LEGACY_AGENT) }
        if (!response.status.isSuccess()) return ""

        val bytes = response.bodyAsChannel().toInputStream().use { it.readBytes() }
        if (bytes.isEmpty()) return ""

        val decoded = if (bytes.isGzip()) {
            GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        } else {
            bytes
        }
        return decoded.toString(Charsets.UTF_8)
    }

    /** Exchanges a REST file id for a temporary download URL. */
    private suspend fun resolveRestLink(fileId: Long, apiKey: String): String? {
        if (apiKey.isBlank()) return null

        val response = client.post("$REST_BASE/download") {
            header("Api-Key", apiKey)
            header("User-Agent", REST_AGENT)
            contentType(ContentType.Application.Json)
            setBody("""{"file_id":$fileId}""")
        }
        if (!response.status.isSuccess()) return null

        return json.decodeFromString<RestDownload>(response.bodyAsText())
            .link
            ?.takeIf { it.isNotBlank() }
    }

    private fun List<SubtitleResult>.ranked(): List<SubtitleResult> = rankSubtitles(this)

    private fun ByteArray.isGzip(): Boolean = isGzipped(this)

    @Serializable
    private data class LegacyDto(
        @SerialName("IDSubtitleFile") val id: String? = null,
        @SerialName("SubFileName") val fileName: String? = null,
        @SerialName("SubDownloadLink") val downloadLink: String? = null,
        @SerialName("SubFormat") val format: String? = null,
        @SerialName("ISO639") val iso639: String? = null,
        @SerialName("LanguageName") val languageName: String? = null,
        @SerialName("SubDownloadsCnt") val downloads: String? = null,
        @SerialName("SubHearingImpaired") val hearingImpaired: String? = null,
    ) {
        fun toResult(): SubtitleResult? {
            val url = downloadLink?.takeIf { it.isNotBlank() } ?: return null
            return SubtitleResult(
                id = id ?: url,
                name = fileName?.takeIf { it.isNotBlank() } ?: languageName ?: "Subtitle",
                language = iso639?.takeIf { it.isNotBlank() } ?: "",
                languageName = languageName.orEmpty(),
                downloadUrl = url,
                format = format?.lowercase()?.takeIf { it.isNotBlank() } ?: "srt",
                downloads = downloads?.toLongOrNull() ?: 0L,
                hearingImpaired = hearingImpaired == "1",
            )
        }
    }

    @Serializable
    private data class RestEnvelope(val data: List<RestDto>? = null)

    @Serializable
    private data class RestDto(val id: String? = null, val attributes: RestAttributes? = null) {
        fun toResult(): SubtitleResult? {
            val attrs = attributes ?: return null
            // The REST API does not return a direct link: files are downloaded by file id
            // through a separate authenticated call. Represented as an id here so the
            // download step can tell the two providers apart.
            val fileId = attrs.files?.firstOrNull()?.fileId ?: return null
            return SubtitleResult(
                id = id ?: fileId.toString(),
                name = attrs.release?.takeIf { it.isNotBlank() } ?: "Subtitle",
                language = attrs.language.orEmpty(),
                languageName = attrs.language.orEmpty(),
                downloadUrl = "",
                fileId = fileId,
                format = attrs.format?.lowercase()?.takeIf { it.isNotBlank() } ?: "srt",
                downloads = attrs.downloadCount ?: 0L,
                hearingImpaired = attrs.hearingImpaired == true,
            )
        }
    }

    @Serializable
    private data class RestAttributes(
        val language: String? = null,
        val release: String? = null,
        val format: String? = null,
        @SerialName("download_count") val downloadCount: Long? = null,
        @SerialName("hearing_impaired") val hearingImpaired: Boolean? = null,
        val files: List<RestFile>? = null,
    )

    @Serializable
    private data class RestFile(@SerialName("file_id") val fileId: Long? = null)

    @Serializable
    private data class RestDownload(val link: String? = null)

    companion object {
        private const val TAG = "WbSubtitles"

        private const val LEGACY_BASE = "https://rest.opensubtitles.org"
        private const val REST_BASE = "https://api.opensubtitles.com/api/v1"

        /** The legacy endpoint refuses requests without a recognisable agent. */
        private const val LEGACY_AGENT = "WatchBox"
        private const val REST_AGENT = "WatchBox v1"

        private const val MAX_RESULTS = 30

        /**
         * Maps a two-letter code to the three-letter one the legacy API expects.
         *
         * Only the languages a subtitle catalogue actually carries in volume. An unknown code
         * is passed through unchanged, which the API treats as no language filter rather than
         * an error - better than dropping the search.
         */
        internal val ISO_639_2 = mapOf(
            "en" to "eng", "es" to "spa", "fr" to "fre", "de" to "ger", "it" to "ita",
            "pt" to "por", "ru" to "rus", "ja" to "jpn", "ko" to "kor", "zh" to "chi",
            "ar" to "ara", "hi" to "hin", "id" to "ind", "th" to "tha", "vi" to "vie",
            "tr" to "tur", "pl" to "pol", "nl" to "dut", "sv" to "swe", "da" to "dan",
            "fi" to "fin", "no" to "nor", "cs" to "cze", "el" to "ell", "he" to "heb",
            "hu" to "hun", "ro" to "rum", "uk" to "ukr", "fa" to "per", "ms" to "may",
        )

        internal fun String.toIso639_2(): String =
            ISO_639_2[lowercase()] ?: lowercase()

        /** How many results a search returns at most. */
        internal const val MAX_RESULTS_LIMIT = MAX_RESULTS
    }
}

/**
 * Orders results so the most useful is first, and caps the list.
 *
 * Download count is the ranking signal rather than the star rating: ratings are sparse and a
 * single 10/10 vote outranks a release with thousands of satisfied downloads. Hearing-impaired
 * versions sort last - they are correct subtitles, but they carry sound annotations most
 * viewers do not want unless they asked for them.
 *
 * Top-level rather than a private method so the ordering can be tested directly: it decides
 * what the user sees first, which is the whole value of the list.
 */
internal fun rankSubtitles(
    results: List<SubtitleResult>,
    limit: Int = SubtitleApi.MAX_RESULTS_LIMIT,
): List<SubtitleResult> =
    results.sortedWith(
        compareBy<SubtitleResult> { it.hearingImpaired }
            .thenByDescending { it.downloads },
    ).take(limit)

/**
 * True when [bytes] starts with the gzip magic number.
 *
 * Checked from the payload rather than the URL: the legacy provider serves `.gz` links but not
 * invariably, and decompressing something that is not compressed - or failing to decompress
 * something that is - leaves an unreadable file either way.
 */
internal fun isGzipped(bytes: ByteArray): Boolean =
    bytes.size > 1 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

/** Which catalogue to search. */
enum class SubtitleProvider {
    /** Keyless, indexed by IMDb id. The default because it needs no setup. */
    OPEN_SUBTITLES_LEGACY,

    /** Modern REST API. Needs a free key, accepts TMDB ids. */
    OPEN_SUBTITLES_API,
}

/**
 * What to search for.
 *
 * Carries both ids because the two providers index differently, and whichever is missing is
 * simply not used. [season] and [episode] are null for a film.
 */
data class SubtitleQuery(
    val imdbId: String?,
    val tmdbId: Int?,
    val season: Int?,
    val episode: Int?,
    val language: String,
    val title: String = "",
) {
    /** True when neither provider could do anything with this query. */
    val isUnusable: Boolean get() = imdbId.isNullOrBlank() && tmdbId == null
}

/** One search hit, before it has been downloaded. */
data class SubtitleResult(
    val id: String,
    /** The release name, e.g. `Show.S01E01.1080p.WEB-DL.srt`. Shown to the user. */
    val name: String,
    val language: String,
    val languageName: String,
    /** Direct link. Empty for the REST provider, which resolves [fileId] instead. */
    val downloadUrl: String,
    /** REST file id, exchanged for a temporary link at download time. Null for legacy. */
    val fileId: Long? = null,
    /** `srt`, `vtt`, `ass`, ... Determines the cached file's extension. */
    val format: String,
    val downloads: Long,
    val hearingImpaired: Boolean,
) {
    /**
     * A filename unique to this result, keeping the format extension.
     *
     * The id is the filename rather than the release name: release names contain characters
     * that are not safe in a path, and two providers can return the same name for different
     * files.
     */
    fun cacheFileName(): String = "sub-${id.filter { it.isLetterOrDigit() }}.${format.ifBlank { "srt" }}"
}
