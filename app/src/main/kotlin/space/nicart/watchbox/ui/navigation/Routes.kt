package space.nicart.watchbox.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable
import space.nicart.watchbox.R

/**
 * Navigation graph.
 *
 * Nuvio uses Navigation 3 with `NavKey` routes and a single flat back stack; the
 * closest stable Android-only equivalent is Navigation-Compose with typed
 * `@Serializable` routes, which is what this uses. Tab switching is state inside
 * [Routes.Tabs] rather than separate back stacks, matching Nuvio's `AppTabHost`.
 */
object Routes {

    /** The tab shell. Holds Home / Search / Library / Settings. */
    @Serializable
    data object Tabs

    /** Title detail page. */
    @Serializable
    data class Detail(val detailPath: String, val title: String = "")

    /** Full-screen player. */
    @Serializable
    data class Player(
        val detailPath: String,
        val season: Int = 1,
        val episode: Int = 1,
        val resumeMs: Long = 0L,
    )

    /** A "view all" grid for one home row. */
    @Serializable
    data class Browse(val title: String, val rowId: String)
}

/** The four bottom-nav destinations, in order (`App.kt:368-373`). */
enum class AppTab(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    HOME(R.string.nav_home, Icons.Filled.Home),
    SEARCH(R.string.nav_search, Icons.Rounded.Search),
    LIBRARY(R.string.nav_library, Icons.Rounded.VideoLibrary),
    SETTINGS(R.string.nav_settings, Icons.Outlined.Person),
}
