package space.nicart.watchbox.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable
import space.nicart.watchbox.R

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
    )

    /** Full-screen player. */
    @Serializable
    data class Player(
        val sourceId: Long,
        val animeUrl: String,
        val episodeUrl: String,
        val resumeMs: Long = 0L,
    )

    /** Popular/latest grid for one source. */
    @Serializable
    data class SourceBrowse(val sourceId: Long, val sourceName: String)

    /** Extension repository manager. */
    @Serializable
    data object Extensions

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
 * The five bottom-nav destinations, in order.
 *
 * Browse sits between Search and Library because it is where sources and
 * extensions are reached, which is the most-used screen before a library exists.
 */
enum class AppTab(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    HOME(R.string.nav_home, Icons.Filled.Home),
    SEARCH(R.string.nav_search, Icons.Rounded.Search),
    BROWSE(R.string.nav_browse, Icons.Rounded.Explore),
    LIBRARY(R.string.nav_library, Icons.Rounded.VideoLibrary),
    SETTINGS(R.string.nav_settings, Icons.Outlined.Person),
}
