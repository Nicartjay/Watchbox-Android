package space.nicart.watchbox.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import space.nicart.watchbox.AppContainer
import space.nicart.watchbox.data.local.AppSettings
import space.nicart.watchbox.data.local.WatchHistoryEntry
import space.nicart.watchbox.domain.AnimeCard
import space.nicart.watchbox.ui.detail.DetailScreen
import space.nicart.watchbox.ui.detail.DetailViewModel
import space.nicart.watchbox.ui.extensions.ExtensionsScreen
import space.nicart.watchbox.ui.extensions.ExtensionsViewModel
import space.nicart.watchbox.ui.library.LibraryScreen
import space.nicart.watchbox.ui.library.LibraryViewModel
import space.nicart.watchbox.ui.navigation.AppTab
import space.nicart.watchbox.ui.navigation.Routes
import space.nicart.watchbox.ui.player.PlayerScreen
import space.nicart.watchbox.ui.player.PlayerViewModel
import space.nicart.watchbox.ui.player.subtitleStyle
import space.nicart.watchbox.ui.search.SearchViewModel
import space.nicart.watchbox.ui.settings.SettingsScreen
import space.nicart.watchbox.ui.settings.SettingsViewModel
import space.nicart.watchbox.ui.browse.BrowseViewModel
import space.nicart.watchbox.ui.browse.SourceListViewModel
import androidx.compose.foundation.layout.Box
import space.nicart.watchbox.ui.update.UpdatePromptDialog
import space.nicart.watchbox.ui.update.UpdatePromptState
import space.nicart.watchbox.ui.update.UpdatePromptViewModel
import androidx.compose.ui.platform.LocalContext
import space.nicart.watchbox.ui.source.SourcePreferenceGroup
import space.nicart.watchbox.ui.source.SourceSettingsScreen
import space.nicart.watchbox.ui.source.readSourcePreferences

/**
 * TV entry point.
 *
 * Deliberately a parallel root rather than a branch inside the phone app. Every
 * ViewModel, repository and route is shared - only the presentation differs - so a
 * TV layout change cannot regress the phone UI, and the phone APK ships none of
 * this code.
 *
 * Screens that are already usable with a D-pad are reused as-is. Detail, Library,
 * Search, Settings and Extensions are list-based and become focusable once their
 * rows are, so re-implementing them for TV would be duplication without benefit.
 * Home is replaced because its phone layout - a scrolling hero card - reads badly at
 * distance.
 */
