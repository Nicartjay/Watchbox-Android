package space.nicart.watchbox.ui.player

import androidx.compose.ui.unit.dp
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import space.nicart.watchbox.core.ui.FormFactor
import space.nicart.watchbox.domain.AnimeDetail
import space.nicart.watchbox.domain.AnimeStatus
import space.nicart.watchbox.ui.navigation.Routes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerPresentationTest {
    @Test
    fun `Arvin playback is portrait on phones and tablets but not televisions`() {
        assertTrue(usesPortraitPlayer(true, FormFactor.COMPACT))
        assertTrue(usesPortraitPlayer(true, FormFactor.TABLET))
        assertFalse(usesPortraitPlayer(true, FormFactor.TV))
    }

    @Test
    fun `ordinary providers retain the standard player on every form factor`() {
        FormFactor.entries.forEach { assertFalse(usesPortraitPlayer(false, it)) }
    }

    @Test
    fun `a tall phone stage uses the full screen width`() {
        assertEquals(400.dp, portraitPlayerWidth(400.dp, 900.dp))
    }

    @Test
    fun `a landscape tablet still has a portrait 9 by 16 stage`() {
        assertEquals(450.dp, portraitPlayerWidth(1200.dp, 800.dp))
    }

    @Test
    fun `a portrait tablet does not stretch the video across its width`() {
        assertEquals(720.dp, portraitPlayerWidth(800.dp, 1280.dp))
    }

    @Test
    fun `a tall reel stage retains the episode rail's existing bottom position`() {
        assertEquals(160.dp, shortReelsRailBottomPadding(800.dp, hasEpisodes = true))
    }

    @Test
    fun `six reel icons fit below the header on short phones`() {
        assertEquals(120.dp, shortReelsRailBottomPadding(520.dp, hasEpisodes = true))
        assertEquals(80.dp, shortReelsRailBottomPadding(480.dp, hasEpisodes = true))
    }

    @Test
    fun `hiding the quality icon removes its space from the right rail`() {
        assertEquals(
            136.dp,
            shortReelsRailBottomPadding(480.dp, hasEpisodes = true, hasQualityChoices = false),
        )
    }

    @Test
    fun `hiding the subtitle icon removes its space from the right rail`() {
        assertEquals(
            136.dp,
            shortReelsRailBottomPadding(480.dp, hasEpisodes = true, hasSubtitles = false),
        )
        assertEquals(
            160.dp,
            shortReelsRailBottomPadding(
                480.dp, hasEpisodes = true, hasQualityChoices = false, hasSubtitles = false,
            ),
        )
    }

    @Test
    fun `very short reel stages keep timeline clearance and allow the rail to scroll`() {
        assertEquals(72.dp, shortReelsRailBottomPadding(400.dp, hasEpisodes = true))
        assertEquals(160.dp, shortReelsRailBottomPadding(520.dp, hasEpisodes = false))
    }

    @Test
    fun `upward and downward swipes select the expected neighboring episode`() {
        assertEquals(ReelSwipeAction.NEXT, reelSwipeAction(-120f, 96f, true, true))
        assertEquals(ReelSwipeAction.PREVIOUS, reelSwipeAction(120f, 96f, true, true))
    }

    @Test
    fun `small drags and taps cannot switch episodes`() {
        listOf(-95f, 0f, 95f).forEach {
            assertEquals(ReelSwipeAction.NONE, reelSwipeAction(it, 96f, true, true))
        }
        assertEquals(ReelSwipeAction.NEXT, reelSwipeAction(-96f, 96f, true, true))
        assertEquals(ReelSwipeAction.PREVIOUS, reelSwipeAction(96f, 96f, true, true))
    }

    @Test
    fun `swipes do not wrap beyond the first or last episode`() {
        assertEquals(ReelSwipeAction.NONE, reelSwipeAction(-500f, 96f, false, true))
        assertEquals(ReelSwipeAction.NONE, reelSwipeAction(500f, 96f, true, false))
        assertEquals(ReelSwipeAction.NONE, reelSwipeAction(-500f, 96f, false, false))
    }

    @Test
    fun `an invalid swipe threshold cannot trigger navigation`() {
        assertEquals(ReelSwipeAction.NONE, reelSwipeAction(-500f, 0f, true, true))
    }

    @Test
    fun `double taps keep the ten-second forward and rewind actions`() {
        listOf(360, 800, 1920).forEach { width ->
            assertEquals(-10_000L, doubleTapSeekDelta(width * 0.25f, width))
            assertEquals(10_000L, doubleTapSeekDelta(width * 0.75f, width))
        }
    }

    @Test
    fun `Arvin identity is available before detail or stream resolution`() {
        val state = PlayerUiState(sourceIsShortReels = true)
        assertTrue(state.isResolving)
        assertTrue(state.isShortReels)
        assertTrue(state.streams.isEmpty())
    }

    @Test
    fun `a saved Arvin detail identifies an offline video without an extension`() {
        val saved = AnimeDetail(
            sourceId = 1L,
            sourceName = "Arvin-HD",
            url = "/series",
            title = "Short drama",
            posterUrl = null,
            description = "",
            author = null,
            artist = null,
            genres = emptyList(),
            status = AnimeStatus.ONGOING,
            episodes = emptyList(),
        )
        assertTrue(PlayerUiState(detail = saved).isShortReels)
        assertFalse(PlayerUiState(detail = saved.copy(sourceName = "Rentaro")).isShortReels)
    }

    @Test
    fun `new player routes retain the saved provider name`() {
        val route = Routes.Player(1L, "/series", "/episode", sourceName = "Arvin-XY")
        assertEquals(route, Json.decodeFromString<Routes.Player>(Json.encodeToString(route)))
    }

    @Test
    fun `old saved player routes remain readable`() {
        val route = Json.decodeFromString<Routes.Player>(
            """{"sourceId":1,"animeUrl":"/series","episodeUrl":"/episode","resumeMs":15000}""",
        )
        assertEquals("", route.sourceName)
        assertEquals(15_000L, route.resumeMs)
    }
}
