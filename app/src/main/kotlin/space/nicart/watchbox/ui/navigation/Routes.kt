package space.nicart.watchbox.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable
import space.nicart.watchbox.R
import space.nicart.watchbox.extension.SourceCollection

/**
 * Navigation graph.
 *
 * A single flat back stack, matching Nuvio's model: the tab shell is one
 * destination and detail/player push on top of it, so full-screen routes cover
 * the nav pill naturally.
 *
 * Routes identify content by `sourceId` + source-relative `url`, because that is
 * the only stable pair an extension guarantees. There is no global id in this
 * ecosystem.
 */
object Routes {

    @Serializable
    data object Tabs

    /** Title detail page. */
    @Serializable
    data class Detail(
        val sourceId: Long,
        val animeUrl: String,
        val title: String = "",
        /**
         * Start playing as soon as the episode list resolves.
         *
         * The hero's "Watch Now" cannot open the player directly: choosing an episode
         * needs the episode list, which only loading the title provides. So it routes
         * here and hands off, rather than blocking the tap on a network round-trip.
         */
        val autoPlay: Boolean = false,
    )

    /** Full-screen player. */
    @Serializable
    data class Player(
        val sourceId: Long,
        val animeUrl: String,
        val episodeUrl: String,
        val resumeMs: Long = 0L,
        /** Also identifies offline Arvin videos before their saved detail has loaded. */
        val sourceName: String = "",
    )

    /** Popular/latest grid for one source. */
    @Serializable
    data class SourceBrowse(val sourceId: Long, val sourceName: String)

    /** Extension manager, restricted to the collection that opened it. */
    @Serializable
    data class Extensions(val collection: SourceCollection = SourceCollection.ALL)

    /**
     * Settings for one extension's sources.
     *
     * Keyed by package name rather than source id: an extension may bundle several
     * sources, and they are configured together on one screen.
     */
    @Serializable
    data class SourceSettings(val pkgName: String, val extensionName: String)
}

/**
 * Main destinations, in order. Short/Reels is shown on tablets and TV only.
 *
 * Browse sits between Search and Library because it is where sources and
 * extensions are reached, which is the most-used screen before a library exists.
 */
enum class AppTab(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    HOME(R.string.nav_home, Icons.Filled.Home),
    SHORT_REELS(R.string.nav_short_reels, Icons.Rounded.SmartDisplay),
    SEARCH(R.string.nav_search, Icons.Rounded.Search),
    BROWSE(R.string.nav_browse, Icons.Rounded.Explore),
    LIBRARY(R.string.nav_library, Icons.Rounded.VideoLibrary),

    // A gear, not a person: this tab is labelled Settings and opens settings. The person
    // icon promised an account or profile, which the app has no concept of - there is no
    // sign-in, so nothing behind it could ever match what it implied.
    SETTINGS(R.string.nav_settings, Icons.Rounded.Settings),
}

fun appTabs(showShortReels: Boolean): List<AppTab> =
    AppTab.entries.filter { showShortReels || it != AppTab.SHORT_REELS }

/** Null delegates Back on Home to Android so the activity can close normally. */
fun AppTab.backDestination(): AppTab? = if (this == AppTab.HOME) null else AppTab.HOME

/** Phones browse both content types; tablet/TV sections keep their separate collections. */
fun AppTab.extensionCollection(separateShortReels: Boolean = true): SourceCollection = when (this) {
    AppTab.SHORT_REELS -> SourceCollection.SHORT_REELS
    AppTab.BROWSE -> if (separateShortReels) SourceCollection.STANDARD else SourceCollection.ALL
    else -> SourceCollection.ALL
}
