package space.nicart.watchbox.ui.home

import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.domain.AnimeRow
import space.nicart.watchbox.extension.isShortReelsProvider
import space.nicart.watchbox.ui.browse.SourceEntry

internal data class PhoneHomeRows(
    val standard: List<AnimeRow>,
    val shortReels: List<AnimeCard>,
)

/** One phone-only short-drama shelf instead of repeating its titles in ordinary source rows. */
internal fun phoneHomeRows(rows: List<AnimeRow>, sources: List<SourceEntry>): PhoneHomeRows {
    val shortIds = sources.filter { it.isShortReels }.map { it.id }.toSet()
    val (shorts, standard) = rows.partition {
        it.sourceId in shortIds || isShortReelsProvider(it.sourceName)
    }
    return PhoneHomeRows(
        standard = standard,
        shortReels = alternatingShortReels(shorts),
    )
}

/** One fresh title per provider per round; an empty provider never leaves a blank slot. */
private fun alternatingShortReels(rows: List<AnimeRow>): List<AnimeCard> {
    val providers = rows.groupBy { it.sourceId }.values.map { providerRows ->
        providerRows.asSequence().flatMap { it.items.asSequence() }.iterator()
    }
    val titles = ArrayList<AnimeCard>(24)
    val seen = HashSet<String>()
    while (titles.size < 24) {
        var added = false
        for (provider in providers) {
            while (provider.hasNext()) {
                val card = provider.next()
                if (seen.add(card.key)) {
                    titles.add(card)
                    added = true
                    break
                }
            }
            if (titles.size == 24) break
        }
        if (!added) break
    }
    return titles
}
