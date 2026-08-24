package space.nicart.watchbox.download

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import android.net.Uri

/**
 * Wraps a data source so an expired credential is re-signed rather than fatal.
 *
 * Extensions sign their stream URLs and hand back a credential good for roughly two
 * minutes. That is ample for playback, which starts immediately, and hopeless for a
 * download: a multi-gigabyte file cannot finish inside the window, so every long download
 * would die partway with a 403 and stay dead, because retrying the same URL retries the
 * same expired signature.
 *
 * On a 401 or 403 this asks [reResolve] for a fresh URL for the same episode and quality,
 * then reissues the request against it from the same byte offset. The bytes already
 * written are kept - the new URL serves identical content, so the download continues
 * rather than restarting, which is the difference between a 20 GB file completing and it
 * looping forever.
 *
 * Only those two statuses are treated this way. A 404 means the release is gone, a 5xx is
 * the server's problem, and re-resolving either would turn a clear failure into an endless
 * retry.
 */
@UnstableApi
class ReResolvingDataSource(
    private val upstream: HttpDataSource,
    private val reResolve: (String) -> ResolvedStream?,
    /** Cached headers for a download, read without touching the network. */
    private val headersFor: (String) -> Map<String, String>,
) : DataSource {

    private var attemptedReResolve = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        // Headers applied before the first request, not only after one has failed.
        //
        // This is what made every segmented download 403 immediately with nothing transferred.
        // These CDNs check a Referer, the extension supplies one, and it was reaching the
        // network only on the retry path - so the opening request was always sent bare, failed,
        // and the download was marked failed before the refresh could help. The player has
        // always applied them per request; the downloader has to as well.
        // Read from cache, never resolved here: a segmented download opens hundreds of
        // connections and resolving for each would be slower than streaming the episode.
        val prepared = dataSpec.key
            ?.let(headersFor)
            ?.takeIf { it.isNotEmpty() }
            ?.let { dataSpec.withRequestHeaders(it) }
            ?: dataSpec

        return try {
            upstream.open(prepared)
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            if (!isCredentialFailure(error.responseCode) || attemptedReResolve) throw error

            // Latched for the lifetime of this source. A second failure straight after a
            // refresh is not an expiry - it is a source that will not serve us at all -
            // and retrying it in a loop would hammer the extension.
            attemptedReResolve = true

            // Identified by the spec's own key, which the downloader sets per download. Asking
            // without it re-resolved against whichever download was queued most recently, so
            // with several running only that one could ever recover.
            val key = dataSpec.key ?: throw error
            val fresh = reResolve(key) ?: throw error

            // The offset is carried over, not reset. The refreshed URL points at the same
            // file, so what is already on disk is still valid and only the remainder is
            // wanted.
            upstream.open(
                prepared
                    .buildUpon()
                    .setUri(Uri.parse(fresh.url))
                    .build()
                    .withRequestHeaders(fresh.headers),
            )
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        upstream.close()
    }

    private fun isCredentialFailure(code: Int): Boolean = code == 401 || code == 403

    /** A freshly resolved stream: a live URL and the headers that make it work. */
    data class ResolvedStream(val url: String, val headers: Map<String, String>)

    /**
     * Builds [ReResolvingDataSource]s over an upstream HTTP factory.
     *
     * [reResolve] is called from the download thread and is expected to block, which is why
     * it is a plain function rather than a suspending one - Media3's data source contract is
     * synchronous, and bridging a coroutine across it belongs at the call site.
     */
    @UnstableApi
    class Factory(
        private val upstreamFactory: HttpDataSource.Factory,
        private val reResolve: (String) -> ResolvedStream?,
        private val headersFor: (String) -> Map<String, String>,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ReResolvingDataSource(upstreamFactory.createDataSource(), reResolve, headersFor)
    }
}
