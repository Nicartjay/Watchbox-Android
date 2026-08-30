package space.nicart.watchbox.data.remote

import kotlinx.serialization.Serializable

/**
 * One result from the keyless aggregator at `subs.bright67.online`.
 *
 * A small subset of what it returns. The service sends around fifty fields per result, most of
 * them null in practice, and adds more without notice - so this reads only what identifies a
 * release and what says whether it will be in sync, and relies on `ignoreUnknownKeys` for the
 * rest. A strict reader would fail on the next field they add and lose every subtitle at once.
 *
 * Its own type rather than a private class inside [SubtitleApi] so the parsing can be tested
 * against real captured payloads: a field that fails to decode makes a subtitle silently absent
 * rather than raising anything, which looks exactly like a title nobody has subtitled.
 */
@Serializable
data class BrightSubtitle(
    val id: String? = null,
    val url: String? = null,
    val format: String? = null,
    val language: String? = null,
    val languageName: String? = null,
    /** The language as the service words it, e.g. `English`. Not a release name. */
    val display: String? = null,
    /** The release this was timed against, which is what tells one row from another. */
    val release: String? = null,
    val fileName: String? = null,
    val downloadCount: Long? = null,
    val isHearingImpaired: Boolean? = null,
    /**
     * The service's own confidence that this matches the episode asked for.
     *
     * Worth carrying because it is the one thing OpenSubtitles' own API does not report: a
     * subtitle matched by id alone is routinely timed against a different cut.
     */
    val syncConfidence: String? = null,
    val isTrusted: Boolean? = null,
    val isMachineTranslated: Boolean? = null,
) {
    /**
     * The best available name for this release.
     *
     * Prefers the release, because [display] is only the language and naming by it would make
     * every row for a language read identically and be unpickable.
     */
    fun bestName(): String =
        release?.takeIf { it.isNotBlank() }
            ?: fileName?.takeIf { it.isNotBlank() }
            ?: display?.takeIf { it.isNotBlank() }
            ?: "Subtitle"

    /**
     * The name with any warning appended.
     *
     * A machine translation and an uncertain sync are both marked rather than hidden: for a
     * language with no human subtitle at all the former is the only option, and the note is what
     * lets that choice be made knowingly.
     */
    fun displayName(): String {
        val notes = buildList {
            if (isMachineTranslated == true) add("auto-translated")
            if (syncConfidence == "unlikely") add("sync uncertain")
        }
        return if (notes.isEmpty()) bestName() else "${bestName()} (${notes.joinToString(", ")})"
    }

    fun toResult(): SubtitleResult? {
        val link = url?.takeIf { it.isNotBlank() } ?: return null

        return SubtitleResult(
            id = id ?: link,
            name = displayName(),
            language = language.orEmpty(),
            languageName = languageName ?: display.orEmpty(),
            downloadUrl = link,
            format = format?.takeIf { it.isNotBlank() } ?: "srt",
            // Trusted uploads are promoted through the shared ranking, which sorts on this.
            downloads = (downloadCount ?: 0L) + if (isTrusted == true) BRIGHT_TRUSTED_BONUS else 0L,
            hearingImpaired = isHearingImpaired == true,
        )
    }
}

/**
 * Download-count bonus given to an upload the aggregator marks trusted.
 *
 * Folded into the count so it sorts through the shared ranking rather than needing a rule of its
 * own. Deliberately modest: a heavily-downloaded untrusted file is usually a safer bet than an
 * obscure trusted one, so this breaks near-ties rather than overriding popularity.
 */
internal const val BRIGHT_TRUSTED_BONUS = 5_000L
