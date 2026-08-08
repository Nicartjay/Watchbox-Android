package space.nicart.watchbox.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import space.nicart.watchbox.data.model.ApiEnvelope
import space.nicart.watchbox.data.model.CaptionResponse
import space.nicart.watchbox.data.model.DetailResponse
import space.nicart.watchbox.data.model.HomeResponse
import space.nicart.watchbox.data.model.PagedSubjects
import space.nicart.watchbox.data.model.PlayResponse
import space.nicart.watchbox.data.model.SearchRequest

/**
 * The AOneRoom ("ONEROOM") content API.
 *
 * Endpoint paths mirror the web app exactly — see the `${API_BASE}/...` call
 * sites in `js/app.js`, `js/search.js`, `js/detail/index.js`.
 *
 * Note the split responsibility with [WatchBoxApi]: metadata comes straight from
 * AOneRoom, but **stream resolution must go through our own proxy**, because the
 * play API rate-limits by IP and requires a spoofed `Referer`.
 */
class OneRoomApi(private val client: HttpClient) {

    suspend fun home(page: Int = 1, perPage: Int = 12): HomeResponse? =
        client.get("$BASE/home") {
            parameter("page", page)
            parameter("perPage", perPage)
        }.envelope<HomeResponse>()

    suspend fun detail(detailPath: String): DetailResponse? =
        client.get("$BASE/detail") {
            parameter("detailPath", detailPath)
        }.envelope<DetailResponse>()

    suspend fun recommendations(subjectId: String): PagedSubjects? =
        client.get("$BASE/subject/detail-rec") {
            parameter("subjectId", subjectId)
        }.envelope<PagedSubjects>()

    suspend fun search(
        keyword: String,
        page: Int = 1,
        perPage: Int = 28,
        subjectType: Int = 0,
    ): PagedSubjects? =
        client.post("$BASE/subject/search") {
            contentType(ContentType.Application.Json)
            setBody(
                SearchRequest(
                    keyword = keyword,
                    page = page.toString(),
                    perPage = perPage,
                    subjectType = subjectType,
                ),
            )
        }.envelope<PagedSubjects>()

    suspend fun trending(page: Int = 1, perPage: Int = 20): PagedSubjects? =
        client.get("$BASE/subject/trending") {
            parameter("page", page)
            parameter("perPage", perPage)
        }.envelope<PagedSubjects>()

    suspend fun upcoming(page: Int = 1, perPage: Int = 20): PagedSubjects? =
        client.get("$BASE/upcoming-subject-list") {
            parameter("page", page)
            parameter("perPage", perPage)
        }.envelope<PagedSubjects>()

    /** Subtitle tracks for a resolved stream. See `js/player/api.js:92`. */
    suspend fun captions(
        streamId: String,
        subjectId: String,
        detailPath: String,
        format: String = "MP4",
    ): CaptionResponse? =
        client.get("$BASE/subject/caption") {
            parameter("format", format)
            parameter("id", streamId)
            parameter("subjectId", subjectId)
            parameter("detailPath", detailPath)
        }.envelope<CaptionResponse>()

    companion object {
        const val BASE = "https://h5-api.aoneroom.com/wefeed-h5api-bff"
    }
}

/**
 * Our own backend: the Cloudflare Worker (proxying + native source resolvers)
 * plus the Vercel edge function used only for `/api/play`.
 */
class WatchBoxApi(
    private val client: HttpClient,
    private val workerBaseProvider: suspend () -> String,
) {

    /**
     * Resolve playable streams for an episode.
     *
     * Deliberately hits the **Vercel** proxy rather than the Worker: upstream
     * (CloudFront) rate-limits Cloudflare egress IPs, which is why the web app
     * routes this endpoint the same way (`js/stream-utils.js:9-11`). Verified
     * live — the Worker returns HTTP 429 for this route while Vercel succeeds.
     */
    suspend fun play(
        subjectId: String,
        detailPath: String,
        season: Int,
        episode: Int,
    ): PlayResponse? =
        client.get("$PLAY_PROXY/api/play") {
            parameter("subjectId", subjectId)
            parameter("detailPath", detailPath)
            parameter("se", season)
            parameter("ep", episode)
        }.envelope<PlayResponse>()

    /**
     * Wrap a CDN URL in the Worker's stream proxy so the right `Referer` /
     * `Origin` are injected server-side (`js/stream-utils.js:18-21`).
     */
    suspend fun proxiedStreamUrl(url: String): String {
        if (url.isBlank()) return url
        val base = workerBaseProvider().trimEnd('/')
        return "$base/api/stream?url=${url.urlEncoded()}"
    }

    /** Subtitle passthrough that normalises SRT to WebVTT server-side. */
    suspend fun subtitleUrl(url: String): String {
        if (url.isBlank()) return url
        val base = workerBaseProvider().trimEnd('/')
        return "$base/api/subtitle?url=${url.urlEncoded()}"
    }

    companion object {
        const val PLAY_PROXY = "https://proxy-gamma-blue.vercel.app"
        const val DEFAULT_WORKER_BASE = "https://watchbox.nicart.space"
    }
}

// ------------------------------------------------------------------ helpers

/**
 * Unwrap `{code, message, data}`. Returns null for transport failures and for
 * any non-zero `code`, so callers only branch on null.
 */
internal suspend inline fun <reified T> io.ktor.client.statement.HttpResponse.envelope(): T? {
    if (!status.isSuccess()) return null
    val envelope = runCatching { body<ApiEnvelope<T>>() }.getOrNull() ?: return null
    return if (envelope.isOk) envelope.data else null
}

internal fun String.urlEncoded(): String =
    java.net.URLEncoder.encode(this, "UTF-8")
        .replace("+", "%20")
