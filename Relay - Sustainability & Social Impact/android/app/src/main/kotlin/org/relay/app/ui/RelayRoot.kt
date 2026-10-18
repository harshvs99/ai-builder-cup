package org.relay.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.relay.app.MeshRepository
import org.relay.app.Permissions
import org.relay.app.rememberPermissions
import org.relay.app.ble.BlePermissions
import org.relay.app.ui.ask.AskScreen
import org.relay.app.ui.ask.CheckScreen
import org.relay.app.ui.ask.TriageScreen
import org.relay.app.ui.home.HomeScreen
import org.relay.app.ui.home.LanguageScreen
import org.relay.app.ui.home.RequestsScreen
import org.relay.app.ui.i18n.AppLanguages
import org.relay.app.ui.i18n.LocalStrings
import org.relay.app.ui.network.ConsoleScreen
import org.relay.app.ui.network.DeveloperScreen
import org.relay.app.ui.network.NetworkScreen
import org.relay.app.ui.network.SettingsScreen
import org.relay.app.ui.ready.HouseholdScreen
import org.relay.app.ui.ready.ReadyScreen
import org.relay.app.ui.request.RequestDetailScreen
import org.relay.app.ready.ReadyRepository
import org.relay.core.lang.LanguageCatalog
import org.relay.core.ready.ReadyPolicy
import org.relay.core.ready.RefreshTrigger
import org.relay.core.payload.Lang

/** Permission / Bluetooth setup actions used by the developer screens (built from [Permissions]). */
class SetupActions(
    val requestAll: () -> Unit,
    val enableBluetooth: () -> Unit,
    val openAppSettings: () -> Unit,
    /** Reads a counter bumped whenever permission state may have changed (read it in composition to refresh). */
    private val tickProvider: () -> Int,
) {
    val tick: Int get() = tickProvider()
}

private class Tab(val route: String, val icon: ImageVector, val label: @Composable () -> String)

/**
 * App shell: M3 Scaffold with a bottom NavigationBar (Home, Requests, Network, Settings) over a Compose
 * navigation graph. The bar is hidden on ask, check and language. The language screen is the start destination
 * until a language has been confirmed.
 *
 * Insets: the Scaffold applies no window insets, so a tab screen must apply `statusBarsPadding()` itself and a
 * flow screen uses StepTopAppBar (which handles the status bar) plus navigationBarsPadding()/imePadding().
 */
@Composable
fun RelayRoot(openReady: Boolean = false, onReadyOpened: () -> Unit = {}) {
    val ctx = LocalContext.current
    val navController = rememberNavController()
    val permissions = rememberPermissions()

    val actions = remember(permissions) {
        SetupActions(
            requestAll = { permissions.ensureBluetooth { permissions.ensureLocation { permissions.ensureMicrophone() } } },
            enableBluetooth = { permissions.enableBluetooth() },
            openAppSettings = { permissions.openAppSettings() },
            tickProvider = { permissions.tick },
        )
    }
    val nav = remember(navController, actions) { RelayNav(navController, actions) }

    // Permissions are asked when first needed (Home, first Send, first mic tap), never as one wall at start-up.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (BlePermissions.canRunMesh(ctx)) MeshRepository.startService(ctx)
    }

    val settings by MeshRepository.settings.flow.collectAsStateWithLifecycle()
    // Ready mode: a refresh when the network comes back, and one every 3 h while the app runs (app open is in MainActivity).
    val online = MeshRepository.status.collectAsStateWithLifecycle().value.internetAvailable
    LaunchedEffect(online) { if (online) ReadyRepository.refresh(ctx, RefreshTrigger.NETWORK_REGAINED) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(ReadyPolicy.PERIODIC_MS)
            ReadyRepository.refresh(ctx, RefreshTrigger.PERIODIC)
        }
    }
    val snackbar = remember { SnackbarHostState() }
    // The stored language was switched off (backend or build): say so once, then keep showing English.
    val resolution = settings.languageResolution
    val englishStrings = LocalStrings.current
    LaunchedEffect(resolution.unavailable, settings.languageNoticeFor) {
        if (LanguageCatalog.shouldAnnounce(resolution, Lang.fromTag(settings.languageNoticeFor))) {
            val gone = resolution.unavailable ?: return@LaunchedEffect
            MeshRepository.settings.update { it.copy(languageNoticeFor = gone.tag) }
            snackbar.showSnackbar(englishStrings.home.languageUnavailable(AppLanguages.of(gone).englishName))
        }
    }
    val startRoute = remember { if (settings.languageConfirmed) Routes.HOME else Routes.LANGUAGE }

    LaunchedEffect(openReady) {
        if (openReady && settings.languageConfirmed) { nav.openReady(); onReadyOpened() }
    }

    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val currentTab = Routes.tabOf(route)
    val s = LocalStrings.current.common
    val tabs = listOf(
        Tab(Routes.HOME, Icons.Outlined.Home) { s.navHome },
        Tab(Routes.REQUESTS, Icons.AutoMirrored.Outlined.List) { s.navRequests },
        Tab(Routes.NETWORK, Icons.Outlined.Share) { s.navNetwork },
        Tab(Routes.SETTINGS, Icons.Outlined.Settings) { s.navSettings },
    )

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!Routes.hidesBottomBar(route)) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val label = tab.label()
                        NavigationBarItem(
                            selected = currentTab == tab.route,
                            onClick = { nav.toTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Box(Modifier.padding(padding)) {
            NavHost(navController, startDestination = startRoute) {
                composable(Routes.LANGUAGE) { LanguageScreen(nav) }
                composable(Routes.HOME) { HomeScreen(nav) }
                composable(Routes.REQUESTS) { RequestsScreen(nav) }
                composable(Routes.REQUEST, listOf(navArgument("msgId") { type = NavType.StringType })) { e ->
                    RequestDetailScreen(nav, e.arguments?.getString("msgId").orEmpty())
                }
                navigation(startDestination = Routes.ASK, route = Routes.ASK_FLOW) {
                    composable(
                        Routes.ASK,
                        listOf(
                            navArgument("mode") { type = NavType.StringType; defaultValue = AskMode.VOICE.arg },
                            navArgument("prefillFrom") { type = NavType.StringType; nullable = true; defaultValue = null },
                        ),
                    ) { e ->
                        AskScreen(
                            nav,
                            AskMode.fromArg(e.arguments?.getString("mode")),
                            e.arguments?.getString("prefillFrom"),
                            remember(e) { navController.getBackStackEntry(Routes.ASK_FLOW) },
                        )
                    }
                    composable(Routes.TRIAGE) { e -> TriageScreen(nav, remember(e) { navController.getBackStackEntry(Routes.ASK_FLOW) }) }
                    // remember: after Send pops the flow, this screen still composes during its exit animation.
                    composable(Routes.CHECK) { e -> CheckScreen(nav, remember(e) { navController.getBackStackEntry(Routes.ASK_FLOW) }) }
                }
                composable(Routes.NETWORK) { NetworkScreen(nav) }
                composable(Routes.CONSOLE) { ConsoleScreen(nav) }
                composable(Routes.SETTINGS) { SettingsScreen(nav) }
                composable(Routes.READY) { ReadyScreen(nav) }
                composable(Routes.HOUSEHOLD) { HouseholdScreen(nav) }
                composable(Routes.DEVELOPER) { DeveloperScreen(nav) }
                composable(Routes.LEGACY_HOME) { LegacyHomeScreen(actions) }
            }
        }
    }
}
