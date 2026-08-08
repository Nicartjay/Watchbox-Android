package space.nicart.watchbox.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.media3.common.util.UnstableApi
import androidx.navigation.toRoute
import space.nicart.watchbox.AppContainer
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.data.local.WatchHistoryEntry
import space.nicart.watchbox.domain.MediaCard
import space.nicart.watchbox.ui.detail.DetailScreen
import space.nicart.watchbox.ui.detail.DetailViewModel
import space.nicart.watchbox.ui.home.HomeScreen
import space.nicart.watchbox.ui.home.HomeViewModel
import space.nicart.watchbox.ui.library.LibraryScreen
import space.nicart.watchbox.ui.library.LibraryViewModel
import space.nicart.watchbox.ui.navigation.AppTab
import space.nicart.watchbox.ui.navigation.Routes
import space.nicart.watchbox.ui.navigation.WbNavigationBar
import space.nicart.watchbox.ui.navigation.rememberWbNavBarScrollState
import space.nicart.watchbox.ui.player.PlayerScreen
import space.nicart.watchbox.ui.player.PlayerViewModel
import space.nicart.watchbox.ui.search.SearchScreen
import space.nicart.watchbox.ui.search.SearchViewModel
import space.nicart.watchbox.ui.settings.SettingsScreen
import space.nicart.watchbox.ui.settings.SettingsViewModel

/**
 * Root navigation.
 *
 * A single flat back stack, matching Nuvio's Navigation-3 model: the tab shell is
 * one destination and detail/player push on top of it, so full-screen routes
 * naturally cover the nav pill.
 */
@UnstableApi
@Composable
fun WatchBoxApp(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.wb.colors.background),
    ) {
        NavHost(
            navController = navController,
            startDestination = Routes.Tabs,
            enterTransition = { fadeIn(tween(220)) },
            exitTransition = { fadeOut(tween(180)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(180)) },
        ) {
            composable<Routes.Tabs> {
                TabShell(
                    container = container,
                    onOpenDetail = { detailPath, title ->
                        navController.navigate(Routes.Detail(detailPath, title))
                    },
                    onResume = { entry -> navController.navigateToPlayer(entry) },
                )
            }

            composable<Routes.Detail>(
                enterTransition = { slideInHorizontally(tween(260)) { it / 4 } + fadeIn(tween(220)) },
                popExitTransition = {
                    slideOutHorizontally(tween(220)) { it / 4 } + fadeOut(tween(180))
                },
            ) { entry ->
                val route = entry.toRoute<Routes.Detail>()
                val viewModel: DetailViewModel = viewModel(
                    key = "detail-${route.detailPath}",
                    factory = DetailViewModel.factory(
                        repository = container.repository,
                        store = container.store,
                        detailPath = route.detailPath,
                    ),
                )
                DetailScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onPlay = { season, episode, resumeMs ->
                        navController.navigate(
                            Routes.Player(route.detailPath, season, episode, resumeMs),
                        )
                    },
                    onOpenTitle = { card ->
                        navController.navigate(Routes.Detail(card.detailPath, card.title))
                    },
                )
            }

            composable<Routes.Player>(
                enterTransition = { fadeIn(tween(220)) },
                exitTransition = { fadeOut(tween(180)) },
            ) { entry ->
                val route = entry.toRoute<Routes.Player>()
                val application = androidx.compose.ui.platform.LocalContext.current
                    .applicationContext as android.app.Application
                val viewModel: PlayerViewModel = viewModel(
                    key = "player-${route.detailPath}-${route.season}",
                    factory = PlayerViewModel.factory(
                        application = application,
                        repository = container.repository,
                        store = container.store,
                        detailPath = route.detailPath,
                        season = route.season,
                        episode = route.episode,
                        resumeMs = route.resumeMs,
                    ),
                )
                PlayerScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

private fun NavHostController.navigateToPlayer(entry: WatchHistoryEntry) {
    navigate(
        Routes.Player(
            detailPath = entry.detailPath,
            season = entry.season,
            episode = entry.episode,
            resumeMs = entry.positionMs,
        ),
    )
}

/**
 * The four-tab shell.
 *
 * Tab content is kept alive via a `SaveableStateHolder` keyed by tab name, which
 * is how Nuvio's `AppTabHost` preserves scroll position without giving each tab
 * its own back stack.
 */
@Composable
private fun TabShell(
    container: AppContainer,
    onOpenDetail: (detailPath: String, title: String) -> Unit,
    onResume: (WatchHistoryEntry) -> Unit,
) {
    var selectedTab by remember { mutableStateOf(AppTab.HOME) }
    val stateHolder = rememberSaveableStateHolder()
    val navScrollState = rememberWbNavBarScrollState()

    val openCard: (MediaCard) -> Unit = { card -> onOpenDetail(card.detailPath, card.title) }

    Box(modifier = Modifier.fillMaxSize()) {
        stateHolder.SaveableStateProvider(selectedTab.name) {
            when (selectedTab) {
                AppTab.HOME -> {
                    val viewModel: HomeViewModel = viewModel(
                        key = "home",
                        factory = HomeViewModel.factory(container.repository, container.store),
                    )
                    HomeScreen(
                        viewModel = viewModel,
                        onOpenTitle = openCard,
                        onOpenDetailPath = onOpenDetail,
                        onResume = onResume,
                        onViewAll = { _, _ -> },
                        navScrollState = navScrollState,
                    )
                }

                AppTab.SEARCH -> {
                    val viewModel: SearchViewModel = viewModel(
                        key = "search",
                        factory = SearchViewModel.factory(container.repository, container.store),
                    )
                    SearchScreen(viewModel = viewModel, onOpenTitle = openCard)
                }

                AppTab.LIBRARY -> {
                    val viewModel: LibraryViewModel = viewModel(
                        key = "library",
                        factory = LibraryViewModel.factory(container.store),
                    )
                    LibraryScreen(
                        viewModel = viewModel,
                        onOpenTitle = onOpenDetail,
                        onResume = onResume,
                    )
                }

                AppTab.SETTINGS -> {
                    val viewModel: SettingsViewModel = viewModel(
                        key = "settings",
                        factory = SettingsViewModel.factory(container.store),
                    )
                    SettingsScreen(viewModel = viewModel)
                }
            }
        }

        WbNavigationBar(
            selected = selectedTab,
            onSelect = { tab ->
                selectedTab = tab
                navScrollState.reveal()
            },
            scrollState = navScrollState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
