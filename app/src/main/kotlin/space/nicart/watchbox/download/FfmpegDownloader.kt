package space.nicart.watchbox.download

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeSession
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.nicart.watchbox.domain.StreamOption
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads a stream by remuxing it with FFmpeg.
 *
 * Exists for the streams Media3's downloader cannot fetch at all. Some extensions never return a
 * real media URL: they run an HTTP proxy inside their own process and hand back a `localhost`
 * address on a port chosen fresh each session. Media3 persists a download and works through it
 * in a background service, by which point that proxy is gone - the observed failure is an
 * immediate 403 with nothing transferred, on a source whose playback works perfectly.
 *
 * FFmpeg resolves the manifest and pulls every segment in one continuous session, while the
 * proxy is still alive, which is how the rest of this ecosystem downloads them.
 *
 * The trade-off is deliberate and worth stating: this cannot resume. A session is a single
 * process invocation, so an interrupted download restarts from nothing, where Media3 would
 * continue from its cache. That is why it is used only for streams Media3 cannot handle rather
 * than for everything.
 *
 * Output is Matroska with every track copied rather than re-encoded. Copying is near-instant and
 * lossless; re-encoding a two-gigabyte episode on a phone would take hours and look worse.
 */
class FfmpegDownloader(private val context: Context) {

    /** Session ids by download key, so one download can be cancelled without touching others. */
    private val sessions = ConcurrentHashMap<String, Long>()

    /**
     * Downloads [stream] to [target], reporting bytes written through [onProgress].
     *
     * Blocks until finished. Returns true on success; false when it failed or was cancelled, in
     * which case the partial file is removed - a truncated remux is not playable, so leaving it
     * would only look like a download that had worked.
     */
    suspend fun download(
        key: String,
        stream: StreamOption,
        target: File,
        onProgress: (bytesWritten: Long, percent: Float) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        // Warnings and worse only. FFmpeg's info level narrates every segment, which on a
        // thousand-segment episode floods logcat and hides anything useful.
        FFmpegKitConfig.setLogLevel(Level.AV_LOG_WARNING)

        target.parentFile?.mkdirs()
        // A stale partial from an earlier attempt would be appended to rather than replaced.
        if (target.exists()) target.delete()

        // Probed first so progress can be a percentage rather than a rising byte count.
        //
        // ffmpeg reports how far through the timeline it has muxed, not how many bytes remain,
        // and a manifest declares no total size at all - so without the duration there is
        // nothing to divide by and the bar sat at 0% while only the size moved. Zero when the
        // probe fails, which is survivable: the size is still shown.
        val durationMs = probeDurationMs(stream)

        val command = buildCommand(stream, target)

        val logCallback = LogCallback { log ->
            if (log.level.ordinal <= Level.AV_LOG_WARNING.ordinal) {
                log.message?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    android.util.Log.w(TAG, "[$key] $it")
                }
            }
        }

        // Bytes rather than duration. A percentage needs the total length, which for a manifest
        // is only known after probing it - an extra round trip through a proxy that may not
        // survive it - so the size written is reported and the UI shows that instead.
        val statsCallback = StatisticsCallback { stats ->
            val written = stats.size.coerceAtLeast(0L)
            // getTime is the position reached in the output, in milliseconds.
            val percent = if (durationMs > 0) {
                (stats.time / durationMs * 100).toFloat().coerceIn(0f, 100f)
            } else {
                0f
            }
            onProgress(written, percent)
        }

        val session: FFmpegSession = FFmpegSession.create(
            FFmpegKitConfig.parseArguments(command),
            null,
            logCallback,
            statsCallback,
        )

        sessions[key] = session.sessionId

