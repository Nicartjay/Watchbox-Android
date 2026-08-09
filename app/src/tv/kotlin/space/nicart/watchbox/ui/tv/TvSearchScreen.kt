package space.nicart.watchbox.ui.tv

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.ui.components.WbEmptyState
import space.nicart.watchbox.ui.components.WbLoading
import space.nicart.watchbox.ui.search.SearchViewModel

/**
 * TV search.
 *
 * Text entry goes through the system voice recogniser rather than an on-screen field.
 * Typing with a D-pad means moving a cursor around a virtual keyboard one letter at a
 * time, and every TV interface offers voice for exactly that reason. The recogniser is
 * launched as an intent, so it degrades gracefully: a device without it returns no
 * result and the previous query stands.
 */
@Composable
fun TvSearchScreen(
    viewModel: SearchViewModel,
    onOpenAnime: (AnimeCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tokens = MaterialTheme.wb

    val voiceSearch = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult

        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return@rememberLauncherForActivityResult

        viewModel.onQueryChange(spoken)
        viewModel.submit()
    }

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
                TvSearchTrigger(
                    query = state.query,
                    placeholder = stringResource(R.string.tv_search_hint),
                    onClick = {
                        voiceSearch.launch(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                )
                                putExtra(
                                    RecognizerIntent.EXTRA_PROMPT,
                                    // Shown by the recogniser's own UI.
                                    "Say a title",
                                )
                            },
                        )
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
                TvPosterRow(
                    title = row.title,
                    items = row.items,
                    onClick = onOpenAnime,
                )
            }
        }
    }
}
