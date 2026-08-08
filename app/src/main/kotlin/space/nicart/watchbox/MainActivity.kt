package space.nicart.watchbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.nicart.watchbox.core.ui.WatchBoxTheme
import space.nicart.watchbox.data.local.AppSettings
import space.nicart.watchbox.ui.WatchBoxApp

@UnstableApi
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as WatchBoxApplication).container

        setContent {
            // Theme choice lives in DataStore, so the whole tree recomposes when
            // the user picks a different accent or toggles AMOLED.
            val settings by container.store.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())

            WatchBoxTheme(
                appTheme = settings.theme,
                amoled = settings.amoled,
            ) {
                WatchBoxApp(container = container)
            }
        }
    }
}
