package space.nicart.watchbox.ui.tv

import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.ui.browse.BrowseMode
import space.nicart.watchbox.ui.browse.BrowseUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvBrowseInteractionTest {
    @Test
    fun `typing a complete title does not submit or touch the listing`() {
        val submissions = mutableListOf<String>()
        val listing = BrowseUiState(query = "Previous", mode = BrowseMode.SEARCH)
        val input = TvBrowseSearchInput(listing.query) { submissions += it }
        val title = "New title"

        title.indices.forEach { input.edit(title.take(it + 1)) }

        assertEquals(title, input.query)
        assertEquals("Previous", listing.query)
        assertTrue(submissions.isEmpty())
    }

    @Test
    fun `Enter or Done submits only the final draft`() {
        val submissions = mutableListOf<String>()
        val input = TvBrowseSearchInput("") { submissions += it }
        input.edit("N")
        input.edit("New")
        input.edit("New title")

        input.submit()

        assertEquals(listOf("New title"), submissions)
    }

    @Test
    fun `clearing waits for an explicit submission too`() {
        val submissions = mutableListOf<String>()
        val input = TvBrowseSearchInput("Previous") { submissions += it }
        input.edit("")
        assertTrue(submissions.isEmpty())

        input.submit()

        assertEquals(listOf(""), submissions)
    }

    @Test
    fun `a prefilled field does not automatically submit when created`() {
        val submissions = mutableListOf<String>()
        val input = TvBrowseSearchInput("Existing title") { submissions += it }
        assertEquals("Existing title", input.query)
        assertTrue(submissions.isEmpty())
    }

    @Test
    fun `an edited draft is retained while the existing listing appends`() {
        val submissions = mutableListOf<String>()
        val input = TvBrowseSearchInput("Existing title") { submissions += it }
        input.edit("Next title")

        val appending = populated().copy(query = "Existing title", isAppending = true)
        assertEquals("Existing title", appending.query)
        assertEquals("Next title", input.query)
        assertTrue(submissions.isEmpty())
    }

    @Test
    fun `header loading and empty-state rows cannot start pagination`() {
        assertFalse(BrowseUiState(isLoading = true).shouldAppendTvBrowse(1, 2))
        assertFalse(BrowseUiState().shouldAppendTvBrowse(1, 2))
        assertFalse(populated().shouldAppendTvBrowse(0, 0))
    }

    @Test
    fun `pagination waits for a ready non-exhausted listing`() {
        val state = populated()
        assertFalse(state.copy(isLoading = true).shouldAppendTvBrowse(30, 31))
        assertFalse(state.copy(hasMore = false).shouldAppendTvBrowse(30, 31))
        assertTrue(state.shouldAppendTvBrowse(30, 31))
    }

    @Test
    fun `an in-flight or duplicate-only page does not re-arm pagination`() {
        val state = populated()
        val initialTrigger = state.shouldAppendTvBrowse(30, 31)
        val appending = state.copy(isAppending = true)
        val duplicatePage = state.copy(page = state.page + 1)

        assertEquals(initialTrigger, appending.shouldAppendTvBrowse(30, 31))
        assertEquals(initialTrigger, duplicatePage.shouldAppendTvBrowse(30, 31))
    }

    @Test
    fun `pagination starts near the end rather than at the search header`() {
        val state = populated()
        assertFalse(state.shouldAppendTvBrowse(lastVisibleIndex = 2, totalItems = 31))
        assertTrue(state.shouldAppendTvBrowse(lastVisibleIndex = 19, totalItems = 31))
    }

    private fun populated() = BrowseUiState(
        items = listOf(AnimeCard(sourceId = 1L, url = "/result", title = "Result", posterUrl = null)),
    )
}
