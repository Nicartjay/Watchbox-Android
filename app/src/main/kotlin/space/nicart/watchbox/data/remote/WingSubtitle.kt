package space.nicart.watchbox.data.remote

import kotlinx.serialization.Serializable

/**
 * Response from the keyless subtitle catalogue at `subs.wing.st`.
 *
 * Its download URLs deliberately have no file extension, so [type] must be preserved. The
 * player uses the cached file's extension to choose a parser; treating one of these SubRip
 * payloads as WebVTT produces a selectable track that never renders.
 */
@Serializable
data class WingSubtitleResponse(
    val total: Int = 0,
    val subtitles: List<WingSubtitle> = emptyList(),
)

@Serializable
data class WingSubtitle(
    val id: String? = null,
    val language: String? = null,
    val url: String? = null,
    val type: String? = null,
    val display: String? = null,
) {
    fun toResult(): SubtitleResult? {
        val link = url?.takeIf { it.isNotBlank() } ?: return null
        val code = language?.takeIf { it.isNotBlank() } ?: return null

        return SubtitleResult(
            id = id?.takeIf { it.isNotBlank() } ?: link,
            name = display?.takeIf { it.isNotBlank() } ?: code,
            language = code,
            languageName = code,
            downloadUrl = link,
            format = type?.trim()?.lowercase()?.removePrefix(".")
                ?.takeIf { it.isNotBlank() }
                ?: "srt",
            downloads = 0L,
            hearingImpaired = false,
        )
    }
}
