package eu.kanade.tachiyomi.animesource.model

/**
 * What an [SAnime] holds. Added in lib 16.
 *
 * See the note in [SAnime] for why this package reproduces the Aniyomi ABI.
 */
enum class FetchType {
    /** The entry is split into seasons: `getSeasonList` returns one [SAnime] per season. */
    Seasons,

    /** The entry holds episodes directly, as every lib 14 entry does. */
    Episodes,
}
