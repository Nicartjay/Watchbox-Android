package space.nicart.watchbox.ui

import androidx.compose.runtime.Composable
import androidx.media3.common.util.UnstableApi
import space.nicart.watchbox.AppContainer
import space.nicart.watchbox.ui.tv.TvApp

/** Android TV entry point. See the mobile definition for why this is per-flavor. */
@UnstableApi
@Composable
fun AppRoot(container: AppContainer) {
    TvApp(container = container)
}
