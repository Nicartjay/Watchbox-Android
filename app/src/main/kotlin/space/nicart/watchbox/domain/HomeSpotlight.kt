package space.nicart.watchbox.domain

/**
 * Shorts stay in the feed's own section, never in the hero/Featured selection.
 * Filter before sampling so normal titles still get every available spotlight slot.
 */
internal fun homeSpotlightCandidates(
    rows: List<AnimeRow>,
    isShortReelsSource: (Long, String) -> Boolean,
): List<AnimeCard> = rows.asSequence()
    .flatMap { it.items.asSequence() }
    .filterNot { isShortReelsSource(it.sourceId, it.sourceName) }
    .distinctBy { it.key }
    .toList()
