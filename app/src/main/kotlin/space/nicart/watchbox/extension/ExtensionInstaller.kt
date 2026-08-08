package space.nicart.watchbox.extension

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import space.nicart.watchbox.extension.loader.ExtensionLoader
import space.nicart.watchbox.extension.model.Extension
import space.nicart.watchbox.extension.model.InstallStep
import java.io.File

/**
 * Downloads extension APKs into the app's private storage.
 *
 * No system installer, no `REQUEST_INSTALL_PACKAGES`, no user prompt: the APK is
 * written to `filesDir/extensions` and loaded straight from there. The trade-off
 * is that these extensions are private to this app rather than shared with other
 * Aniyomi-family clients, which is the right default for a personal build.
 *
 * The download lands in the cache first and is only moved into place once it has
 * been verified as a real, matching extension. A half-written or wrong APK in the
 * extensions directory would otherwise fail on every subsequent startup.
 */
class ExtensionInstaller(
    private val context: Context,
    private val client: HttpClient,
) {

    fun install(extension: Extension.Available): Flow<InstallStep> = flow {
        emit(InstallStep.Pending)

        val staging = File(context.cacheDir, "ext_download_${extension.pkgName}.apk")
        val target = ExtensionLoader.privateFile(context, extension.pkgName)

        try {
            emit(InstallStep.Downloading)

            val response = client.get(extension.apkUrl)
            if (!response.status.isSuccess()) {
                error("Download failed with HTTP ${response.status.value}")
            }

            staging.parentFile?.mkdirs()
            staging.outputStream().use { out ->
                response.bodyAsChannel().toInputStream().use { input ->
                    input.copyTo(out, DEFAULT_BUFFER_SIZE)
                }
            }

            emit(InstallStep.Installing)
            verify(staging, extension)

            // Replace atomically where possible so a failed move cannot leave a
            // truncated APK behind.
            if (target.exists() && !target.delete()) {
                error("Could not replace the existing extension file")
            }
            if (!staging.renameTo(target)) {
                staging.copyTo(target, overwrite = true)
                staging.delete()
            }
            target.setReadOnly()

            emit(InstallStep.Installed)
        } catch (e: Throwable) {
            staging.delete()
            lastError = e.message ?: e.javaClass.simpleName
            emit(InstallStep.Error)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Confirms the download really is the requested extension.
     *
     * Guards against a repo serving an unrelated or corrupt APK: without this the
     * file would sit in the extensions directory failing to parse forever.
     */
    private fun verify(file: File, expected: Extension.Available) {
        @Suppress("DEPRECATION")
        val info = context.packageManager
            .getPackageArchiveInfo(file.absolutePath, android.content.pm.PackageManager.GET_CONFIGURATIONS)
            ?: error("Downloaded file is not a valid APK")

        if (info.packageName != expected.pkgName) {
            error("APK is ${info.packageName}, expected ${expected.pkgName}")
        }

        val isExtension = info.reqFeatures.orEmpty()
            .any { it.name == "tachiyomi.animeextension" }
        if (!isExtension) {
            error("APK is not an anime extension")
        }
    }

    fun uninstall(pkgName: String): Boolean {
        val file = ExtensionLoader.privateFile(context, pkgName)
        return !file.exists() || file.delete()
    }

    /** Message from the most recent failure, for the UI to surface. */
    @Volatile
    var lastError: String? = null
        private set
}
