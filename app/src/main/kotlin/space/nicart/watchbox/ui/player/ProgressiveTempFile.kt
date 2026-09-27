package space.nicart.watchbox.ui.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Plays a "no seek" stream from a file it downloads in the background.
 *
 * Some hosts (Google's `video-downloads`, for one) ignore Range and always answer from byte 0,
 * so a seek over the network means re-downloading everything before the target. Instead the
 * whole file is fetched once, front to back, into a temporary file, and the player reads that
 * file. Anything already on disk can be sought to instantly; reading past the downloaded edge
 * simply waits for the download to catch up, exactly like buffering.
 *
 * One instance per stream. [close] stops the download and deletes the file.
 */
@UnstableApi
class ProgressiveTempFile(
    /** The remote file this downloads; the player reuses an instance for the same URL. */
    val url: String,
    private val headers: Map<String, String>,
    private val client: OkHttpClient,
    cacheDir: File,
) {
    /** How far the download has got, for the timeline. */
    data class Progress(val downloaded: Long, val total: Long, val done: Boolean, val failed: Boolean) {
        /** 0..1 of the file on disk; 0 while the size is unknown. */
        val fraction: Float get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    val file: File = File(File(cacheDir, DIR).apply { mkdirs() }, "stream-${url.hashCode().toUInt().toString(16)}.part")

    private val _progress = MutableStateFlow(Progress(0L, C.LENGTH_UNSET.toLong(), done = false, failed = false))
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val lock = Object()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var closed = false

    /** The URI the player should open; any string works, the factory below ignores it. */
    val playbackUri: Uri = Uri.fromFile(file)

    fun start() {
        if (job != null) return
        file.delete()
        job = scope.launch { download() }
    }

    private fun download() {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) ->
                if (!name.equals("Range", true) && !name.equals("Accept-Encoding", true)) header(name, value)
            }
        }.build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("empty body")
                val total = body.contentLength()
                publish(0L, total, done = false)

                RandomAccessFile(file, "rw").use { out ->
                    val source = body.byteStream()
                    val buffer = ByteArray(BUFFER)
                    var written = 0L
                    var lastPublished = 0L
                    while (scope.isActive && !closed) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        // Published in steps rather than per read, so the UI is not flooded.
                        if (written - lastPublished >= PUBLISH_STEP) {
                            publish(written, total, done = false)
                            lastPublished = written
                        }
                    }
                    publish(written, if (total > 0) total else written, done = !closed)
                }
            }
        } catch (e: Exception) {
            if (!closed) {
                android.util.Log.w(TAG, "temp download failed: ${e::class.java.simpleName}: ${e.message}")
                _progress.value = _progress.value.copy(failed = true)
                synchronized(lock) { lock.notifyAll() }
            }
        }
    }

    private fun publish(downloaded: Long, total: Long, done: Boolean) {
        _progress.value = Progress(downloaded, total, done, failed = false)
        synchronized(lock) { lock.notifyAll() }
    }

    /**
     * Blocks until [position] bytes are on disk (or the download ends), and returns how many
     * bytes are readable from [position] right now. -1 at the true end of file.
     */
    internal fun awaitAvailable(position: Long): Long {
        synchronized(lock) {
            while (true) {
                val p = _progress.value
                if (p.downloaded > position) return p.downloaded - position
                if (p.done) return -1
                if (p.failed) throw IOException("stream download failed")
                if (closed) throw IOException("closed")
                lock.wait(WAIT_SLICE_MS)
            }
        }
    }

    /** Media3 data source reading the growing file. */
    fun dataSourceFactory(): DataSource.Factory = DataSource.Factory { Reader() }

    private inner class Reader : BaseDataSource(/* isNetwork = */ false) {
        private var raf: RandomAccessFile? = null
        private var position = 0L
        private var uri: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            transferInitializing(dataSpec)
            uri = dataSpec.uri
            position = dataSpec.position
            // Waits for the first byte at this offset, so a seek inside the file is instant and
            // one just past the edge behaves like buffering.
            awaitAvailable(position)
            raf = RandomAccessFile(file, "r")
            transferStarted(dataSpec)
            val total = _progress.value.total
            return if (total > 0) total - position else C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val available = awaitAvailable(position)
            if (available < 0) return C.RESULT_END_OF_INPUT
            val file = raf ?: throw IOException("not open")
            file.seek(position)
            val read = file.read(buffer, offset, minOf(length.toLong(), available).toInt())
            if (read <= 0) return C.RESULT_END_OF_INPUT
            position += read
            bytesTransferred(read)
            return read
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            raf?.close()
            raf = null
            uri = null
            transferEnded()
        }
    }

    fun close() = detach(deleteFile = true)

    /**
     * Stops the download and wakes any waiting reader. The file is deleted unless it has just
     * been moved into Downloads, which is the one time it must survive the player closing.
     */
    fun detach(deleteFile: Boolean) {
        closed = true
        synchronized(lock) { lock.notifyAll() }
        scope.cancel()
        if (deleteFile) file.delete()
    }


    companion object {
        private const val TAG = "WbTempStream"
        private const val DIR = "noseek"
        private const val BUFFER = 256 * 1024
        private const val PUBLISH_STEP = 2L * 1024 * 1024
        private const val WAIT_SLICE_MS = 500L

        /** Deletes temp files left by a previous session (a crash or a killed process). */
        fun clearStale(cacheDir: File) {
            File(cacheDir, DIR).listFiles()?.forEach { it.delete() }
            // Early test builds used this misspelled folder.
            File(cacheDir, "nosseek").deleteRecursively()
        }

        /** Bytes held by temp files right now, including one that is still downloading. */
        fun cacheSize(cacheDir: File): Long =
            File(cacheDir, DIR).listFiles()?.sumOf { it.length() } ?: 0L
    }
}
