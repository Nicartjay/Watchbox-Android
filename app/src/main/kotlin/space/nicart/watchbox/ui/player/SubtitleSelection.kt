package space.nicart.watchbox.ui.player

import space.nicart.watchbox.data.remote.SubtitleApi.Companion.toIso639_1
import space.nicart.watchbox.domain.SubtitleOption

/** Remember intent across episodes, not an index or the previous episode's subtitle file. */
data class SubtitlePreference(
    val enabled: Boolean = false,
    val language: String = "",
    val label: String = "",
    val preferEmbedded: Boolean = false,
    val sourceUrl: String = "",
    /** Pins duplicate in-file tracks only while the same episode and stream are playing. */
    val embeddedIndex: Int = -1,
    val embeddedMedia: Pair<String?, String?>? = null,
)

data class EmbeddedSubtitleTrack(val label: String, val language: String)

/** Availability is independent of the preference: Off must still let the user turn tracks on. */
internal val PlayerUiState.hasSubtitleTracks: Boolean
    get() = subtitles.isNotEmpty() || embeddedSubtitleTracks.isNotEmpty()

internal fun subtitleLanguageCode(language: String, label: String = ""): String =
    language.toIso639_1().ifBlank { label.toIso639_1() }

internal fun sourceSubtitlePreference(track: SubtitleOption): SubtitlePreference = SubtitlePreference(
    enabled = true,
    language = subtitleLanguageCode(track.language, track.label),
    label = track.label,
    sourceUrl = track.url,
)

private fun matchingSubtitle(
    preference: SubtitlePreference,
    tracks: List<EmbeddedSubtitleTrack>,
): Int {
    if (!preference.enabled) return -1
    val language = preference.language
    if (preference.label.isNotBlank()) {
        val byLabel = tracks.indexOfFirst {
            it.label.trim().equals(preference.label.trim(), ignoreCase = true) &&
                (language.isBlank() || subtitleLanguageCode(it.language, it.label) == language)
        }
        if (byLabel >= 0) return byLabel
    }
    return if (language.isBlank()) -1 else {
        tracks.indexOfFirst { subtitleLanguageCode(it.language, it.label) == language }
    }
}

/** Re-resolve against this episode's own URLs and freshly discovered in-file tracks. */
internal fun PlayerUiState.withSubtitleSelection(): PlayerUiState {
    val preference = subtitlePreference
    if (!preference.enabled) {
        return copy(selectedSubtitleIndex = -1, selectedEmbeddedSubtitleIndex = -1)
    }

    val sourceIndex = subtitles.indexOfFirst {
        preference.sourceUrl.isNotBlank() && it.url == preference.sourceUrl
    }.takeIf { it >= 0 } ?: matchingSubtitle(
        preference,
        subtitles.map { EmbeddedSubtitleTrack(it.label, it.language) },
    )
    val pinned = preference.embeddedIndex.takeIf {
        preference.embeddedMedia == (episode?.url to selectedStream?.url) &&
            embeddedSubtitleTracks.getOrNull(it)?.let { track ->
                track.label == preference.label &&
                    subtitleLanguageCode(track.language, track.label) == preference.language
            } == true
    }
    val embeddedIndex = pinned ?: matchingSubtitle(preference, embeddedSubtitleTracks)

    return when {
        embeddedIndex >= 0 && (preference.preferEmbedded || sourceIndex < 0) ->
            copy(selectedSubtitleIndex = -1, selectedEmbeddedSubtitleIndex = embeddedIndex)
        sourceIndex >= 0 -> copy(selectedSubtitleIndex = sourceIndex, selectedEmbeddedSubtitleIndex = -1)
        else -> copy(selectedSubtitleIndex = -1, selectedEmbeddedSubtitleIndex = -1)
    }
}
