package space.nicart.watchbox.extension.loader

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import space.nicart.watchbox.extension.model.Extension
import space.nicart.watchbox.extension.model.LoadResult
import java.io.File
import java.security.MessageDigest

/**
 * Discovers extension APKs and instantiates the sources inside them.
 *
 * Two channels are searched:
 *
 *  * **Private** — APKs this app downloaded into `filesDir/extensions`. This is
 *    the default path because it needs no install prompt and no
 *    `REQUEST_INSTALL_PACKAGES` / `QUERY_ALL_PACKAGES` permission.
 *  * **Shared** — extensions installed system-wide, which are also visible to
 *    other Aniyomi-family apps. Only reachable when the platform lets us
 *    enumerate packages, so it is best-effort.
 *
 * An extension is identified by the `tachiyomi.animeextension` `uses-feature`
 * tag, and its source classes come from the `tachiyomi.animeextension.class`
 * manifest metadata. Both are fixed by the ecosystem, not chosen here.
 */
object ExtensionLoader {

    private const val EXTENSION_FEATURE = "tachiyomi.animeextension"
    private const val METADATA_SOURCE_CLASS = "tachiyomi.animeextension.class"
    private const val METADATA_SOURCE_FACTORY = "tachiyomi.animeextension.factory"
    private const val METADATA_NSFW = "tachiyomi.animeextension.nsfw"

    /**
     * Supported extension library range.
     *
     * Every extension in the default repo reports 14. The upper bound stops at
     * 15 deliberately: lib 16 made `getSeasonList` abstract and replaced the
     * video contract with `Hoster`, so a 16 extension would call members this
     * app does not implement and die with `AbstractMethodError` mid-playback.
     * Refusing to load it is the honest failure.
     */
    const val LIB_VERSION_MIN = 12.0
    const val LIB_VERSION_MAX = 15.0

    private const val PRIVATE_DIR = "extensions"
    private const val PRIVATE_SUFFIX = ".apk"

