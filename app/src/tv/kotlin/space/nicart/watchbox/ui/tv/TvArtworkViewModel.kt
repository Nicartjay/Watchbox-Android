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
