package space.nicart.watchbox

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch
import space.nicart.watchbox.core.ui.WatchBoxTheme
import space.nicart.watchbox.data.local.AppSettings
import space.nicart.watchbox.data.local.ExtensionRepo
import space.nicart.watchbox.extension.RepoDeepLink
import space.nicart.watchbox.ui.AppRoot

@UnstableApi
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as WatchBoxApplication).container

        // A link that launched the app cold.
        handleRepoLink(intent)

        setContent {
            // Theme choice lives in DataStore, so the whole tree recomposes when
            // the user picks a different accent or toggles AMOLED.
            val settings by container.store.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())

            WatchBoxTheme(
                appTheme = settings.theme,
                amoled = settings.amoled,
                uiScale = settings.uiScale,
                posterScale = settings.posterScale,
            ) {
                // Resolved per flavor: WatchBoxApp on phones, TvApp on television.
                AppRoot(container = container)
            }
        }
    }

    /**
     * The activity is `singleTask`, so a link arriving while the app is already
     * running is delivered here instead of to [onCreate].
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Kept as the activity's current intent, or a later getIntent() would
        // return the one that originally launched the app.
        setIntent(intent)
        handleRepoLink(intent)
    }

    /**
     * Adds a repository named by an `aniyomi://add-repo?url=...` link.
     *
     * Feedback is a toast rather than in-app UI because the link can arrive while
     * any screen is showing - including none, on a cold start - and silently adding
     * a repository would leave the user unsure whether the link did anything.
     */
    private fun handleRepoLink(intent: Intent?) {
        val url = RepoDeepLink.parse(intent?.dataString) ?: return
        val store = (application as WatchBoxApplication).container.store

        lifecycleScope.launch {
            val added = store.addRepo(url)
            val name = ExtensionRepo(ExtensionRepo.normaliseUrl(url)).displayName

            Toast.makeText(
                this@MainActivity,
                if (added) {
                    getString(R.string.repo_link_added, name)
                } else {
                    // Distinguished from success: re-opening a link is common, and
                    // "added" would be a lie the second time.
                    getString(R.string.repo_link_duplicate, name)
                },
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
