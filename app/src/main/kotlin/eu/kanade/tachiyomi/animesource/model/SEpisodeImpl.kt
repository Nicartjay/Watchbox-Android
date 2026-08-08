package eu.kanade.tachiyomi.animesource.model

/**
 * Default [SEpisode] holder handed to extensions by [SEpisode.create].
 *
 * See the note in [SAnime] for why this package mirrors the Aniyomi ABI.
 */
@Suppress("PropertyName", "VariableNaming")
class SEpisodeImpl : SEpisode {

    override var url: String = ""

    override var name: String = ""

    override var date_upload: Long = 0

    /** `-1f` means "unknown"; the host falls back to list position. */
    override var episode_number: Float = -1f

    override var scanlator: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SEpisode) return false
        return url == other.url
    }

    override fun hashCode(): Int = url.hashCode()

    override fun toString(): String = "SEpisode(url=$url, name=$name)"
}
