package space.nicart.watchbox.data.remote

/**
 * A trailer or teaser, always a YouTube link in practice.
 *
 * Every video TMDB returns across the titles surveyed is `site: "YouTube"` - there is no
 * direct stream URL anywhere in the payload. YouTube's own stream URLs are signed and
 * obfuscated, so Media3 cannot play one, and the app carries no WebView. Playback therefore
 * hands off to whatever handles YouTube on the device.
 */
data class TmdbVideo(
    val key: String,
    val name: String,
    val type: String,
    /** True for a studio upload rather than a fan re-post. */
    val official: Boolean,
    /** ISO date, for ordering. Blank when TMDB has none. */
    val publishedAt: String,
) {
    /** The watch page, which is what an intent can open. */
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$key"

    /** Thumbnail, straight from YouTube's own image host - no API key needed. */
    val thumbnailUrl: String get() = "https://img.youtube.com/vi/$key/hqdefault.jpg"
}

/**
 * One streaming service carrying a title, in one country.
 *
 * [kind] separates the ways it is offered, because they are not interchangeable: a title on
 * a subscription service is watchable now, one listed under `buy` is not.
 */
data class TmdbProvider(
    val name: String,
    val logoUrl: String?,
    val kind: ProviderKind,
)

enum class ProviderKind {
    /** Included with a subscription. */
    STREAM,

    /** Free, advertising-supported. */
    FREE,

    /** Rental. */
    RENT,

    /** Purchase. */
    BUY,
}

/** A viewer review, for the detail page. */
data class TmdbReview(
    val author: String,
    val content: String,
    /** Out of 10, null when the reviewer left no score. */
    val rating: Int?,
    val avatarUrl: String?,
    /** ISO date. Blank when absent. */
    val createdAt: String,
)

/**
 * The extras that are not artwork: videos, availability, reviews, ratings and ids.
 *
 * Fetched separately from [TmdbArtwork] and only for the detail page. Artwork is needed for
 * every card in every rail, while none of this is, so folding them together would mean
 * requesting reviews for a hundred posters nobody has opened.
 */
data class TmdbExtras(
    val videos: List<TmdbVideo> = emptyList(),
    val providers: List<TmdbProvider> = emptyList(),
    /** Where the provider list came from, for the "as shown in X" label. */
    val providerCountry: String = "",
    /** TMDB's own page for the region's providers, worth linking rather than reproducing. */
    val providerLink: String? = null,
    val reviews: List<TmdbReview> = emptyList(),
    /** Age rating for the user's country, e.g. `TV-MA`. Blank when none is published. */
    val certification: String = "",
    /** Descriptive tags, far more specific than genres. */
    val keywords: List<String> = emptyList(),
    /**
     * Alternative titles, for matching a source's naming against TMDB's.
     *
     * Sources frequently use a romaji or regional name where TMDB uses the English one.
     */
    val alternativeTitles: List<String> = emptyList(),
    /** TheTVDB id, a second mapping route where the primary one has no match. */
    val tvdbId: Int? = null,
    /** Air date of the next episode, ISO. Blank when nothing is scheduled. */
    val nextEpisodeAirDate: String = "",
    /** Episode number of the next airing, 0 when unknown. */
    val nextEpisodeNumber: Int = 0,
) {
    val hasAnything: Boolean
        get() = videos.isNotEmpty() ||
            providers.isNotEmpty() ||
            reviews.isNotEmpty() ||
            certification.isNotBlank() ||
            keywords.isNotEmpty()
}
