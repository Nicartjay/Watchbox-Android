package eu.kanade.tachiyomi.animesource.model

/**
 * Whether a library update should refetch an entry.
 *
 * Set by sources on [SAnime.update_strategy]. This app has no library updates, so the value
 * is stored and never read. It exists because extensions set it: four lib 14 extensions in the
 * default repo do, and each such call raised `NoSuchMethodError` while it was missing.
 *
 * See the note in [SAnime] for why this package reproduces the Aniyomi ABI.
 */
enum class AnimeUpdateStrategy {
    ALWAYS_UPDATE,

    /** For entries known never to change, such as a film. */
    ONLY_FETCH_ONCE,
}
