package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode

/**
 * A source that produces several logical sources from one class.
 *
 * The loader checks for this after instantiating each declared class: if the
 * instance is a factory it expands to [createSources] instead of being used
 * directly. Multi-region and multi-mirror extensions rely on it.
 *
 * See the note in [eu.kanade.tachiyomi.animesource.model.SAnime] for why this
 * package reproduces the Aniyomi ABI.
 */
interface AnimeSourceFactory {
    fun createSources(): List<AnimeSource>
}

/**
 * A source whose traffic should not count against metered-connection limits,
 * e.g. a self-hosted server on the local network.
 */
interface UnmeteredSource

/**
 * Marks a source that can turn a shared URL back into a library entry.
 * Rarely implemented; the host treats it as optional.
 */
interface ResolvableAnimeSource : AnimeSource {

    fun getUriType(uri: String): UriType

    suspend fun getAnime(uri: String): SAnime?

    suspend fun getEpisode(uri: String): SEpisode?
}

sealed interface UriType {
    data object Anime : UriType
    data object Episode : UriType
    data object Unknown : UriType
}
