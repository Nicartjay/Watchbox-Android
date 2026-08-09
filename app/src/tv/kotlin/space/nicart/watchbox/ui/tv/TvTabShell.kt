package space.nicart.watchbox.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.media3.common.util.UnstableApi
import space.nicart.watchbox.WatchBoxApplication
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.ui.navigation.AppTab
import space.nicart.watchbox.ui.navigation.WbNavigationRail
import androidx.compose.material3.MaterialTheme

/**
 * The TV tab shell: a left rail beside the focused content.
 *
 * Two behaviours make this workable with only a D-pad, and both are the reason this
 * is a separate shell rather than the phone one with a different nav bar:
 *
 *  - **Left from the content opens the rail.** There is no other way to reach
 *    navigation without a back button press, and pressing Back should leave the app,
 *    not move focus.
 *  - **The rail expands only while it holds focus.** Collapsed it is a 72dp icon
 *    strip so content keeps nearly the full width; focused it widens to show labels.
 *
 * Tab content is kept alive by a `SaveableStateHolder` keyed on tab name, matching
 * the phone shell, so scroll position and focus survive switching tabs.
 */
@UnstableApi
@Composable
fun TvTabShell(
    container: space.nicart.watchbox.AppContainer,
    content: @Composable (AppTab) -> Unit,
) {
    val tokens = MaterialTheme.wb

    var selectedTab by remember { mutableStateOf(AppTab.HOME) }
    var railFocused by remember { mutableStateOf(false) }
    val stateHolder = rememberSaveableStateHolder()

    val railFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.colors.background),
    ) {
        Box(
            modifier = Modifier
                .focusRequester(railFocusRequester)
                .onFocusChanged { railFocused = it.hasFocus }
                // Right out of the rail returns to the content rather than falling
                // through to whatever happens to be laid out next.
                .focusProperties { right = contentFocusRequester }
                .onPreviewKeyEvent { event ->
                    // Selecting a tab moves focus into the content, so the user is
                    // not left with the rail expanded over what they just chose.
                    val isSelect = event.key == Key.DirectionCenter || event.key == Key.Enter
                    if (event.type == KeyEventType.KeyUp && isSelect) {
                        contentFocusRequester.requestFocus()
                        true
                    } else {
                        false
                    }
                },
        ) {
            WbNavigationRail(
                selected = selectedTab,
                onSelect = { selectedTab = it },
                expanded = railFocused,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(contentFocusRequester)
                // focusGroup, NOT focusable: focusable makes this Box itself the
                // focus target, which swallows every D-pad press and leaves the
                // posters unreachable. focusGroup delegates to the children while
                // still letting the rail hand focus back to this subtree.
                .focusGroup()
                .focusProperties { left = railFocusRequester },
        ) {
            stateHolder.SaveableStateProvider(selectedTab.name) {
                content(selectedTab)
            }
        }
    }
}
