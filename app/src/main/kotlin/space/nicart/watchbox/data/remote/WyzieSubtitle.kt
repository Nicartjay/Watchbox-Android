package space.nicart.watchbox.data.remote

import kotlinx.serialization.Serializable

/**
 * One result from the curated list at `vidfast.vc/wyzie`.
 *
 * A far thinner reply than the other providers give - four fields, and no release name,
 * download count, format or sync information. That is not a gap to work around but what the
 * service is: it returns exactly one hand-picked subtitle per language rather than a catalogue
 * of competing releases, which is why the rows can be named by language alone without being
 * ambiguous.
 *
 * Its own type, like [BrightSubtitle], so the parsing can be tested against real captured
 * payloads. A field that fails to decode makes a subtitle silently absent, which is
 * indistinguishable from a title nobody has subtitled.
 */
@Serializable
data class WyzieSubtitle(
    /** The language as the service words it, e.g. `English`, `Portuguese (BR)`. */
    val display: String? = null,
    val language: String? = null,
    val url: String? = null,
    val encoding: String? = null,
) {
    fun toResult(): SubtitleResult? {
        val link = url?.takeIf { it.isNotBlank() } ?: return null
        val code = language?.takeIf { it.isNotBlank() } ?: return null

        return SubtitleResult(
            // The URL is the only identity on offer: there is no id field, and the language
            // would collide across the alternatives a fallback may later merge in.
            id = link,
            // The language is the whole name, and that is honest here rather than lazy. With one
            // entry per language there is nothing to disambiguate, and inventing a release name
            // the service never sent would imply a choice that does not exist.
            name = display?.takeIf { it.isNotBlank() } ?: code,
            language = code,
            languageName = display.orEmpty(),
            downloadUrl = link,
            // Declared rather than read: the reply carries no format and the URL has no
            // extension, but the payload is SubRip. Verified by fetching one.
            format = "srt",
            // No popularity signal exists, so every entry ranks equally and the shared ranking
            // leaves this list in the order the service chose. Claiming a count would put these
            // above or below other providers' results for no reason.
            downloads = 0L,
            // Not reported. Assuming false is the safer error: a hearing-impaired track shown as
            // ordinary is a mild surprise, where hiding one that is fine is a lost subtitle.
            hearingImpaired = false,
        )
    }
}
