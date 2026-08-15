package space.nicart.watchbox.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import space.nicart.watchbox.AppContainer
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.data.local.WatchHistoryEntry
import space.nicart.watchbox.data.local.WatchlistEntry
import space.nicart.watchbox.data.local.AppSettings
import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.ui.browse.BrowseScreen
import space.nicart.watchbox.ui.browse.BrowseViewModel
import space.nicart.watchbox.ui.browse.SourceListScreen
import space.nicart.watchbox.ui.browse.SourceListViewModel
import space.nicart.watchbox.ui.detail.DetailScreen
import space.nicart.watchbox.ui.detail.DetailViewModel
import space.nicart.watchbox.ui.extensions.ExtensionsScreen
import space.nicart.watchbox.ui.extensions.ExtensionsViewModel
import space.nicart.watchbox.ui.home.HomeScreen
import space.nicart.watchbox.ui.home.HomeViewModel
import space.nicart.watchbox.ui.library.LibraryScreen
import space.nicart.watchbox.ui.library.LibraryViewModel
import space.nicart.watchbox.ui.navigation.AppTab
import space.nicart.watchbox.ui.navigation.Routes
import space.nicart.watchbox.core.ui.LocalLayoutMetrics
import space.nicart.watchbox.ui.navigation.WbNavigationRail
import space.nicart.watchbox.ui.navigation.WbNavigationBar
import space.nicart.watchbox.ui.navigation.rememberWbNavBarScrollState
import space.nicart.watchbox.ui.player.PlayerScreen
import space.nicart.watchbox.ui.player.subtitleStyle
import space.nicart.watchbox.ui.player.PlayerViewModel
import space.nicart.watchbox.ui.search.SearchScreen
import space.nicart.watchbox.ui.source.SourcePreferenceGroup
import space.nicart.watchbox.ui.source.SourceSettingsScreen
import space.nicart.watchbox.ui.source.readSourcePreferences
import space.nicart.watchbox.ui.search.SearchViewModel
import space.nicart.watchbox.ui.settings.SettingsScreen
import space.nicart.watchbox.ui.settings.SettingsViewModel
import space.nicart.watchbox.ui.update.UpdatePromptDialog
import space.nicart.watchbox.ui.update.UpdatePromptState
import space.nicart.watchbox.ui.update.UpdatePromptViewModel

/**
 * Root navigation.
 *
 * One flat back stack, matching Nuvio's model: the tab shell is a single
 * destination and detail/player/browse push on top, so full-screen routes cover
 * the nav pill without any per-tab stack bookkeeping.
 */