    @Suppress("DEPRECATION")
    private val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
        PackageManager.GET_META_DATA or
        PackageManager.GET_SIGNATURES or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            0
        }

    fun privateDir(context: Context): File =
        File(context.filesDir, PRIVATE_DIR).apply { mkdirs() }

    fun privateFile(context: Context, pkgName: String): File =
        File(privateDir(context), "$pkgName$PRIVATE_SUFFIX")

    /** Loads every extension found through either channel, concurrently. */
    suspend fun loadExtensions(context: Context): List<LoadResult> =
        withContext(Dispatchers.IO) {
            val candidates = (loadPrivateCandidates(context) + loadSharedCandidates(context))
                // A shared install and a private copy of the same package can
                // coexist; keep whichever has the higher version code.
                .groupBy { it.packageInfo.packageName }
                .map { (_, dupes) -> dupes.maxBy { it.versionCode } }

            coroutineScope {
                candidates
                    .map { candidate -> async { loadExtension(context, candidate) } }
                    .awaitAll()
            }
        }

    // ------------------------------------------------------------ discovery

    private data class Candidate(
        val packageInfo: PackageInfo,
        val isShared: Boolean,
        val apkPath: String,
    ) {
        val versionCode: Long
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
    }

    private fun loadPrivateCandidates(context: Context): List<Candidate> {
        val dir = privateDir(context)
        val files = dir.listFiles { file -> file.name.endsWith(PRIVATE_SUFFIX) }
            ?: return emptyList()

        return files.mapNotNull { file ->
            // Android 14+ refuses to load a writable APK.
            runCatching { file.setReadOnly() }

            val info = runCatching {
                context.packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
            }.getOrNull() ?: return@mapNotNull null

            if (!isExtension(info)) return@mapNotNull null

            // getPackageArchiveInfo leaves these null on newer platforms, which
            // breaks both classloading and icon lookup.
            info.applicationInfo?.apply {
                sourceDir = file.absolutePath
                publicSourceDir = file.absolutePath
            }

            Candidate(info, isShared = false, apkPath = file.absolutePath)
        }
    }

    private fun loadSharedCandidates(context: Context): List<Candidate> {
        val installed = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getInstalledPackages(
                    PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstalledPackages(PACKAGE_FLAGS)
            }
        }.getOrDefault(emptyList())

        return installed
            .filter { isExtension(it) }
            .mapNotNull { info ->
                val path = info.applicationInfo?.sourceDir ?: return@mapNotNull null
                Candidate(info, isShared = true, apkPath = path)
            }
    }

    private fun isExtension(info: PackageInfo): Boolean =
        info.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE }

    // -------------------------------------------------------------- loading

    private fun loadExtension(context: Context, candidate: Candidate): LoadResult {
        val info = candidate.packageInfo
        val pkgName = info.packageName
        val appInfo = info.applicationInfo
            ?: return LoadResult.Error(pkgName, "No application info")

        val versionName = info.versionName.orEmpty()
        if (versionName.isEmpty()) {
            return LoadResult.Error(pkgName, "Missing version name")
        }

        // versionName is "<libVersion>.<extVersion>", e.g. "14.46" -> 14.0.
        val libVersion = versionName.substringBeforeLast('.').toDoubleOrNull()
            ?: return LoadResult.Error(pkgName, "Unrecognised version '$versionName'")

        if (libVersion < LIB_VERSION_MIN || libVersion > LIB_VERSION_MAX) {
            return LoadResult.Error(
                pkgName,
                "Unsupported extension API $libVersion " +
                    "(supported: $LIB_VERSION_MIN-$LIB_VERSION_MAX)",
            )
        }

        val extName = runCatching {
            context.packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault(pkgName).removePrefix("Aniyomi: ")

        val isNsfw = appInfo.metaData?.getInt(METADATA_NSFW) == 1

        val classList = appInfo.metaData?.getString(METADATA_SOURCE_CLASS)
        if (classList.isNullOrBlank()) {
            return LoadResult.Error(pkgName, "No source class declared")
        }

        val classLoader = createClassLoader(context, candidate)
            ?: return LoadResult.Error(pkgName, "Could not open extension dex")

        val declaredClasses = classList.split(";")
            .map(String::trim)
            .filter(String::isNotEmpty)
            // A leading dot is shorthand for the package name.
            .map { if (it.startsWith(".")) pkgName + it else it }

        val sources = mutableListOf<AnimeSource>()
        for (className in declaredClasses) {

            val instance = runCatching {
                Class.forName(className, false, classLoader)
                    .getDeclaredConstructor()
                    .newInstance()
            }.getOrElse { error ->
                return LoadResult.Error(
                    pkgName,
                    "Could not instantiate $className: ${error.rootMessage()}",
                )
            }

            when (instance) {
                is AnimeSource -> sources += instance
                is AnimeSourceFactory -> sources += runCatching { instance.createSources() }
                    .getOrElse {
                        return LoadResult.Error(pkgName, "Factory failed: ${it.rootMessage()}")
                    }

                else -> return LoadResult.Error(
                    pkgName,
                    "Unsupported source type ${instance.javaClass.name}",
                )
            }
        }

        if (sources.isEmpty()) {
            return LoadResult.Error(pkgName, "Extension declared no usable sources")
        }

        val langs = sources.filterIsInstance<AnimeCatalogueSource>()
            .map { it.lang }
            .distinct()
        val lang = when (langs.size) {
            0 -> ""
            1 -> langs.first()
            else -> "all"
        }

        return LoadResult.Success(
            Extension.Installed(
                name = extName,
                pkgName = pkgName,
                versionName = versionName,
                versionCode = candidate.versionCode,
                libVersion = libVersion,
                lang = lang,
                isNsfw = isNsfw,
                sources = sources,
                icon = runCatching {
                    context.packageManager.getApplicationIcon(appInfo)
                }.getOrNull(),
                isShared = candidate.isShared,
            ),
        )
    }

    /**
     * Builds the classloader, preferring child-first resolution.
     *
     * Child-first is right for the common case, but a few extensions bundle a
     * class that collides with one the host also needs, and only link under
     * parent-first. Rather than fail those outright, fall back on [LinkageError].
     */
    private fun createClassLoader(context: Context, candidate: Candidate): ClassLoader? {
        val path = candidate.apkPath
        val nativeLibDir = candidate.packageInfo.applicationInfo?.nativeLibraryDir

        return runCatching {
            ChildFirstClassLoader(path, nativeLibDir, context.classLoader)
        }.recoverCatching {
            dalvik.system.PathClassLoader(path, nativeLibDir, context.classLoader)
        }.getOrNull()
    }

    // ----------------------------------------------------------- signatures

    /** Lowercase hex SHA-256 of each signing certificate. */
    @Suppress("DEPRECATION")
    fun signatureHashes(info: PackageInfo): List<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo
            when {
                signingInfo == null -> null
                signingInfo.hasMultipleSigners() -> signingInfo.apkContentsSigners
                else -> signingInfo.signingCertificateHistory
            }
        } else {
            info.signatures
        }

        return signatures.orEmpty().mapNotNull { signature ->
            runCatching {
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }
    }

    private fun Throwable.rootMessage(): String {
        var cause: Throwable = this
        while (cause.cause != null && cause.cause != cause) cause = cause.cause!!
        return cause.message ?: cause.javaClass.simpleName
    }
}
