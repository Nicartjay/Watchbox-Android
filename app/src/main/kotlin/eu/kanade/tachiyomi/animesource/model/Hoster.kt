package eu.kanade.tachiyomi.animesource.model

/**
 * One server offering an episode, with the videos it carries. Added in lib 16.
 *
 * Lib 16 split stream lookup in two: a source lists hosters for an episode, then videos per
 * hoster. [videoList] is filled in when the source already has the videos, and null when the
 * host has to ask for them with `getVideoList(hoster)`. [lazy] marks a hoster the source
 * considers too expensive to load before it is needed.
 *
 * See the note in [SAnime] for why this package reproduces the Aniyomi ABI.
 */
class Hoster(
    val hosterUrl: String = "",
    val hosterName: String = "",
    val videoList: List<Video>? = null,
    val internalData: String = "",
    val lazy: Boolean = false,
) {

    override fun toString(): String = "Hoster(hosterName=$hosterName, lazy=$lazy)"

    companion object {
        /** Name of the single pseudo-hoster that wraps a plain video list. */
        const val NO_HOSTER_LIST = "no_hoster_list"

        /**
         * Wraps a flat video list as one unnamed hoster.
         *
         * Every lib 14 extension in the default repo bundles a shim that calls this from its
         * own `getHosterList`, so it is referenced far more widely than lib 16 alone suggests.
         */
        fun List<Video>.toHosterList(): List<Hoster> = listOf(
            Hoster(hosterUrl = "", hosterName = NO_HOSTER_LIST, videoList = this),
        )
    }
}
