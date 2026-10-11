package eu.kanade.tachiyomi.animesource.model

/**
 * A single episode entry produced by an extension.
 *
 * See the note in [SAnime] for why this package reproduces the Aniyomi ABI and
 * why the snake_case member names are required.
 */
@Suppress("PropertyName", "VariableNaming")
interface SEpisode : java.io.Serializable {

    var url: String

    var name: String

    var date_upload: Long

    /** 1-based where known, `-1f` when the extension cannot determine it. */
    var episode_number: Float

    var scanlator: String?

    /** True when the source marks the episode as filler. Lib 16. */
    var fillermark: Boolean

    /** The source's own synopsis of the episode. Lib 16. */
    var summary: String?

    /** The source's own still for the episode. Lib 16. */
    var preview_url: String?

    fun copyFrom(other: SEpisode) {
        name = other.name
        url = other.url
        date_upload = other.date_upload
        episode_number = other.episode_number
        scanlator = other.scanlator
        fillermark = other.fillermark
        summary = other.summary
        preview_url = other.preview_url
    }

    companion object {
        fun create(): SEpisode = SEpisodeImpl()
    }
}
