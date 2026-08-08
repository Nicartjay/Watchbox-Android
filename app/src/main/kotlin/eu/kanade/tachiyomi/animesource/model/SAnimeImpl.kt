package eu.kanade.tachiyomi.animesource.model

/**
 * Default [SAnime] holder handed to extensions by [SAnime.create].
 *
 * Extensions only ever touch this through the [SAnime] interface, so the class
 * itself carries no behaviour beyond storage and value equality.
 *
 * See the note in [SAnime] for why this package mirrors the Aniyomi ABI.
 */
@Suppress("PropertyName", "VariableNaming")
class SAnimeImpl : SAnime {

    override var url: String = ""

    override var title: String = ""

    override var artist: String? = null

    override var author: String? = null

    override var description: String? = null

    override var genre: String? = null

    override var status: Int = SAnime.UNKNOWN

    override var thumbnail_url: String? = null

    override var initialized: Boolean = false

    /** Identity is the source-relative [url]; titles are not unique. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SAnime) return false
        return url == other.url
    }

    override fun hashCode(): Int = url.hashCode()

    override fun toString(): String = "SAnime(url=$url, title=$title)"
}
