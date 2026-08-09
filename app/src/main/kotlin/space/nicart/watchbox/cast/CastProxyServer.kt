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
     * Fetches [url] upstream with the session's headers and relays it.
     *
     * `Range` is forwarded so the receiver can seek in progressive MP4, and the
     * upstream status is passed through unchanged — a 206 must stay a 206 or
     * seeking silently breaks.
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

        call.execute().use { response ->
            // okhttp 5 guarantees a non-null body, so there is nothing to guard.
            val body = response.body

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
