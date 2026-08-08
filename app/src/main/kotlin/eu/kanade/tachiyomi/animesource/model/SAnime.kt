package eu.kanade.tachiyomi.animesource.model

/**
 * Anime metadata as produced by an extension.
 *
 * ## Why this file exists
 *
 * Aniyomi-style extension APKs are compiled `compileOnly` against the Aniyomi
 * "extensions-lib" and do **not** bundle it. At runtime their classes link
 * against whatever the host application puts on the classpath, so the host is
 * the library provider. The published `aniyomi-extensions-lib` artifact cannot
 * be used for this: every method body in it is `throw Exception("Stub!")`, in
 * the same way `android.jar` is a compile-only facade.
 *
 * This package is therefore a from-scratch implementation of that ABI, written
 * against the signatures observed in real extension APKs from
 * `github.com/yuzono/anime-repo` (all of which report lib version 14).
 * The fully-qualified names, member names and signatures are fixed by the ABI
 * and cannot be renamed or shaded; the behaviour behind them is ours.
 *
 * ## Field naming
 *
 * The snake_case property names are part of the ABI (extensions call
 * `setThumbnail_url`, `setDate_upload`, and so on), so the Kotlin naming
 * convention is deliberately not followed here.
 */
@Suppress("PropertyName", "VariableNaming")
interface SAnime : java.io.Serializable {

    var url: String

    var title: String

    var artist: String?

    var author: String?

    var description: String?

    /** Comma-separated, conventionally joined with ", ". */
    var genre: String?

    var status: Int

    var thumbnail_url: String?

    /** True once details have been fetched, so the host can avoid refetching. */
    var initialized: Boolean

    fun getGenres(): List<String>? {
        if (genre.isNullOrBlank()) return null
        return genre?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
    }

    fun copyFrom(other: SAnime) {
        if (other.author != null) author = other.author
        if (other.artist != null) artist = other.artist
        if (other.description != null) description = other.description
        if (other.genre != null) genre = other.genre
        if (other.thumbnail_url != null) thumbnail_url = other.thumbnail_url
        status = other.status
        if (!initialized) initialized = other.initialized
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        fun create(): SAnime = SAnimeImpl()
    }
}
