package space.nicart.watchbox.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.nicart.watchbox.R
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import space.nicart.watchbox.ui.components.WbSearchField
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.ui.components.WbEmptyState
import space.nicart.watchbox.ui.components.WbLoading
import space.nicart.watchbox.ui.search.SearchViewModel

/**
 * TV search.
 *
 * Text entry is an ordinary field, opened on demand.
 *
 * A TextField consumes the D-pad while focused - arrow keys become caret movement - so it
 * must not take focus on entry: doing so raises the keyboard, which then swallows every
 * press and leaves nothing else on screen reachable. Up and Back release it explicitly,
 * and submitting hands focus to the results.
 */
@Composable
fun TvSearchScreen(
    viewModel: SearchViewModel,
    onOpenAnime: (AnimeCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tokens = MaterialTheme.wb

    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = TV_CONTENT_START, end = 48.dp, top = 40.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "header") {
            Column {
                Text(
                    text = stringResource(R.string.title_search),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = tokens.colors.textPrimary,
                )
                Spacer(Modifier.height(20.dp))
                WbSearchField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = stringResource(R.string.empty_search_hint),
                    onSubmit = {
                        viewModel.submit()
                        // Released on submit so the results are reachable without having
                        // to escape the field first.
                        focusManager.clearFocus()
                    },
                    modifier = Modifier
                        .focusRequester(fieldFocus)
                        // A TextField consumes the D-pad while focused, so Up and Back
                        // release it and Down moves into the results - otherwise focus
                        // enters the field and cannot leave.
                        .onPreviewKeyEvent { event ->
                            // KeyDown, not KeyUp: a TextField consumes directional keys
                            // on KeyDown, so a handler gated on KeyUp never runs and the
                            // field keeps focus regardless.
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                            when (event.key) {
                                Key.DirectionUp, Key.Back, Key.Escape -> {
                                    focusManager.clearFocus()
                                    event.key != Key.Back
                                }

                                Key.DirectionDown -> {
                                    focusManager.moveFocus(FocusDirection.Down)
                                    true
                                }

                                else -> false
                            }
                        },
                )
            }
        }

        when {
            state.isLoading -> item(key = "loading") {
                Box(modifier = Modifier.fillMaxWidth().height(240.dp)) { WbLoading() }
            }

            state.hasNoSources -> item(key = "no-sources") {
                WbEmptyState(
                    title = stringResource(R.string.empty_no_sources_title),
                    body = stringResource(R.string.empty_no_sources_body),
                )
            }

            state.hasSearched && state.results.isEmpty() -> item(key = "empty") {
                WbEmptyState(title = stringResource(R.string.empty_search_title))
            }

            else -> items(items = state.results, key = { it.sourceId }) { row ->
                TvPortraitRow(
                    title = row.title,
                    items = row.items,
                    onClick = onOpenAnime,
                )
            }
        }
    }
}