        try {
            FFmpegKitConfig.ffmpegExecute(session)

            val ok = ReturnCode.isSuccess(session.returnCode)
            if (!ok) {
                // Cancellation is an ordinary outcome, not a fault, so it is not logged as one.
                if (ReturnCode.isCancel(session.returnCode)) {
                    android.util.Log.i(TAG, "[$key] cancelled")
                } else {
                    android.util.Log.w(
                        TAG,
                        "[$key] failed rc=${session.returnCode} " +
                            "(${session.returnCode?.value?.describeFfmpegError()}) " +
                            (session.failStackTrace ?: ""),
                    )
                }
                target.delete()
            }
            ok
        } finally {
            sessions.remove(key)
        }
    }

    /**
     * Length of [stream] in milliseconds, or zero when it cannot be determined.
     *
     * A separate ffprobe pass, which is one extra request against the manifest - worth it for a
     * download measured in minutes, and the only way to show real progress. Everything here is
     * best-effort: a probe that fails leaves the size as the only progress signal rather than
     * stopping the download.
     */
    private fun probeDurationMs(stream: StreamOption): Double {
        val headerArg = stream.headers
            .takeIf { it.isNotEmpty() }
            ?.entries
            ?.joinToString("") { "${it.key}: ${it.value}\r\n" }
            ?.let { "-headers '$it' " }
            .orEmpty()

        val session = FFprobeSession.create(
            FFmpegKitConfig.parseArguments(
                headerArg +
                    "-v quiet -show_entries format=duration " +
                    "-of default=noprint_wrappers=1:nokey=1 \"${stream.url}\"",
            ),
        )

        return runCatching {
            FFmpegKitConfig.ffprobeExecute(session)
            if (!ReturnCode.isSuccess(session.returnCode)) return 0.0
            // Seconds, as a decimal, or "N/A" for a stream whose length is not declared.
            session.output?.trim()?.toDoubleOrNull()?.times(1_000) ?: 0.0
        }.getOrDefault(0.0)
    }

    /** Stops one download. Its partial file is removed by [download] as it unwinds. */
    fun cancel(key: String) {
        sessions[key]?.let { FFmpegKit.cancel(it) }
    }

    fun isRunning(key: String): Boolean = sessions.containsKey(key)

    /**
     * Builds the ffmpeg invocation.
     *
     * Headers are passed as one `-headers` argument before each input, and only for HTTP inputs -
     * ffmpeg rejects the option on a local path. They matter as much here as in the player: most
     * of these CDNs check a Referer and answer 403 without one.
     *
     * Subtitle and audio tracks the source supplied are added as further inputs and mapped in, so
     * an offline copy keeps them without a second download of its own.
     */
    private fun buildCommand(stream: StreamOption, target: File): String {
        val headerArg = stream.headers
            .takeIf { it.isNotEmpty() }
            ?.entries
            ?.joinToString("") { "${it.key}: ${it.value}\r\n" }
            ?.let { "-headers '$it'" }
            .orEmpty()

        fun input(url: String) = buildList {
            if (url.startsWith("http", ignoreCase = true) && headerArg.isNotEmpty()) {
                add(headerArg)
            }
            add("-i")
            add("\"$url\"")
        }.joinToString(" ")

        val subtitleTracks = stream.subtitles.filter { it.url.isNotBlank() }
        val audioTracks = stream.audioTracks.filter { it.url.isNotBlank() }

        val parts = buildList {
            add(input(stream.url))
            subtitleTracks.forEach { add(input(it.url)) }
            audioTracks.forEach { add(input(it.url)) }

            // Input 0 is the video. Its own audio and subtitles are taken where present - the
            // `?` makes each optional, since a video-only stream would otherwise fail the whole
            // command rather than simply having no audio.
            add("-map 0:v")
            add("-map 0:a?")
            add("-map 0:s?")

            // Then each sidecar track, numbered in the order they were added above.
            // Optional, like the video's own tracks. A sidecar URL that has expired would
            // otherwise fail the entire command and lose the video along with the subtitle.
            subtitleTracks.forEachIndexed { i, _ -> add("-map ${i + 1}:s?") }
            audioTracks.forEachIndexed { i, _ ->
                add("-map ${subtitleTracks.size + i + 1}:a")
            }

            // Video and audio are copied; subtitles are converted.
            //
            // `-c copy` across everything is what silently produced files with no subtitle
            // track. Source subtitles here are WebVTT, which Matroska cannot hold in its
            // original form, so copying it makes ffmpeg drop the stream rather than fail - the
            // download "succeeds" and the subtitles are simply absent. Converting to SubRip
            // costs nothing: it is text, and both formats carry the same cues.
            add("-c:v copy")
            add("-c:a copy")
            add("-c:s srt")
            add("-f matroska")

            subtitleTracks.forEachIndexed { i, track ->
                add("-metadata:s:s:$i \"title=${track.language.sanitised()}\"")
                add("-metadata:s:s:$i \"language=${track.language.sanitised()}\"")
            }
            audioTracks.forEachIndexed { i, track ->
                add("-metadata:s:a:$i \"title=${track.language.sanitised()}\"")
            }

            // Overwrite without asking; the file was deleted above, so this only guards against
            // ffmpeg stopping to prompt on a race.
            add("-y")
            add("\"${target.absolutePath}\"")
        }

        return parts.joinToString(" ")
    }

    /**
     * Turns ffmpeg's numeric return code into something readable.
     *
     * ffmpeg encodes its errors as a negated four-character tag, so a raw code like -1094995529
     * says nothing on its own - decoded it reads `INDA`, meaning invalid data. That distinction
     * matters when diagnosing a source: invalid data is a server answering with something that is
     * not media at all, usually an HTML error page served with a 200, which no retry will fix.
     */
    private fun Int.describeFfmpegError(): String {
        val tag = String(
            CharArray(4) { i -> ((-this shr (8 * i)) and 0xFF).toChar() },
        ).filter { it.isLetterOrDigit() || it == ' ' }

        return when (tag) {
            "INDA" -> "invalid data - the server did not return media"
            "0KOI" -> "end of file - the response was truncated"
            "!RTS" -> "stream not found"
            "4FF8", "8FF4" -> "protocol not found"
            else -> tag.ifBlank { "unknown" }
        }
    }

    /** Strips what would break out of a quoted ffmpeg argument. */
    private fun String.sanitised(): String =
        filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
            .trim()
            .ifBlank { "und" }

    private companion object {
        const val TAG = "WbFfmpeg"
    }
}
