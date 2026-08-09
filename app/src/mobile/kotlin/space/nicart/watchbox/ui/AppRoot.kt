package space.nicart.watchbox.ui

import androidx.compose.runtime.Composable
import androidx.media3.common.util.UnstableApi
import space.nicart.watchbox.AppContainer

/**
 * Phone and tablet entry point.
 *
 * One of two flavor-specific definitions. Selecting the UI here rather than branching
 * at runtime means each APK contains only its own screens: the phone build has no TV
 * code, and vice versa.
 */
@UnstableApi
@Composable
fun AppRoot(container: AppContainer) {
    WatchBoxApp(container = container)
}
