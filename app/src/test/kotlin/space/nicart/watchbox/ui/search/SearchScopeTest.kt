package space.nicart.watchbox.ui.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchScopeTest {

    @Test
    fun `a sole installed source is queried directly`() {
        val state = SearchUiState(
            sources = listOf(SearchSource(id = 42L, name = "Rentaro")),
        )

        assertEquals(42L, state.effectiveSourceId())
    }

    @Test
    fun `multiple sources keep the all-source search when none is selected`() {
        val state = SearchUiState(
            sources = listOf(
                SearchSource(id = 1L, name = "One"),
                SearchSource(id = 2L, name = "Two"),
            ),
        )

        assertNull(state.effectiveSourceId())
    }

    @Test
    fun `an explicit source selection wins`() {
        val state = SearchUiState(
            sources = listOf(
                SearchSource(id = 1L, name = "One"),
                SearchSource(id = 2L, name = "Two"),
            ),
            selectedSourceId = 2L,
        )

        assertEquals(2L, state.effectiveSourceId())
    }
}
