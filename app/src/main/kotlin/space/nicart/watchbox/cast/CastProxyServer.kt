package space.nicart.watchbox.cast

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Local HTTP server that re-hosts a stream for a cast receiver.
 *
 * ## Why this has to exist
 *
 * Casting is pull-based: you hand the receiver a URL and it opens its own
 * connection. Neither the Cast protocol's LOAD message nor DLNA's
 * SetAVTransportURI carries request headers, so a receiver cannot send the
 * `Referer` that most extension CDNs require — the stream simply 403s and the
 * TV reports an opaque failure.
 *
 * Routing playback through here fixes that: the receiver fetches from us, and we
 * fetch upstream with the headers the extension supplied.
 *
 * ## What it deliberately does not do
 *
 * No caching, no transcoding, no persistence. It is a header-injecting relay
 * with `Range` passthrough, plus HLS manifest rewriting so that segment requests
 * come back through the same relay. Anything more belongs in the player, not
 * here.
 *
 * Bound to the address the receiver can reach (see [CastNetwork.localAddressFor])
 * rather than to a wildcard, so we never advertise an unroutable IP.
 */
class CastProxyServer(
    private val client: OkHttpClient,
) {

    /** One entry per cast session; cleared when casting stops. */
    private val streams = ConcurrentHashMap<String, ProxiedStream>()

    private val nextId = AtomicLong(1)
    private var socket: ServerSocket? = null
    private var scope: CoroutineScope? = null

    @Volatile
    var boundHost: String? = null
        private set

    @Volatile
    var boundPort: Int = 0
        private set

    val isRunning: Boolean get() = socket?.isClosed == false

    /** Subtitles published for the receiver, by id. */
    private val subtitles = ConcurrentHashMap<String, PublishedSubtitle>()

    /**
     * A subtitle the receiver can fetch from us.
     *
     * Either a file in this app's cache or a remote URL that needs headers. Both go through the
     * proxy for the same two reasons: a receiver cannot send a `Referer`, and it only accepts
     * WebVTT - so a source's own `.srt` link fails whether or not it is reachable.
     */
    data class PublishedSubtitle(
        val file: java.io.File? = null,
        val url: String? = null,
        val headers: Map<String, String> = emptyMap(),
        /** Used to decide conversion when a remote URL carries no usable extension. */
        val name: String = url ?: file?.name.orEmpty(),
    )

    data class ProxiedStream(
        val id: String,
        val upstreamUrl: String,
        val headers: Map<String, String>,
        val mimeType: String,
        val isHls: Boolean,
    )

    /**
     * Starts listening on the interface that can reach [targetHost].
     *
     * Returns the base URL to advertise, or null when no route exists — the
     * caller must treat that as "cannot cast" rather than falling back to
     * localhost, which the receiver could never reach.
     */
    fun start(targetHost: String): String? {
        stop()

        val host = CastNetwork.localAddressFor(targetHost, HTTP_PROBE_PORT)
            ?: CastNetwork.outboundAddress()
            ?: return null

        return runCatching {
            val server = ServerSocket(0, BACKLOG, java.net.InetAddress.getByName(host))
            socket = server
            boundHost = host
            boundPort = server.localPort

            val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = serverScope
            serverScope.launch { acceptLoop(server) }

            "http://$host:${server.localPort}"
        }.getOrElse {
            Log.w(TAG, "Could not bind proxy on $host: ${it.message}")
            stop()
            null
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        scope?.cancel()
        scope = null
        streams.clear()
        subtitles.clear()
        boundHost = null
        boundPort = 0
    }

    /**
     * Registers a stream and returns the URL to give the receiver.
     *
     * The upstream URL is kept server-side and referenced by an opaque id rather
     * than embedded in the path, so it is never exposed on the local network.
     */
    fun publish(
        upstreamUrl: String,
        headers: Map<String, String>,
        mimeType: String,
        isHls: Boolean,
    ): String? {
        val host = boundHost ?: return null
        val id = nextId.getAndIncrement().toString()

        streams[id] = ProxiedStream(
            id = id,
            upstreamUrl = upstreamUrl,
            headers = headers,
            mimeType = mimeType,
            isHls = isHls,
        )

        // The extension matters: some receivers sniff the type from the path
        // before trusting Content-Type.
        val suffix = if (isHls) ".m3u8" else ".mp4"
        return "http://$host:$boundPort/s/$id$suffix"
    }

    /**
     * Publishes a local subtitle file and returns a URL the receiver can fetch.
     *
     * Downloaded subtitles live in this app's cache as `file://` paths, which a receiver on
     * another device cannot open - it fetches subtitles itself, over HTTP. Serving them through
     * the proxy that is already running for the video is what makes them reachable.
     *
     * The URL always ends `.vtt`: receivers sniff the extension, and the body is converted to
     * WebVTT on the way out regardless of what the file holds.
     */
    fun publishSubtitle(file: java.io.File, asWebVtt: Boolean): String? =
        publishSubtitle(PublishedSubtitle(file = file), asWebVtt)

    /**
     * Publishes a remote subtitle so the receiver fetches it through us.
     *
     * Source-supplied subtitles were handed over as their original CDN URL. That fails twice: the
     * CDN often requires the same `Referer` as the video, which a receiver cannot send, and the
     * file is usually SubRip, which the receiver ignores. Relaying fixes both - the headers are
     * added here and the body is converted on the way out.
     */
    fun publishRemoteSubtitle(
        url: String,
        headers: Map<String, String>,
        asWebVtt: Boolean,
    ): String? = publishSubtitle(PublishedSubtitle(url = url, headers = headers), asWebVtt)

    private fun publishSubtitle(entry: PublishedSubtitle, asWebVtt: Boolean): String? {
        val host = boundHost ?: return null
        val id = nextId.getAndIncrement().toString()

        subtitles[id] = entry

        // The extension decides the conversion, because receivers sniff the path and the two
        // protocols want opposite things: a Cast receiver accepts only WebVTT, while DLNA
        // renderers are built around SubRip and commonly ignore WebVTT outright.
        val suffix = if (asWebVtt) "vtt" else "srt"
        return "http://$host:$boundPort/sub/$id.$suffix"
    }

    // ------------------------------------------------------------ connections

    private suspend fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val connection = runCatching { server.accept() }.getOrNull() ?: break
            scope?.launch { handle(connection) }
        }
    }

    private fun handle(connection: Socket) {
        connection.use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS

            runCatching {
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())

                val request = readRequest(input) ?: return@runCatching
                route(request, output)
                output.flush()
            }.onFailure {
                // A receiver aborting mid-stream is normal (seek, stop, buffer
                // trim), so this is not worth surfacing as an error.
                Log.d(TAG, "connection ended: ${it.javaClass.simpleName}")
            }
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
    )

    private fun readRequest(input: BufferedInputStream): HttpRequest? {
        val requestLine = readLine(input)?.takeIf { it.isNotBlank() } ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                // Lower-cased because receivers are inconsistent about casing.
                headers[line.take(colon).trim().lowercase()] =
                    line.substring(colon + 1).trim()
            }
        }

        return HttpRequest(parts[0].uppercase(), parts[1], headers)
    }

    /** Reads a CRLF-terminated line without over-reading into the body. */
    private fun readLine(input: BufferedInputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) return builder.takeIf { it.isNotEmpty() }?.toString()
            if (byte == '\n'.code) return builder.toString().removeSuffix("\r")
            builder.append(byte.toChar())
        }
    }

    private fun route(request: HttpRequest, output: OutputStream) {
        val path = request.path.substringBefore('?')

        // Answered before routing: a preflight names no resource of ours.
        if (request.method == "OPTIONS") {
            writePreflight(output)
            return
        }

        Log.d(TAG, "${request.method} ${path.take(80)} range=${request.headers["range"]}")

        when {
            // Segment/key fetches rewritten out of an HLS manifest.
            path.startsWith("/seg/") -> {
                val encoded = path.removePrefix("/seg/")
                val sessionId = encoded.substringBefore('/')
                val target = runCatching {
                    URLDecoder.decode(encoded.substringAfter('/'), "UTF-8")
                }.getOrNull()

                val stream = streams[sessionId]
                if (target == null || stream == null) {
                    writeStatus(output, 404, "Not Found")
                } else {
                    relay(target, stream, request, output, rewriteManifest = false)
                }
            }

            // Local subtitle file, converted to WebVTT on the way out.
            path.startsWith("/sub/") -> {
                val id = path.removePrefix("/sub/").substringBefore('.')
                val entry = subtitles[id]
                if (entry == null) {
                    writeStatus(output, 404, "Not Found")
                } else {
                    writeSubtitle(
                        entry = entry,
                        request = request,
                        output = output,
                        asWebVtt = path.endsWith(".vtt", ignoreCase = true),
                    )
                }
            }

            path.startsWith("/s/") -> {
                val id = path.removePrefix("/s/").substringBefore('.')
                val stream = streams[id]
                if (stream == null) {
                    writeStatus(output, 404, "Not Found")
                } else {
                    relay(
                        stream.upstreamUrl,
                        stream,
                        request,
                        output,
                        rewriteManifest = stream.isHls,
                    )
                }
            }

            else -> writeStatus(output, 404, "Not Found")
        }
    }

    /**
     * Answers a CORS preflight.
     *
     * The Default Media Receiver is a Chromium page driving Media Source Extensions, so its
     * fetches are subject to browser CORS rules - unlike a DLNA renderer, which has no such
     * notion. An unanswered `OPTIONS` fell through to 404, and the browser then refused the
     * request that followed.
     */
    private fun writePreflight(output: OutputStream) {
        writeHeaders(
            output = output,
            status = 204,
            reason = "No Content",
            contentType = "text/plain",
            contentLength = 0,
            extra = mapOf(
                "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                "Access-Control-Allow-Headers" to "*",
                "Access-Control-Max-Age" to "86400",
            ),
        )
    }

    /**
     * Fetches [url] upstream with the session's headers and relays it.
     *
     * `Range` is forwarded so the receiver can seek in progressive MP4, and the
     * upstream status is passed through unchanged - a 206 must stay a 206 or
     * seeking silently breaks.
     *
     * Only the entry-point manifest is rewritten. A nested rewrite was tried and reverted:
     * every source seen in practice publishes a single-level media playlist, so it solved a
     * problem no real stream had while adding a content-type guess to every segment fetch.
     */
    private fun relay(
        url: String,
        stream: ProxiedStream,
        request: HttpRequest,
        output: OutputStream,
        rewriteManifest: Boolean,
    ) {
        val builder = Request.Builder().url(url)
        stream.headers.forEach { (name, value) -> builder.header(name, value) }
        request.headers["range"]?.let { builder.header("Range", it) }

        val call = client.newBuilder()
            .readTimeout(UPSTREAM_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
            .newCall(builder.build())

        // Logged rather than swallowed: an upstream failure here is invisible to the receiver,
        // which simply stops requesting, so this is the only record that anything went wrong.
        runCatching { call.execute() }
            .onFailure {
                Log.w(TAG, "upstream fetch failed: ${it.javaClass.simpleName}: ${it.message}")
            }
            .getOrThrow()
            .use { response ->
            // okhttp 5 guarantees a non-null body, so there is nothing to guard.
            val body = response.body

            Log.d(
                TAG,
                "upstream ${response.code} ct=${response.header("Content-Type")} " +
                    "rewrite=$rewriteManifest",
            )

            if (rewriteManifest) {
                // Manifests are small, so buffering to rewrite is fine; media
                // segments are always streamed.
                val rewritten = HlsRewriter.rewrite(
                    manifest = body.string(),
                    manifestUrl = url,
                    sessionId = stream.id,
                    proxyBase = "http://$boundHost:$boundPort",
                )
                val bytes = rewritten.toByteArray()

                writeHeaders(
                    output = output,
                    status = response.code,
                    reason = response.message.ifBlank { "OK" },
                    contentType = HLS_MIME,
                    contentLength = bytes.size.toLong(),
                    extra = mapOf("Cache-Control" to "no-cache"),
                )
                if (request.method != "HEAD") output.write(bytes)
                return
            }

            val contentType = response.header("Content-Type")
                ?: stream.mimeType.takeIf { it.isNotBlank() }
                ?: "application/octet-stream"

            val extra = buildMap {
                response.header("Content-Range")?.let { put("Content-Range", it) }
                put("Accept-Ranges", "bytes")
                // The DLNA renderer contract; harmless to a Chromecast.
                put("transferMode.dlna.org", "Streaming")
                put("realTimeInfo.dlna.org", "DLNA.ORG_TLAG=*")
            }

            writeHeaders(
                output = output,
                status = response.code,
                reason = response.message.ifBlank { "OK" },
                contentType = contentType,
                contentLength = body.contentLength().takeIf { it >= 0 },
                extra = extra,
            )

            // Some renderers issue a HEAD first and only stream if it answers.
            if (request.method == "HEAD") return

            body.byteStream().use { input ->
                val buffer = ByteArray(RELAY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    /**
     * Writes a subtitle file to the receiver as WebVTT.
     *
     * Buffered rather than streamed: subtitle files are tens of kilobytes, and the conversion
     * needs the whole text anyway. The declared length must match the converted body, not the
     * file on disk, since the header and timestamp rewrites change the byte count.
     */
    private fun writeSubtitle(
        entry: PublishedSubtitle,
        request: HttpRequest,
        output: OutputStream,
        asWebVtt: Boolean,
    ) {
        val converted = runCatching {
            val raw = when {
                entry.file != null -> entry.file.readText()

                entry.url != null -> {
                    val builder = Request.Builder().url(entry.url)
                    entry.headers.forEach { (name, value) -> builder.header(name, value) }
                    client.newCall(builder.build()).execute().use { response ->
                        if (!response.isSuccessful) {
                            error("upstream ${response.code}")
                        }
                        response.body.string()
                    }
                }

                else -> error("no source")
            }

            // Converted whenever WebVTT was asked for and the text is not already WebVTT.
            // Judged from the content rather than the name: a source's subtitle URL frequently
            // has no extension at all, so a name check alone would pass SubRip through
            // unconverted and the receiver would silently show nothing.
            if (asWebVtt) SubtitleConverter.toWebVtt(raw) else raw
        }.getOrElse {
            Log.w(TAG, "could not read subtitle: ${it.javaClass.simpleName}: ${it.message}")
            writeStatus(output, 502, "Bad Gateway")
            return
        }

        val bytes = converted.toByteArray(Charsets.UTF_8)

        writeHeaders(
            output = output,
            status = 200,
            reason = "OK",
            contentType = if (asWebVtt) "text/vtt; charset=utf-8" else "text/srt; charset=utf-8",
            contentLength = bytes.size.toLong(),
            extra = mapOf("Cache-Control" to "no-cache"),
        )

        if (request.method != "HEAD") output.write(bytes)
    }

    // ---------------------------------------------------------------- writing

    private fun writeHeaders(
        output: OutputStream,
        status: Int,
        reason: String,
        contentType: String,
        contentLength: Long?,
        extra: Map<String, String> = emptyMap(),
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append(CRLF)
            append("Content-Type: ").append(contentType).append(CRLF)
            contentLength?.let { append("Content-Length: ").append(it).append(CRLF) }
            // The Cast receiver is a web page, so subtitle and manifest fetches
            // are subject to browser CORS rules.
            append("Access-Control-Allow-Origin: *").append(CRLF)
            // Without this a browser-based receiver cannot read the length or range of a
            // response, which Media Source Extensions needs to buffer at all.
            append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges")
                .append(CRLF)
            append("Connection: close").append(CRLF)
            extra.forEach { (name, value) ->
                append(name).append(": ").append(value).append(CRLF)
            }
            append(CRLF)
        }
        output.write(head.toByteArray())
    }

    private fun writeStatus(output: OutputStream, status: Int, reason: String) {
        writeHeaders(output, status, reason, "text/plain", 0)
    }

    private companion object {
        const val TAG = "CastProxy"
        const val CRLF = "\r\n"
        const val BACKLOG = 8
        const val SOCKET_TIMEOUT_MS = 30_000
        const val RELAY_BUFFER_BYTES = 64 * 1024
        const val UPSTREAM_READ_TIMEOUT_SECONDS = 30L
        const val HTTP_PROBE_PORT = 8009
        const val HLS_MIME = "application/vnd.apple.mpegurl"
    }
}

/** Percent-encodes a URL for embedding in a proxy path segment. */
internal fun String.pathEncoded(): String =
    URLEncoder.encode(this, "UTF-8").replace("+", "%20")