@UnstableApi
@Composable
fun WatchBoxApp(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    // App-scoped, so the launch check runs wherever the user lands rather than only if
    // they open Settings - which is where the manual button already was.
    val updateViewModel: UpdatePromptViewModel = viewModel(
        factory = UpdatePromptViewModel.factory(
            store = container.store,
            checker = container.updateChecker,
            installer = container.updateInstaller,
        ),
    )
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()

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
                    onOpenAnime = navController::openAnime,
                    onPlayAnime = navController::playAnime,
                    onResume = navController::openPlayer,
                    onOpenExtensions = { navController.navigate(Routes.Extensions) },
                    onOpenSource = { id, name ->
                        navController.navigate(Routes.SourceBrowse(id, name))
                    },
                )
            }

            composable<Routes.Detail>(
                enterTransition = {
                    slideInHorizontally(tween(260)) { it / 4 } + fadeIn(tween(220))
                },
                popExitTransition = {
                    slideOutHorizontally(tween(220)) { it / 4 } + fadeOut(tween(180))
                },
            ) { entry ->
                val route = entry.toRoute<Routes.Detail>()
                val viewModel: DetailViewModel = viewModel(
                    key = "detail-${route.sourceId}-${route.animeUrl}",
                    factory = DetailViewModel.factory(
                        repository = container.repository,
                        store = container.store,
                        countryResolver = container.countryResolver,
                        sourceId = route.sourceId,
                        animeUrl = route.animeUrl,
                    ),
                )
                DetailScreen(
                    viewModel = viewModel,
                    onBack = navController::popBackStack,
                    extensionForSource = container.extensionManager::extensionForSource,
                    // A suggestion opens its own detail page, pushed onto the
                    // stack so Back returns to the title it was suggested from.
                    onOpenAnime = navController::openAnime,
                    onPlay = { episode, resumeMs ->
                        navController.navigate(
                            Routes.Player(
                                sourceId = route.sourceId,
                                animeUrl = route.animeUrl,
                                episodeUrl = episode.url,
                                resumeMs = resumeMs,
                            ),
                        )
                    },
                    autoPlay = route.autoPlay,
                )
            }

            composable<Routes.Player> { entry ->
                val route = entry.toRoute<Routes.Player>()
                val viewModel: PlayerViewModel = viewModel(
                    key = "player-${route.sourceId}-${route.episodeUrl}",
                    factory = PlayerViewModel.factory(
                        repository = container.repository,
                        subtitles = container.subtitleRepository,
                        skips = container.skipRepository,
                        store = container.store,
                        sourceId = route.sourceId,
                        animeUrl = route.animeUrl,
                        episodeUrl = route.episodeUrl,
                        resumeMs = route.resumeMs,
                    ),
                )
                val playerSettings by container.store.settings
                    .collectAsStateWithLifecycle(initialValue = AppSettings())

                PlayerScreen(
                    viewModel = viewModel,
                    castManager = container.castManager,
                    onBack = navController::popBackStack,
                    subtitleStyle = playerSettings.subtitleStyle(),
                    onSetSubtitleSize = { size ->
                        scope.launch { container.store.setSubtitleSize(size) }
                    },
                    onSetSubtitleBackground = { background ->
                        scope.launch { container.store.setSubtitleBackground(background) }
                    },
                    onSetSubtitleEdgeWidth = { width ->
                        scope.launch { container.store.setSubtitleEdgeWidth(width) }
                    },
                    onSetSubtitleColor = { color ->
                        scope.launch { container.store.setSubtitleTextColor(color) }
                    },
                )
            }

            composable<Routes.SourceBrowse> { entry ->
                val route = entry.toRoute<Routes.SourceBrowse>()
                val viewModel: BrowseViewModel = viewModel(
                    key = "browse-${route.sourceId}",
                    factory = BrowseViewModel.factory(container.repository, route.sourceId),
                )
                BrowseScreen(
                    sourceName = route.sourceName,
                    viewModel = viewModel,
                    supportsLatest = container.extensionManager
                        .catalogueSourceById(route.sourceId)
                        ?.let { runCatching { it.supportsLatest }.getOrDefault(false) }
                        ?: false,
                    onBack = navController::popBackStack,
                    onOpenAnime = navController::openAnime,
                )
            }

            composable<Routes.Extensions> {
                val viewModel: ExtensionsViewModel = viewModel(
                    key = "extensions",
                    factory = ExtensionsViewModel.factory(
                        container.extensionManager,
                        container.store,
                    ),
                )
                ExtensionsScreen(
                    viewModel = viewModel,
                    onBack = navController::popBackStack,
                    onOpenSettings = { extension ->
                        navController.navigate(
                            Routes.SourceSettings(extension.pkgName, extension.name),
                        )
                    },
                )
            }

            composable<Routes.SourceSettings> { entry ->
                val route = entry.toRoute<Routes.SourceSettings>()
                val context = LocalContext.current

                // Read once per visit rather than per recomposition: building the
                // preference tree runs extension code and touches SharedPreferences.
                val groups = remember(route.pkgName) {
                    container.extensionManager.installed.value
                        .firstOrNull { it.pkgName == route.pkgName }
                        ?.sources
                        ?.map { source ->
                            SourcePreferenceGroup(
                                sourceId = source.id,
                                sourceName = source.name,
                                preferences = readSourcePreferences(context, source),
                            )
                        }
                        ?.filter { it.preferences.isNotEmpty() }
                        .orEmpty()
                }

                SourceSettingsScreen(
                    extensionName = route.extensionName,
                    groups = groups,
                    onBack = navController::popBackStack,
                )
            }
        }

        // Above everything, including the nav host, so it is visible on whichever screen
        // the user landed on.
        UpdatePromptDialog(
            state = updateState,
            onDownload = {
                (updateState as? UpdatePromptState.Available)
                    ?.let { updateViewModel.download(it.update) }
            },
            onSkip = {
                (updateState as? UpdatePromptState.Available)
                    ?.let { updateViewModel.skip(it.update) }
            },
            onDismiss = updateViewModel::dismiss,
        )
    }
}

private fun NavHostController.openAnime(card: AnimeCard) {
    navigate(Routes.Detail(card.sourceId, card.url, card.title))
}

/**
 * Opens a title and starts playing it.
 *
 * Goes through the detail route rather than straight to the player: the episode to
 * play is only known once the episode list has loaded, and doing that here would
 * leave the tap unacknowledged while the request ran.
 */
private fun NavHostController.playAnime(card: AnimeCard) {
    navigate(Routes.Detail(card.sourceId, card.url, card.title, autoPlay = true))
}

private fun NavHostController.openPlayer(entry: WatchHistoryEntry) {
    navigate(
        Routes.Player(
            sourceId = entry.sourceId,
            animeUrl = entry.animeUrl,
            episodeUrl = entry.episodeUrl,
            resumeMs = entry.positionMs,
        ),
    )
}

