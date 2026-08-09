package space.nicart.watchbox.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import space.nicart.watchbox.AppContainer
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.ui.navigation.AppTab
import space.nicart.watchbox.ui.navigation.WbNavigationRail

/**
 * The TV tab shell: a left rail beside the focused content.
 *
 * ## Initial focus
 *
 * Something must hold focus before the first key press, or that press is spent
 * establishing focus and the remote appears dead. Compose does not focus anything by
 * default, so the rail claims it on first composition. This is the single most
 * important detail in a leanback UI and the easiest to miss, because it only shows up
 * when testing with an actual D-pad rather than a mouse.
 *
 * ## Why focus is not redirected between the two panes
 *
 * An earlier version pointed the rail's `right` at the content group and called
 * `requestFocus()` on it when a tab was selected. Both are unsafe: requesting focus
 * on a [focusGroup] that has no focusable child yet - which happens on every tab
 * switch, before the new screen's items compose - silently drops focus to nothing,
 * leaving the D-pad dead with no way to recover.
 *
 * Compose's own two-dimensional search handles this correctly once both panes simply
 * contain focusable children, so the explicit wiring is gone. Only `left` is
 * declared, so leaving the content always finds the rail rather than depending on
 * geometry.
 */
@UnstableApi
@Composable
fun TvTabShell(
    container: AppContainer,
    content: @Composable (AppTab) -> Unit,
) {
    val tokens = MaterialTheme.wb

    var selectedTab by remember { mutableStateOf(AppTab.HOME) }
    var railFocused by remember { mutableStateOf(false) }
    val stateHolder = rememberSaveableStateHolder()

    val railFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }

    // Claimed once, on the first composition only. Re-requesting on every tab change
    // would yank focus back to the rail after each selection.
    LaunchedEffect(Unit) {
        runCatching { railFocusRequester.requestFocus() }
    }

    // The rail overlays the content rather than sitting beside it in a Row. A
    // transparent rail that still consumed layout width would leave a blank strip the
    // artwork could not reach, which defeats the point of it being transparent.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.colors.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(contentFocusRequester)
                .focusGroup()
                // Declared so leaving the content finds the rail regardless of what
                // the content's leftmost item happens to be.
                .focusProperties { left = railFocusRequester },
        ) {
            stateHolder.SaveableStateProvider(selectedTab.name) {
                content(selectedTab)
            }
        }

        // Drawn last so it sits above the content it overlays.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .focusRequester(railFocusRequester)
                .onFocusChanged { railFocused = it.hasFocus }
                .focusGroup()
                // Explicit, because the rail overlays the content rather than sitting
                // beside it: there is nothing to the rail's right geometrically, so
                // Compose's 2D search finds no candidate and focus simply stays put.
                .focusProperties { right = contentFocusRequester },
        ) {
            WbNavigationRail(
                selected = selectedTab,
                onSelect = { selectedTab = it },
                expanded = railFocused,
            )
        }
    }
}

/**
 * Left padding for TV content.
 *
 * The collapsed rail is 72dp and overlays the content, so screens start beyond it.
 * The remainder is overscan clearance - televisions can crop several percent of each
 * edge, so content flush to the screen edge risks being physically cut off.
 *
 * Deliberately not widened to the rail's *expanded* width: the rail only expands while
 * focused, and reserving space for that would leave a permanent gap.
 */
val TV_CONTENT_START = 120.dp
