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
 * `github.com/yuzono/anime-repo`, which ships lib versions 14 and 16.
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

    /** Wide artwork, when the source has one. */
    var background_url: String?

    /** Stored for the ABI only; see [AnimeUpdateStrategy]. */
    var update_strategy: AnimeUpdateStrategy

    /**
     * Whether this entry holds episodes or seasons. Added in lib 16.
     *
     * A [FetchType.Seasons] entry has no episodes of its own: `getSeasonList` returns one
     * [SAnime] per season, and each of those is asked for its episodes.
     */
    var fetch_type: FetchType

    /** This entry's number as a season of another, or `-1` when it is not one. Lib 16. */
    var season_number: Double

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
        if (other.background_url != null) background_url = other.background_url
        status = other.status
        update_strategy = other.update_strategy
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
