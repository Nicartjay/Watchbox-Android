package space.nicart.watchbox.domain

import space.nicart.watchbox.extension.isShortReelsProvider

/**
 * A content boundary, not an artwork preference: short dramas must never be matched to
 * similarly named TMDB films or series. Applies even when a saved entry carries an old id.
 */
internal class SourceMetadataPolicy(
    private val isShortReelsSource: (Long, String) -> Boolean,
) {
    suspend fun <T> load(
        sourceId: Long,
        sourceName: String,
        fallback: T,
        request: suspend () -> T,
    ): T {
        if (isShortReelsProvider(sourceName) || isShortReelsSource(sourceId, sourceName)) {
            return fallback
        }
        return request()
    }
}
