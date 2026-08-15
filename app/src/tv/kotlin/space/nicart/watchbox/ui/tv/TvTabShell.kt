package space.nicart.watchbox.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
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
    /** Receives the current tab and a way to switch it. */
    content: @Composable (AppTab, (AppTab) -> Unit) -> Unit,
) {
    val tokens = MaterialTheme.wb

    // Owned by the shell: the rail's source applies to both the home feed and search,
    // so neither screen can be the one that holds it.
    val sourceViewModel: TvSourceViewModel = viewModel(
        key = "tv-source",
        factory = TvSourceViewModel.factory(container.extensionManager, container.store),
    )
    val sources by sourceViewModel.sources.collectAsStateWithLifecycle()
    val selectedSource by sourceViewModel.selected.collectAsStateWithLifecycle()
    // Mirrored onto the view model, not kept purely local: the home screen has to know the
    // drawer is covering it so the hero carousel can hold still, and it is a sibling that
    // cannot see this state.
    val pickerOpen by sourceViewModel.pickerOpen.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableStateOf(AppTab.HOME) }
    var railFocused by remember { mutableStateOf(false) }
    val stateHolder = rememberSaveableStateHolder()

    val railFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }

    /**
     * Re-homes focus after a tab switch.
     *
     * Selecting a tab replaces the content subtree, which disposes whatever held
     * focus. Compose does not re-home it, so focus is simply lost and the remote goes
     * dead until a direction press happens to land somewhere.
     *
     * Keyed on the tab alone. An earlier version also keyed on a pending flag and
     * cleared it before requesting, which re-ran the effect with the flag already
     * false - so the request never happened.
     *
     * The frame wait matters: the requester has no node attached until the new tab has
     * composed, and requesting before then throws.
     */
    var shellHasFocus by remember { mutableStateOf(false) }

    // Retried until it succeeds. A single attempt keyed on the tab is not enough: the
    // requester is detached while the content subtree is being replaced, so the
    // request fails silently and focus stays lost - which leaves the remote dead.
    LaunchedEffect(selectedTab, shellHasFocus) {
        if (shellHasFocus) return@LaunchedEffect

        repeat(REFOCUS_ATTEMPTS) {
            withFrameNanos { }
            runCatching { railFocusRequester.requestFocus() }

            // Checked against observed focus, not the call's return value:
            // requestFocus reports success even when the target has no node yet, so
            // trusting it stops the retry loop while focus is still nowhere.
            if (shellHasFocus) return@LaunchedEffect
            delay(REFOCUS_RETRY_MS)
        }
    }

    /**
     * Back moves focus to the navigation rail before it will leave the app.
     *
     * On a television Back is the only way out of a section, and exiting the app from
     * inside a content row is almost never what was meant: the remote has no other
     * "go up a level", so Back is what a viewer reaches for to get back to the menu.
     *
     * Disabled once the rail already has focus, so a second press falls through to the
     * system and closes the app as normal - otherwise there would be no way out at all.
     *
     * Also disabled while the source picker is open, which owns Back for itself.
     */
    BackHandler(enabled = !railFocused && !pickerOpen) {
        // A single request, not a retry loop: the rail is already composed here, unlike
        // the tab-switch case above, so its requester has a node. A loop would also spin
        // without yielding, so `railFocused` could not update between attempts and every
        // iteration would see the stale value.
        runCatching { railFocusRequester.requestFocus() }
    }

    // The rail overlays the content rather than sitting beside it in a Row. A
    // transparent rail that still consumed layout width would leave a blank strip the
    // artwork could not reach, which defeats the point of it being transparent.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { shellHasFocus = it.hasFocus }
            .background(tokens.colors.background),
    ) {
        // Slid aside while the rail is expanded rather than letting the rail cover
        // it. Padding would re-layout the whole screen - reflowing every row and
        // re-measuring images - on each focus change; a translation moves the same
        // pixels and animates cheaply.
        val contentShift by animateDpAsState(
            targetValue = if (railFocused) RAIL_EXPANDED_SHIFT else 0.dp,
            animationSpec = tween(180),
            label = "contentShift",
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(x = contentShift)
                .focusRequester(contentFocusRequester)
                .focusGroup()
                // Declared so leaving the content finds the rail regardless of what
                // the content's leftmost item happens to be.
                .focusProperties { left = railFocusRequester },
        ) {
            stateHolder.SaveableStateProvider(selectedTab.name) {
                content(selectedTab) { selectedTab = it }
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
                footer = { expanded ->
                    TvRailSourceButton(
                        source = selectedSource,
                        expanded = expanded,
                        onClick = { sourceViewModel.setPickerOpen(true) },
                    )
                },
            )
        }

        // Above the rail, so the drawer covers it rather than sliding under.
        TvSourcePickerPanel(
            sources = sources,
            selected = selectedSource,
            visible = pickerOpen,
            onSelect = { source ->
                sourceViewModel.select(source)
                sourceViewModel.setPickerOpen(false)
            },
            onDismiss = { sourceViewModel.setPickerOpen(false) },
        )
    }
}

/**
 * Left padding for TV content.
 *
 * The collapsed rail is 72dp and overlays the content, so this is the rail plus a
 * 16dp gutter. No overscan padding is added on this edge: the rail already occupies
 * it, and the rail's own 8dp inset keeps its icons clear of the cropped region.
 *
 * Deliberately not widened to the rail's *expanded* width. The rail only expands while
 * focused, and reserving that space permanently would leave a gap that is empty
 * whenever the content has focus - which is most of the time.
 */
val TV_CONTENT_START = 88.dp

/** Frames to keep retrying the focus claim after a tab switch. */
private const val REFOCUS_ATTEMPTS = 10
private const val REFOCUS_RETRY_MS = 50L

/**
 * How far content slides right while the rail is expanded.
 *
 * Sized so the shifted content sits the same 16dp clear of the expanded rail as it
 * does of the collapsed one, keeping the gutter visually constant as the rail opens
 * and closes.
 */
private val RAIL_EXPANDED_SHIFT = 148.dp
