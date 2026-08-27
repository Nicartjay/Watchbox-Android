package space.nicart.watchbox.ui.player

import java.util.Locale

/**
 * An audio track carried inside the stream itself.
 *
 * Distinct from a "dub" in [StreamFacets], which is a label parsed off a source's stream
 * name and selects a different URL. This is one of several tracks in a single file, chosen
 * by overriding the player's track selection - no reload, no buffering.
 */
internal data class EmbeddedAudioTrack(
    /** What the panel shows. */
    val label: String,
    /** ISO 639 code from the container, or empty when it names none. */
    val language: String,
)

/**
 * Human-readable name for an audio track.
 *
 * Prefers the container's own label, since a release that bothers to name a track
 * ("Commentary", "English 5.1") says more than a language code can. Falls back to the
 * language rendered in the reader's own locale, because a bare "jpn" is not what someone
 * choosing between two tracks wants to read. [fallback] covers a track with neither.
 */
internal fun audioTrackLabel(
    rawLabel: String?,
    language: String?,
    fallback: String,
): String {
    rawLabel?.takeIf { it.isNotBlank() }?.let { return it }

    val code = language?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?: return fallback

    // Locale renders a code it recognises as a name and echoes one it does not, so the
    // blank check is what distinguishes "no idea" from a genuine answer.
    return Locale.forLanguageTag(code).displayLanguage
        .takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
        ?: code
}

/**
 * Index of the track matching [preferred], or -1 when none does.
 *
 * Compared on the leading subtag alone, so a stored "en" still matches a track tagged
 * "en-US" and the preference survives releases that tag their audio more precisely than
 * the one it was set from. A blank preference matches nothing, which leaves Media3's own
 * default in place rather than forcing the first track.
 *
 * Falls back to the label because a container need not tag its audio at all. Such a track
 * is stored by label, and matching it here is what makes choosing one stick - within that
 * release, at least, which is as far as an untagged track can carry.
 */
internal fun List<EmbeddedAudioTrack>.indexOfLanguage(preferred: String): Int {
    val wanted = preferred.primarySubtag()
    if (wanted.isEmpty()) return -1

    val byLanguage = indexOfFirst {
        it.language.isNotBlank() && it.language.primarySubtag() == wanted
    }
    if (byLanguage >= 0) return byLanguage

    return indexOfFirst { it.label.trim().lowercase() == preferred.trim().lowercase() }
}

private fun String.primarySubtag(): String =
    trim().substringBefore('-').substringBefore('_').lowercase()

/**
 * Names a track from the container's own metadata, falling back to what the source said.
 *
 * A track merged in from a separate playlist carries no label and frequently no language,
 * because that information lived in the master playlist rather than in the media. The
 * extension read it there, so its naming is all that identifies such a track - without
 * this a row read "Audio track 2" for something the source called "English".
 *
 * [suppliedLabel] and [suppliedLanguage] are null for a track that came out of the file
 * itself, which is why the container is consulted first.
 */
internal fun mergedAudioTrack(
    containerLabel: String?,
    containerLanguage: String?,
    suppliedLabel: String?,
    suppliedLanguage: String?,
    fallback: String,
): EmbeddedAudioTrack = EmbeddedAudioTrack(
    label = audioTrackLabel(
        rawLabel = containerLabel ?: suppliedLabel,
        language = containerLanguage ?: suppliedLanguage,
        fallback = fallback,
    ),
    // "und" is what a container writes when it does not know, so it must not beat a real
    // answer from the source - otherwise the stored preference matches nothing and the
    // choice fails to carry to the next episode.
    language = containerLanguage?.takeIf { it.isNotBlank() && !it.equals("und", true) }
        ?: suppliedLanguage.orEmpty(),
)

/**
 * Index into the track-group list where the source's own audio playlists begin.
 *
 * Merging appends its sources after the video, so the tail of the group list lines up with
 * the stream's audio list - the same arrangement sideloaded subtitles rely on. Clamped at
 * zero because the groups may not have arrived yet, and a negative offset would pair a
 * file's own track with a label belonging to something else.
 */
internal fun mergedAudioOffset(groupCount: Int, suppliedCount: Int): Int =
    (groupCount - suppliedCount).coerceAtLeast(0)
