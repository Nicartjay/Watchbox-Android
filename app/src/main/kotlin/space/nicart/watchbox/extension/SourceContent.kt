package space.nicart.watchbox.extension

import space.nicart.watchbox.extension.model.Extension
import kotlinx.serialization.Serializable
import androidx.annotation.Keep

/**
 * Arvin's suffix identifies the provider, not the content format. Match the family
 * prefix so installing a new Arvin-* extension needs no app update or ABI change.
 *
 * Check the owning extension too: a factory may give its bundled sources different
 * display names.
 */
fun isShortReelsProvider(sourceName: String, extensionName: String = ""): Boolean =
    sourceName.trim().startsWith("Arvin-", ignoreCase = true) ||
        extensionName.trim().startsWith("Arvin-", ignoreCase = true)

/** Works before installation too, even when the repository omits source metadata. */
val Extension.isShortReels: Boolean
    get() = isShortReelsProvider(name) || when (this) {
        is Extension.Installed -> sources.any { isShortReelsProvider(it.name) }
        is Extension.Available -> sources.any { isShortReelsProvider(it.name) }
        is Extension.Untrusted -> false
    }

/** Which providers belong on a browsing surface. */
@Serializable
@Keep
enum class SourceCollection {
    ALL,
    STANDARD,
    SHORT_REELS,
    ;

    fun includes(isShortReels: Boolean): Boolean = when (this) {
        ALL -> true
        STANDARD -> !isShortReels
        SHORT_REELS -> isShortReels
    }
}

internal data class ContentGroup<T>(val collection: SourceCollection, val items: List<T>)

/** Keep each category contiguous without changing the order within either category. */
internal fun <T> contentGroups(
    items: List<T>,
    scope: SourceCollection,
    isShortReels: (T) -> Boolean,
): List<ContentGroup<T>> = listOf(SourceCollection.STANDARD, SourceCollection.SHORT_REELS)
    .filter { scope == SourceCollection.ALL || scope == it }
    .map { collection -> ContentGroup(collection, items.filter { collection.includes(isShortReels(it)) }) }
    .filter { it.items.isNotEmpty() }

/** Counts stay separate so a shorts update cannot badge the normal Browse section. */
data class ExtensionUpdateCounts(
    val standard: Int = 0,
    val shortReels: Int = 0,
) {
    val total: Int get() = standard + shortReels

    fun forCollection(collection: SourceCollection): Int = when (collection) {
        SourceCollection.ALL -> total
        SourceCollection.STANDARD -> standard
        SourceCollection.SHORT_REELS -> shortReels
    }
}

fun List<Extension.Installed>.extensionUpdateCounts(): ExtensionUpdateCounts = ExtensionUpdateCounts(
    standard = count { it.hasUpdate && !it.isShortReels },
    shortReels = count { it.hasUpdate && it.isShortReels },
)