@UnstableApi
@Composable
fun TvApp(container: AppContainer, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    // App-scoped launch check, as on the phone. It matters more here: a television is the
    // device least likely to be updated deliberately, since there is no store entry and
    // sideloading a new APK by remote is tedious.
    val updateViewModel: UpdatePromptViewModel = viewModel(
        factory = UpdatePromptViewModel.factory(
            store = container.store,
            checker = container.updateChecker,
            installer = container.updateInstaller,
        ),
    )
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()

    Box(modifier = modifier) {

    NavHost(
        navController = navController,
        startDestination = Routes.Tabs,
    ) {
        composable<Routes.Tabs> {
            TvTabShell(container = container) { tab, selectTab ->
                TvTabContent(
                    tab = tab,
                    container = container,
                    navController = navController,
                    onSelectTab = selectTab,
                )
            }
        }

        composable<Routes.Detail> { entry ->
            val route = entry.toRoute<Routes.Detail>()
            val viewModel: DetailViewModel = viewModel(
                key = "detail-${route.sourceId}-${route.animeUrl}",
                factory = DetailViewModel.factory(
                    repository = container.repository,
                    store = container.store,
                    countryResolver = container.countryResolver,
                    downloads = container.downloadController,
                    downloadStorage = container.downloadStorage,
                    subtitleRepository = container.subtitleRepository,
                    sourceId = route.sourceId,
                    animeUrl = route.animeUrl,
                ),
            )
            DetailScreen(
                viewModel = viewModel,
                onBack = navController::popBackStack,
                extensionForSource = container.extensionManager::extensionForSource,
                onOpenAnime = { navController.openAnime(it) },
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
            )
        }

        composable<Routes.Player> { entry ->
            val route = entry.toRoute<Routes.Player>()
            val viewModel: PlayerViewModel = viewModel(
                key = "player-${route.sourceId}-${route.episodeUrl}",
                factory = PlayerViewModel.factory(
                    container.repository,
                    container.subtitleRepository,
                    container.skipRepository,
                    container.store,
                    container.downloadEngine,
                    route.sourceId,
                    route.animeUrl,
                    route.episodeUrl,
                    route.resumeMs,
                ),
            )
            val settings by container.store.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())

            PlayerScreen(
                viewModel = viewModel,
                castManager = container.castManager,
                onBack = navController::popBackStack,
                subtitleStyle = settings.subtitleStyle(),
                onSetSubtitleSize = { scope.launch { container.store.setSubtitleSize(it) } },
                onSetSubtitleBackground = {
                    scope.launch { container.store.setSubtitleBackground(it) }
                },
                onSetSubtitleEdgeWidth = {
                    scope.launch { container.store.setSubtitleEdgeWidth(it) }
                },
                onSetSubtitleColor = {
                    scope.launch { container.store.setSubtitleTextColor(it) }
                },
            )
        }

        composable<Routes.SourceBrowse> { entry ->
            val route = entry.toRoute<Routes.SourceBrowse>()
            val viewModel: BrowseViewModel = viewModel(
                key = "browse-${route.sourceId}",
                factory = BrowseViewModel.factory(container.repository, route.sourceId),
            )
            val artwork: TvArtworkViewModel = viewModel(
                key = "tv-artwork-browse-${route.sourceId}",
                factory = TvArtworkViewModel.factory(container.repository),
            )
            TvSourceBrowseScreen(
                sourceName = route.sourceName,
                viewModel = viewModel,
                artworkViewModel = artwork,
                supportsLatest = container.extensionManager
                    .catalogueSourceById(route.sourceId)
                    ?.let { runCatching { it.supportsLatest }.getOrDefault(false) }
                    ?: false,
                onBack = navController::popBackStack,
                onOpenAnime = { navController.openAnime(it) },
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
                // Navigates, as on the phone. This was an empty lambda, so the per-extension
                // settings button was present, focusable and did nothing at all - and the
                // destination it needs was never registered on this nav host either.
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

            // Read once per visit rather than per recomposition: building the preference
            // tree runs extension code and touches SharedPreferences.
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

        // Above the nav host so it shows on whichever tab the user landed on.
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

@UnstableApi
@Composable
private fun TvTabContent(
    tab: AppTab,
    container: AppContainer,
    navController: NavHostController,
    onSelectTab: (AppTab) -> Unit,
) {
    val openAnime: (AnimeCard) -> Unit = { navController.openAnime(it) }

    when (tab) {
        AppTab.HOME -> {
            val viewModel: TvHomeViewModel = viewModel(
                key = "tv-home",
                factory = TvHomeViewModel.factory(
                    container.repository,
                    container.extensionManager,
                    container.store,
                ),
            )
            val artwork: TvArtworkViewModel = viewModel(
                key = "tv-artwork",
                factory = TvArtworkViewModel.factory(container.repository),
            )

            // The same instance the shell owns - resolved by key, not created here - so the
            // hero can see when the source drawer is covering it.
            val sourceViewModel: TvSourceViewModel = viewModel(
                key = "tv-source",
                factory = TvSourceViewModel.factory(
                    container.extensionManager,
                    container.store,
                ),
            )
            val pickerOpen by sourceViewModel.pickerOpen.collectAsStateWithLifecycle()

            TvHomeScreen(
                viewModel = viewModel,
                artworkViewModel = artwork,
                onOpenAnime = openAnime,
                onResume = { entry -> navController.openPlayer(entry) },
                // Switches tab rather than navigating: repositories live in the
                // Settings tab, which is not a nav destination.
                onOpenSettings = { onSelectTab(AppTab.SETTINGS) },
                pickerOpen = pickerOpen,
                onPlay = { request ->
                    navController.navigate(
                        Routes.Player(
                            sourceId = request.sourceId,
                            animeUrl = request.animeUrl,
                            episodeUrl = request.episodeUrl,
                            resumeMs = request.resumeMs,
                        ),
                    )
                },
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
            // Search follows the rail's source rather than querying everything: on a
            // television one source is the chosen channel, and grouped multi-source
            // results are a phone affordance that needs far more scrolling with a
            // remote. Driven from the stored id so the two cannot disagree.
            val settings by container.store.settings.collectAsStateWithLifecycle(
                initialValue = null,
            )
            val tvSourceId = settings?.tvSourceId
            LaunchedEffect(tvSourceId) {
                if (tvSourceId != null) viewModel.onSelectSource(tvSourceId)
            }

            TvSearchScreen(viewModel = viewModel, onOpenAnime = openAnime)
        }

        AppTab.BROWSE -> {
            val viewModel: SourceListViewModel = viewModel(
                key = "sources",
                factory = SourceListViewModel.factory(container.extensionManager),
            )
            val updateCount by container.extensionManager.updateCount
                .collectAsStateWithLifecycle()

            TvSourceListScreen(
                viewModel = viewModel,
                updateCount = updateCount,
                onOpenSource = { source ->
                    navController.navigate(
                        Routes.SourceBrowse(source.id, source.name),
                    )
                },
                onOpenExtensions = { navController.navigate(Routes.Extensions) },
            )
        }

        AppTab.LIBRARY -> {
            val viewModel: LibraryViewModel = viewModel(
                key = "library",
                factory = LibraryViewModel.factory(
                    store = container.store,
                    downloads = container.downloadController,
                    storage = container.downloadStorage,
                ),
            )
            LibraryScreen(
                viewModel = viewModel,
                onOpenAnime = openAnime,
                onResume = { entry -> navController.openPlayer(entry) },
                onPlayDownload = { entry ->
                    navController.navigate(
                        Routes.Player(
                            sourceId = entry.sourceId,
                            animeUrl = entry.animeUrl,
                            episodeUrl = entry.episodeUrl,
                        ),
                    )
                },
            )
        }

        AppTab.SETTINGS -> {
            val viewModel: SettingsViewModel = viewModel(
                key = "settings",
                factory = SettingsViewModel.factory(
                    container.store,
                    container.updateChecker,
                    container.updateInstaller,
                    container.downloadController,
                    container.downloadStorage,
                    space.nicart.watchbox.BuildConfig.VERSION_NAME,
                ),
            )
            SettingsScreen(viewModel = viewModel)
        }
    }
}

private fun NavHostController.openAnime(card: AnimeCard) {
    navigate(Routes.Detail(card.sourceId, card.url, card.title))
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
