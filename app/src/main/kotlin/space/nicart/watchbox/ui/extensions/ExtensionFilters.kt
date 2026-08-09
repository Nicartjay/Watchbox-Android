package space.nicart.watchbox.ui.extensions

import space.nicart.watchbox.extension.model.Extension

/**
 * The filters applied to the extension list.
 *
 * A single value object rather than separate fields so the "any filter active"
 * check has one definition, and so the filter panel can be reset by replacing the
 * whole thing.
 */
data class ExtensionFilters(
    val query: String = "",
    /** Language codes to keep. Empty means every language. */
    val languages: Set<String> = emptySet(),
    val nsfw: NsfwFilter = NsfwFilter.ALL,
    /** Repository URLs to keep. Empty means every repository. */
    val repoUrls: Set<String> = emptySet(),
) {

    /**
     * Whether anything is narrowing the list.
     *
     * Drives the filter button's highlight, so the user can tell an empty list
     * apart from an over-filtered one.
     */
    val isActive: Boolean
        get() = query.isNotBlank() ||
            languages.isNotEmpty() ||
            nsfw != NsfwFilter.ALL ||
            repoUrls.isNotEmpty()
}

/**
 * How adult extensions are treated.
 *
 * Three states rather than a boolean: hiding and isolating them are both useful,
 * and a two-state toggle cannot express "only 18+".
 *
 * This is independent of the NSFW *setting*, which decides whether such
 * extensions are listed at all. This filter only narrows what is already visible,
 * so it can never reveal something the setting has excluded.
 */
enum class NsfwFilter { ALL, HIDE, ONLY }

/**
 * Applies [filters] to a list of extensions.
 *
 * Written against the [Extension] interface so the installed and available lists
 * share one implementation - two copies would inevitably drift, and a filter that
 * behaves differently in the two sections reads as a bug.
 */
fun <T : Extension> List<T>.applyFilters(filters: ExtensionFilters): List<T> = filter { extension ->
    extension.matches(filters.query) &&
        extension.matchesLanguage(filters.languages) &&
        extension.matchesNsfw(filters.nsfw) &&
        extension.matchesRepo(filters.repoUrls)
}

private fun Extension.matchesLanguage(languages: Set<String>): Boolean =
    languages.isEmpty() || lang.lowercase() in languages

private fun Extension.matchesNsfw(filter: NsfwFilter): Boolean = when (filter) {
    NsfwFilter.ALL -> true
    NsfwFilter.HIDE -> !isNsfw
    NsfwFilter.ONLY -> isNsfw
}

/**
 * Repository filtering only constrains available extensions.
 *
 * An installed extension has no repository of its own - it lives on disk, and may
 * have come from a repo that has since been removed. Excluding installed rows when
 * a repo filter is set would make them vanish for no reason the user can see.
 */
private fun Extension.matchesRepo(repoUrls: Set<String>): Boolean = when {
    repoUrls.isEmpty() -> true
    this is Extension.Available -> repoUrl in repoUrls
    else -> true
}

/**
 * Language codes present in a list, sorted for stable display.
 *
 * Derived from the extensions themselves rather than a fixed list, so the filter
 * only ever offers languages that would actually match something.
 */
fun List<Extension>.availableLanguages(): List<String> =
    mapNotNull { it.lang.lowercase().takeIf { code -> code.isNotBlank() } }
        .distinct()
        .sorted()
