package space.nicart.watchbox.data.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import space.nicart.watchbox.BuildConfig
import java.io.File

/** Progress reported while fetching and handing off an update. */
sealed interface UpdateDownload {
    data class Progress(val percent: Int) : UpdateDownload
    data object Launching : UpdateDownload
    data class Failed(val message: String) : UpdateDownload
}

/**
 * Downloads an update APK and hands it to the system installer.
 *
 * The APK goes to `cacheDir/updates`, which is the only directory exposed
 * through the FileProvider. The extensions directory is deliberately not
 * shareable — a self-update needs the system installer, but extensions never do.
 *
 * Android always shows its own install confirmation, so this cannot install
 * anything without the user agreeing.
 */
class UpdateInstaller(
    private val context: Context,
    private val client: HttpClient,
) {

    fun downloadAndInstall(update: AppUpdate): Flow<UpdateDownload> = flow {
        emit(UpdateDownload.Progress(0))

        val dir = File(context.cacheDir, DIR).apply { mkdirs() }

        // One file per version, and any older download is cleared first so the
        // cache cannot accumulate stale APKs.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "watchbox-${update.versionName}.apk")

        try {
            val response = client.get(update.apkUrl)
            if (!response.status.isSuccess()) {
                error("Download failed with HTTP ${response.status.value}")
            }

            val total = update.apkSizeBytes.takeIf { it > 0 }
            var written = 0L
            var lastPercent = 0

            target.outputStream().use { out ->
                response.bodyAsChannel().toInputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        written += read

                        if (total != null) {
                            val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                            // Emit sparingly; a per-buffer emission would flood
                            // the UI with recompositions.
                            if (percent >= lastPercent + 2) {
                                lastPercent = percent
                                emit(UpdateDownload.Progress(percent))
                            }
                        }
                    }
                }
            }

            if (target.length() == 0L) error("Downloaded file was empty")

            // Sanity-check the package before handing it to the installer, so a
            // corrupt or wrong download fails here with a clear message rather
            // than as an opaque "app not installed" from the system.
            verify(target)

            emit(UpdateDownload.Progress(100))
            emit(UpdateDownload.Launching)
            launchInstaller(target)
        } catch (e: Throwable) {
            target.delete()
            emit(UpdateDownload.Failed(e.message ?: e.javaClass.simpleName))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Confirms the download is genuinely our release APK.
     *
     * Compared against the *release* application id rather than the running
     * package: debug builds append ".debug", so using `context.packageName` here
     * rejected every real update. On a debug build the installer will therefore
     * offer the release app side-by-side rather than as an in-place update, which
     * is the only thing Android permits across differing package names.
     */
    private fun verify(file: File) {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: error("Downloaded file is not a valid APK")

        val expected = BuildConfig.RELEASE_APPLICATION_ID
        if (info.packageName != expected) {
            error("APK is ${info.packageName}, expected $expected")
        }
    }

    private fun launchInstaller(file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private companion object {
        const val DIR = "updates"
    }
}