/**
 * The tab shell.
 *
 * Tab content is kept alive by a `SaveableStateHolder` keyed on tab name, so
 * scroll position survives switching without giving each tab its own back stack.
 */
@Composable
private fun TabShell(
    container: AppContainer,
    onOpenAnime: (AnimeCard) -> Unit,
    onResume: (WatchHistoryEntry) -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenSource: (sourceId: Long, sourceName: String) -> Unit,
    /** Opens a title and starts it playing, for the home hero's primary action. */
    onPlayAnime: (AnimeCard) -> Unit,
) {
    var selectedTab by remember { mutableStateOf(AppTab.HOME) }
    val stateHolder = rememberSaveableStateHolder()
    val navScrollState = rememberWbNavBarScrollState()
    val metrics = LocalLayoutMetrics.current

    val openSaved: (WatchlistEntry) -> Unit = { entry ->
        onOpenAnime(
            AnimeCard(
                sourceId = entry.sourceId,
                url = entry.animeUrl,
                title = entry.title,
                posterUrl = entry.posterUrl,
                sourceName = entry.sourceName,
            ),
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Padded rather than overlaid: on a tablet the rail is permanently expanded, so
        // an overlay would cover a fixed strip of every screen. The TV rail can overlay
        // because it is collapsed except while focused.
        val contentInset = if (metrics.usesNavRail) TABLET_RAIL_WIDTH else 0.dp

        Box(modifier = Modifier.fillMaxSize().padding(start = contentInset)) {
        stateHolder.SaveableStateProvider(selectedTab.name) {
            when (selectedTab) {
                AppTab.HOME -> {
                    val viewModel: HomeViewModel = viewModel(
                        key = "home",
                        factory = HomeViewModel.factory(
                            container.repository,
                            container.extensionManager,
                            container.store,
                        ),
                    )
                    HomeScreen(
                        viewModel = viewModel,
                        onOpenAnime = onOpenAnime,
                        onPlayAnime = onPlayAnime,
                        onResume = onResume,
                        onOpenSaved = openSaved,
                        onBrowseSource = onOpenSource,
                        onInstallExtensions = onOpenExtensions,
                        // Switches tab rather than navigating: repositories live in
                        // Settings, and the tabs are not nav destinations.
                        onOpenSettings = { selectedTab = AppTab.SETTINGS },
                        navScrollState = navScrollState,
                    )
                }

                AppTab.SEARCH -> {
                    val viewModel: SearchViewModel = viewModel(
                        key = "search",
                        factory = SearchViewModel.factory(
                            container.repository,
                            container.store,
                            container.extensionManager,
                        ),
                    )
                    SearchScreen(viewModel = viewModel, onOpenAnime = onOpenAnime)
                }

                AppTab.BROWSE -> {
                    val viewModel: SourceListViewModel = viewModel(
                        key = "sources",
                        factory = SourceListViewModel.factory(container.extensionManager),
                    )
                    val updateCount by container.extensionManager.updateCount
                        .collectAsStateWithLifecycle()

                    SourceListScreen(
                        viewModel = viewModel,
                        onOpenSource = { onOpenSource(it.id, it.name) },
                        onOpenExtensions = onOpenExtensions,
                        updateCount = updateCount,
                    )
                }

                AppTab.LIBRARY -> {
                    val viewModel: LibraryViewModel = viewModel(
                        key = "library",
                        factory = LibraryViewModel.factory(container.store),
                    )
                    LibraryScreen(
                        viewModel = viewModel,
                        onOpenAnime = onOpenAnime,
                        onResume = onResume,
                    )
                }

                AppTab.SETTINGS -> {
                    val viewModel: SettingsViewModel = viewModel(
                        key = "settings",
                        factory = SettingsViewModel.factory(
                            store = container.store,
                            updateChecker = container.updateChecker,
                            updateInstaller = container.updateInstaller,
                            currentVersion = space.nicart.watchbox.BuildConfig.VERSION_NAME,
                        ),
                    )
                    SettingsScreen(viewModel = viewModel)
                }
            }
        }
        }

        // A rail on wide tablets, the floating pill otherwise. In landscape on a held
        // tablet the bottom edge is the hardest place to reach two-handed, and a
        // full-width bar there wastes the height that matters for content.
        if (metrics.usesNavRail) {
            WbNavigationRail(
                selected = selectedTab,
                onSelect = { selectedTab = it },
                // Always labelled here: unlike a TV there is no focus to expand on,
                // and a tablet has the width to spare.
                expanded = true,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        } else {
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
}

/**
 * Width reserved for the tablet navigation rail.
 *
 * Matches the rail's expanded width, since on a tablet it is always labelled.
 */
private val TABLET_RAIL_WIDTH = 220.dp
