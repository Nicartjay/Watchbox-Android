package space.nicart.watchbox.ui.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.domain.AnimeRepository

/**
 * Supplies TMDB artwork for the TV home.
 *
 * Two needs, both absent from the phone feed - which enriches only the first few hero
 * cards, because a wide backdrop and a title logo are the only things it shows:
 *
 *  - **Landscape cards need a backdrop each.** A portrait poster cannot fill a 16:9
 *    card without cropping away most of the frame, so every visible card needs its own
 *    backdrop.
 *  - **The full-bleed hero follows focus,** so any card can become the hero.
 *
 * Enrichment is deliberately not done inside `homeFeed`. Fetching artwork for every
 * card before the screen renders would turn a fast home load into dozens of serial
 * TMDB round-trips; instead rows fill in progressively, and cards show their source's
 * own poster until their backdrop arrives.
 */
class TvArtworkViewModel(
    private val repository: AnimeRepository,
) : ViewModel() {

    private val _focused = MutableStateFlow<AnimeCard?>(null)

    /** The focused card, with artwork attached once it resolves. */
    val focused: StateFlow<AnimeCard?> = _focused.asStateFlow()

    private val _artwork = MutableStateFlow<Map<String, AnimeCard>>(emptyMap())

    /**
     * Resolved artwork by card key.
     *
     * A map rather than rewritten rows: the feed belongs to [HomeViewModel], and
     * replacing its rows from here would mean two sources of truth for the same list.
     */
    val artwork: StateFlow<Map<String, AnimeCard>> = _artwork.asStateFlow()

    /** Rows already requested, so scrolling back does not refetch. */
    private val requestedRows = mutableSetOf<String>()

    /**
     * Caps concurrent TMDB calls.
     *
     * Unbounded fan-out across several rows would open dozens of sockets at once and
     * invite rate limiting; four keeps a row filling in visibly fast without that.
     */
    private val permits = Semaphore(MAX_CONCURRENT_LOOKUPS)

    private var focusJob: Job? = null

    /**
     * Enriches the cards of one row.
     *
     * Capped at [MAX_CARDS_PER_ROW] because a row is scrolled, not read whole: cards
     * beyond the first screenful are usually never seen, and fetching them costs
     * requests that delay the ones that are.
     */
    fun onRowVisible(rowKey: String, cards: List<AnimeCard>) {
        if (!requestedRows.add(rowKey)) return

        viewModelScope.launch {
            cards.take(MAX_CARDS_PER_ROW).forEach { card ->
                // Already resolved, or arrived enriched from the hero.
                if (card.cardBackdropUrl != null || _artwork.value.containsKey(card.key)) {
                    return@forEach
                }

                launch {
                    permits.withPermit {
                        val enriched = repository.artworkFor(card)
                        // Only published when something was actually found, so a
                        // miss leaves the card on its original poster rather than
                        // replacing it with an identical copy.
                        if (enriched.hasArtwork()) {
                            _artwork.value = _artwork.value + (card.key to enriched)
                        }
                    }
                }
            }
        }
    }

    /**
     * Reports the focused card, which drives the hero.
     *
     * Debounced: holding a direction on a remote fires focus events rapidly, and
     * without a delay sweeping a row would issue a lookup per card passed over.
     */
    fun onFocus(card: AnimeCard) {
        focusJob?.cancel()

        // Resolved artwork first, so the hero does not briefly regress to the plain
        // card after having already shown the backdrop.
        _focused.value = _artwork.value[card.key] ?: card

        if (card.backdropUrl != null || _artwork.value.containsKey(card.key)) return

        focusJob = viewModelScope.launch {
            delay(FOCUS_DEBOUNCE_MS)

            val enriched = repository.artworkFor(card)
            if (enriched.hasArtwork()) {
                _artwork.value = _artwork.value + (card.key to enriched)
            }

            // Discarded if focus has moved on: a late response must not overwrite the
            // card the user is now looking at.
            if (_focused.value?.key == card.key) {
                _focused.value = enriched
            }
        }
    }

    /**
     * Forgets everything belonging to the previous source.
     *
     * Three pieces of state outlive a source switch, and each caused a visible fault:
     *
     *  - [_focused] is what the hero backdrop prefers over the spotlight card, so the old
     *    source's artwork stayed on screen beneath the new source's logo and title. That is
     *    the mismatch: the backdrop and the text were reading different cards.
     *  - [requestedRows] is a de-duplication set keyed by row name, and the hero's name is a
     *    constant. Once "tv-hero" had been requested for one source, every later source was
     *    skipped, so the new spotlight never got a backdrop or a logo at all.
     *  - [_artwork] is keyed by card, which is unique per source, so it is correct to keep -
     *    but it is dropped anyway. It is a cache of a screen that no longer exists, and
     *    holding five sources' artwork to save refetching one is the wrong trade.
     *
     * A pending focus lookup is cancelled too: its response is for a card the user can no
     * longer see, and publishing it would put the old source back on the hero.
     */
    fun onSourceChanged(sourceId: Long?) {
        // Only on a real change. This is driven from the home screen's composition, which is
        // disposed and rebuilt every time Detail is pushed and popped - so an unguarded call
        // fired on the way back from a detail page too, clearing the pending focus restore and
        // stranding focus at the top of the feed instead of on the card the user had opened.
        if (sourceId == lastSourceId) return

        val isFirstFeed = lastSourceId == null
        lastSourceId = sourceId

        // Nothing to discard before the first feed has loaded, and clearing here would throw
        // away artwork already requested for it.
        if (isFirstFeed) return

        focusJob?.cancel()
        _focused.value = null
        _artwork.value = emptyMap()
        requestedRows.clear()
        _lastOpened.value = null
    }

    /**
     * Drops every resolved image so the next request re-picks it.
     *
     * Called when the artwork language changes. The client's own cache is cleared at the same
     * time, but that alone is not enough: the entries here hold the poster and logo URLs
     * chosen under the previous language, and nothing re-resolves them - so the setting
     * appeared to do nothing until a source switch happened to discard them anyway.
     *
     * Deliberately does not touch [lastSourceId]: the source has not changed, and resetting
     * it would make the next recomposition look like a switch and strand focus.
     */
    fun onArtworkLanguageChanged() {
        focusJob?.cancel()
        _artwork.value = emptyMap()
        requestedRows.clear()

        // Re-resolved rather than blanked, so the hero does not lose its backdrop while the
        // new artwork is fetched. The card itself is unchanged; only its URLs are stale.
        //
        // Refetched directly rather than through onFocus, which returns early for a card that
        // already carries a backdrop - true of every card that has been enriched once, so
        // routing through it here would silently do nothing.
        val showing = _focused.value ?: return
        focusJob = viewModelScope.launch {
            val enriched = repository.artworkFor(showing)
            if (!enriched.hasArtwork()) return@launch
            _artwork.value = _artwork.value + (showing.key to enriched)
            // Guarded, since focus may have moved while this was in flight.
            if (_focused.value?.key == showing.key) _focused.value = enriched
        }
    }

    /** The source the retained state belongs to, so a switch can be told from a recomposition. */
    private var lastSourceId: Long? = null

    /**
     * Which card's detail page was opened last, as "<row>::<card key>", or null.
     *
     * Held here because this survives navigation while the home screen's composition does
     * not: pushing Detail disposes the whole subtree, so anything the cards remember
     * themselves is gone by the time the user comes back. The card that was opened is the
     * one focus should return to, which is not the same as the focused card - focus can
     * move after opening, and does when the shell re-homes it to the rail.
     *
     * Qualified by row because a card key is "<source>::<url>", which is the same title in
     * Popular and in Latest. Keyed on the card alone, both rows matched and focus landed on
     * whichever composed last rather than the row the user actually opened from.
     */
    private val _lastOpened = MutableStateFlow<String?>(null)
    val lastOpened: StateFlow<String?> = _lastOpened.asStateFlow()

    /** Records the card being opened, so focus can return to it. */
    fun onOpen(rowKey: String, card: AnimeCard) {
        _lastOpened.value = openedKey(rowKey, card)
    }

    /**
     * Clears the pending restore once a card has claimed focus.
     *
     * Without this the row would pull focus back to that card every time it recomposed -
     * including while the user was moving away from it.
     */
    fun onFocusRestored() {
        _lastOpened.value = null
    }

    /** The [lastOpened] value identifying [card] within [rowKey]. */
    fun openedKey(rowKey: String, card: AnimeCard): String = "$rowKey::${card.key}"

    companion object {
        /**
         * Long enough to skip cards passed over while holding a direction, short
         * enough that pausing on one feels immediate.
         */
        private const val FOCUS_DEBOUNCE_MS = 350L
        private const val MAX_CONCURRENT_LOOKUPS = 4
        private const val MAX_CARDS_PER_ROW = 12

        fun factory(repository: AnimeRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TvArtworkViewModel(repository) as T
            }
    }
}

/**
 * Whether a lookup produced anything worth publishing.
 *
 * Includes the portrait poster: the TV cards prefer it over the source's own artwork, so
 * a title that matched on poster alone still has something to show. Checking only the
 * backdrop and logo would silently drop those.
 */
private fun AnimeCard.hasArtwork(): Boolean =
    cardBackdropUrl != null || logoUrl != null || tmdbPosterUrl != null
