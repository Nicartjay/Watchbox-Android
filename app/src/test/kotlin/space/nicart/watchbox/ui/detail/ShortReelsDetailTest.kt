package space.nicart.watchbox.ui.detail

import space.nicart.watchbox.domain.AnimeDetail
import space.nicart.watchbox.domain.AnimeStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShortReelsDetailTest {
    @Test
    fun `extension classification selects number-only episodes before detail arrives`() {
        assertTrue(DetailUiState(sourceIsShortReels = true).isShortReels)
    }

    @Test
    fun `an Arvin factory's source keeps number-only episodes with another display name`() {
        assertTrue(DetailUiState(sourceIsShortReels = true, detail = detail("Drama provider")).isShortReels)
    }

    @Test
    fun `an offline Arvin detail retains number-only episodes without its extension`() {
        assertTrue(DetailUiState(detail = detail("Arvin-RF")).isShortReels)
    }

    @Test
    fun `ordinary providers retain their detailed episode presentation`() {
        assertFalse(DetailUiState().isShortReels)
        assertFalse(DetailUiState(detail = detail("Rentaro")).isShortReels)
    }

    private fun detail(sourceName: String) = AnimeDetail(
        sourceId = 1L,
        sourceName = sourceName,
        url = "/series",
        title = "Drama",
        posterUrl = null,
        description = "",
        author = null,
        artist = null,
        genres = emptyList(),
        status = AnimeStatus.ONGOING,
        episodes = emptyList(),
    )
}
