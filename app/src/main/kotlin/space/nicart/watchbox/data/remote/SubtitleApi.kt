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
                SubtitleProvider.SUBS_BRIGHT -> searchBright(query)
                SubtitleProvider.VIDFAST_WYZIE -> searchWyzie(query)
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

        // Refused rather than sent. An unrecognised language used to be lowercased and pasted
        // straight into the path, which for a source's own track label ("English",
        // "Portuguese (Brazil)") produced a segment the endpoint does not know - or, with a
        // space or bracket in it, a URL that would not parse at all.
        val lang = query.language.toIso639_2()
        if (lang.isBlank()) return emptyList()

        val segments = buildList {
            if (query.episode != null) add("episode-${query.episode}")
            add("imdbid-$imdb")
            if (query.season != null) add("season-${query.season}")
            add("sublanguageid-$lang")
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

    /**
     * The keyless curated list, keyed by either id.
     *
     * No language parameter: the service ignores one and returns every language it has, so the
     * filtering is done here. That is acceptable because the reply is one entry per language -
     * around twenty for a film, forty for an episode - rather than the eighty-odd competing
     * releases the aggregator sends.
     *
     * A series must carry season and episode. Omitting them answers 404 rather than falling
     * back to the title, which is worth relying on: a silent wrong answer would be worse.
     */
    private suspend fun searchWyzie(query: SubtitleQuery): List<SubtitleResult> {
        // Either id works, TMDB preferred: it is the one this app resolves for every title.
        val id = query.tmdbId?.toString()
            ?: query.imdbId?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        val lang = query.language.toIso639_1()
        if (lang.isBlank()) return emptyList()

        val params = buildList {
            add("id=$id")
            query.season?.let { add("season=$it") }
            query.episode?.let { add("episode=$it") }
        }

        val response = client.get("$WYZIE_BASE/wyzie?${params.joinToString("&")}") {
            header("User-Agent", LEGACY_AGENT)
        }
        if (!response.status.isSuccess()) return emptyList()

        return json.decodeFromString<List<WyzieSubtitle>>(response.bodyAsText())
            // Filtered client-side, and on the leading subtag so a stored "pt" still matches the
            // "pb" and "pt" this service distinguishes between.
            .filter { it.language?.startsWith(lang, ignoreCase = true) == true }
            .mapNotNull { it.toResult() }
            .ranked()
    }

    /**
     * The keyless aggregator, keyed by either id.
     *
     * Takes a TMDB id or an `tt`-prefixed IMDb one in the same `id` parameter, so a title
     * matched by either is searchable - unlike the legacy endpoint, which needs IMDb. Season
     * and episode are omitted for a film, which is what the endpoint expects rather than
     * something it tolerates.
     *
     * The language is filtered server-side. Sending it unfiltered returned every language at
     * once - eighty-one results for one episode - and the picker would have been unusable.
     */
    private suspend fun searchBright(query: SubtitleQuery): List<SubtitleResult> {
        // Either id works, TMDB preferred: it is the one this app resolves for every title,
        // where an IMDb id is only present when TMDB happened to carry one.
        val id = query.tmdbId?.toString()
            ?: query.imdbId?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        // A two-letter code, which is what this endpoint indexes by. Refused rather than sent
        // unfiltered, since an unrecognised value would otherwise return every language.
        val lang = query.language.toIso639_1()
        if (lang.isBlank()) return emptyList()

        val params = buildList {
            add("id=$id")
            query.season?.let { add("season=$it") }
            query.episode?.let { add("episode=$it") }
            add("language=$lang")
        }

        val response = client.get("$BRIGHT_BASE/search?${params.joinToString("&")}") {
            // The endpoint is a CORS API for a web player and checks Origin. Verified against
            // the live service: the search needs it, and the download URLs it hands back serve
            // without any header at all.
            header("Origin", BRIGHT_ORIGIN)
            header("User-Agent", LEGACY_AGENT)
        }
        if (!response.status.isSuccess()) return emptyList()

        return json.decodeFromString<List<BrightSubtitle>>(response.bodyAsText())
            .mapNotNull { it.toResult() }
            .ranked()
    }

    /** The modern REST API, keyed by TMDB id so it needs no IMDb match. */
    private suspend fun searchRest(query: SubtitleQuery, apiKey: String): List<SubtitleResult> {        if (apiKey.isBlank()) return emptyList()

        val params = buildList {
            query.tmdbId?.let { add("tmdb_id=$it") }
            if (query.tmdbId == null) query.imdbId?.let { add("imdb_id=${it.removePrefix("tt")}") }
            query.season?.let { add("season_number=$it") }
            query.episode?.let { add("episode_number=$it") }
            // The REST catalogue indexes by two-letter code, so a name or a regional tag has
            // to be reduced before it is sent.
            val lang = query.language.toIso639_1()
            if (lang.isBlank()) return emptyList()
            add("languages=$lang")
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
    suspend fun fetchText(url: String, extraHeaders: Map<String, String> = emptyMap()): String {
        val response = client.get(url) {
            header("User-Agent", LEGACY_AGENT)
            // A source's own subtitle sits on the same CDN as its video and is gated the same
            // way, so it needs the Referer the extension supplied. Without these the request
            // came back 403 and the track was silently skipped.
            extraHeaders.forEach { (name, value) ->
                if (!name.equals("User-Agent", ignoreCase = true)) header(name, value)
            }
        }
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
        private const val BRIGHT_BASE = "https://subs.bright67.online"
        private const val WYZIE_BASE = "https://vidfast.vc"

        /**
         * Origin the aggregator checks on a search.
         *
         * It is a CORS API for a web player, so it wants the player's own origin. Verified
         * against the live service: the search needs this, and the download URLs it returns
         * serve with no headers whatever.
         */
        private const val BRIGHT_ORIGIN = "https://cinesrc.st"

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

        /**
         * English names for the languages above, because a source labels its embedded tracks
         * for people rather than for an API.
         *
         * Selecting one of those tracks stores its label as the preferred language, and that
         * value is what a later online search is built from - so a track called "English"
         * produced `sublanguageid-english`, which is not a code the endpoint knows. Worse, some
         * labels are not URL-safe at all: "Portuguese (Brazil)" put brackets and a space into a
         * path segment, the URL failed to parse, and the request went to a nonsense host -
         * reported as "Unable to resolve host" rather than as a bad language.
         */
        private val NAME_TO_ISO_639_1 = mapOf(
            "english" to "en", "spanish" to "es", "french" to "fr", "german" to "de",
            "italian" to "it", "portuguese" to "pt", "russian" to "ru", "japanese" to "ja",
            "korean" to "ko", "chinese" to "zh", "arabic" to "ar", "hindi" to "hi",
            "indonesian" to "id", "thai" to "th", "vietnamese" to "vi", "turkish" to "tr",
            "polish" to "pl", "dutch" to "nl", "swedish" to "sv", "danish" to "da",
            "finnish" to "fi", "norwegian" to "no", "czech" to "cs", "greek" to "el",
            "hebrew" to "he", "hungarian" to "hu", "romanian" to "ro", "ukrainian" to "uk",
            "persian" to "fa", "malay" to "ms",
        )

        /**
         * Reduces whatever was stored to a two-letter code, or empty when it cannot be.
         *
         * Three shapes turn up. A code (`en`) and a regional code (`pt-BR`, indexed by its
         * base) are already usable. An English name is not: a source labels its embedded
         * tracks for people, and selecting one stored that label as the preferred language.
         *
         * Empty only for something that is neither - a name nobody mapped. That case cannot be
         * passed through, because the unsafe characters in a label like
         * "Portuguese (Brazil)" made the URL itself unparseable and the request went to a
         * nonsense host.
         */
        internal fun String.toIso639_1(): String {
            val cleaned = trim().lowercase()
            if (cleaned.isEmpty()) return ""

            val base = cleaned.substringBefore('-').substringBefore('_')
            if (base.length == 2 && base.all(Char::isLetter)) return base
            if (base.length == 3 && base.all(Char::isLetter)) {
                ISO_639_2.entries.firstOrNull { it.value == base }?.let { return it.key }
            }

            return NAME_TO_ISO_639_1[cleaned.substringBefore('(').trim()].orEmpty()
        }

        /**
         * The three-letter code the legacy endpoint wants.
         *
         * A short alphabetic token it does not recognise is still passed through: the endpoint
         * reads an unknown value as no language filter, which returns too much but is better
         * than refusing to ask. Anything that would not survive being put in a path segment is
         * refused instead - that is the case that produced an unresolvable host rather than a
         * bad result.
         */
        internal fun String.toIso639_2(): String {
            val code = toIso639_1()
            if (code.isNotEmpty()) return ISO_639_2[code] ?: code

            val cleaned = trim().lowercase()
            return if (cleaned.length in 2..3 && cleaned.all(Char::isLetter)) cleaned else ""
        }

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

    /**
     * Keyless curated list, accepting either a TMDB or an IMDb id.
     *
     * Returns one hand-picked subtitle per language rather than competing releases, so it is a
     * short list with nothing to choose between - useful when the catalogues offer a dozen cuts
     * of the same episode and none is obviously right.
     */
    VIDFAST_WYZIE,

    /**
     * Keyless aggregator, accepting either a TMDB or an IMDb id.
     *
     * Indexes OpenSubtitles but answers with far more per release than the legacy endpoint -
     * sync confidence, release group, trusted and machine-translated flags - and needs no key,
     * so it is usable without setup while still being ranked properly.
     */
    SUBS_BRIGHT,
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

    companion object {
        /** Searched for when the stored preference names no usable language. */
        const val DEFAULT_LANGUAGE = "en"

        /**
         * Reduces a stored preference to something a provider will accept.
         *
         * Repaired on read rather than trusted, because earlier builds stored a source's own
         * track label here - "English", "Portuguese (Brazil)" - and a device carrying one
         * would keep searching for a language no catalogue indexes, however the write path is
         * fixed. An unrecognisable value becomes [DEFAULT_LANGUAGE], since a default that
         * returns results beats one known to return none.
         *
         * "off" is not special here. It means the viewer does not want subtitles *shown*,
         * which is a playback preference and no reason to refuse to look for a file they
         * asked to download.
         */
        fun normaliseLanguage(stored: String): String =
            with(SubtitleApi) { stored.toIso639_1() }
                .takeIf { it.isNotBlank() }
                ?: DEFAULT_LANGUAGE

        /**
         * Builds the query for one episode, or null when nothing could be searched.
         *
         * Shared so the player and the download flow cannot disagree about it. They had
         * drifted: the player normalised the stored language and fell back to English, while
         * the download flow passed the raw value straight through and abandoned the search
         * outright when it was "off". A device whose preference held a label - the common
         * case, since selecting an embedded track used to store one - got three results in
         * the player and "none found" when downloading the same episode.
         */
        fun forEpisode(
            imdbId: String?,
            tmdbId: Int?,
            title: String,
            isMovie: Boolean,
            season: Int?,
            episodeNumber: Float?,
            storedLanguage: String,
        ): SubtitleQuery? {
            val query = SubtitleQuery(
                imdbId = imdbId,
                tmdbId = tmdbId,
                // Null for a film: the catalogue holds one as a single entry with no season,
                // and sending either field filters every result away.
                season = if (isMovie) null else season ?: 1,
                episode = if (isMovie) null else episodeNumber?.takeIf { it >= 0f }?.toInt(),
                language = normaliseLanguage(storedLanguage),
                title = title,
            )

            return query.takeUnless { it.isUnusable }
        }
    }
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
