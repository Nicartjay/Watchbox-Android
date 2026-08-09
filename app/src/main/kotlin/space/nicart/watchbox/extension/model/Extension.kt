package space.nicart.watchbox.extension.model

import android.graphics.drawable.Drawable
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource

/**
 * An extension the app knows about, in one of three states.
 *
 * [Installed] is the only variant that carries live [AnimeSource] instances;
 * the other two are metadata only.
 */
sealed interface Extension {

    val name: String
    val pkgName: String
    val versionName: String
    val versionCode: Long
    val libVersion: Double
    val lang: String
    val isNsfw: Boolean

    data class Installed(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val lang: String,
        override val isNsfw: Boolean,
        val sources: List<AnimeSource>,
        val icon: Drawable? = null,
        /** True when installed system-wide rather than into our private dir. */
        val isShared: Boolean = false,
        /** Set once the repo index reports a newer build. */
        val hasUpdate: Boolean = false,
        /** True when the extension is installed but no longer in any repo. */
        val isObsolete: Boolean = false,
    ) : Extension {

        /**
         * Whether any bundled source exposes its own preference screen.
         *
         * Checked before offering a settings button so it never opens an empty
         * screen: implementing [ConfigurableAnimeSource] is optional, and plenty
         * of sources have nothing to configure.
         */
        fun hasConfigurableSources(): Boolean =
            sources.any { it is ConfigurableAnimeSource }
    }

    data class Available(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val lang: String,
        override val isNsfw: Boolean,
        val apkName: String,
        val iconUrl: String,
        val apkUrl: String,
        val sources: List<AvailableSource> = emptyList(),
        /**
         * Which repository listed this extension.
         *
         * Recorded so the list can be filtered by origin, and so a package offered
         * by several repositories can be attributed to the one that won.
         */
        val repoUrl: String = "",
    ) : Extension

    /**
     * Loaded but rejected because its signature is not trusted.
     *
     * Kept as a distinct state rather than silently skipped so the UI can offer
     * an explicit opt-in: loading one runs third-party code inside this process.
     */
    data class Untrusted(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val lang: String = "",
        override val isNsfw: Boolean = false,
        val signatureHash: String,
    ) : Extension
}

/** A source advertised by the repo index, before the extension is installed. */
data class AvailableSource(
    val id: Long,
    val name: String,
    val lang: String,
    val baseUrl: String,
)

/** Outcome of trying to load one installed package. */
sealed interface LoadResult {
    data class Success(val extension: Extension.Installed) : LoadResult
    data class Untrusted(val extension: Extension.Untrusted) : LoadResult
    data class Error(val pkgName: String, val reason: String) : LoadResult
}

/** Progress reported while installing. */
enum class InstallStep {
    Idle,
    Pending,
    Downloading,
    Installing,
    Installed,
    Error,
    ;

    val isCompleted: Boolean get() = this == Installed || this == Error
}
