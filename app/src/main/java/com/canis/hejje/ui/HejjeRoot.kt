package com.canis.hejje.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.canis.hejje.AppContainer
import com.canis.hejje.R
import com.canis.hejje.ui.components.HejjeIcons
import com.canis.hejje.ui.components.LocalContainer
import com.canis.hejje.ui.components.LocalUndo
import com.canis.hejje.ui.components.UndoHost
import com.canis.hejje.ui.detail.TripDetailScreen
import com.canis.hejje.ui.insights.InsightsScreen
import com.canis.hejje.ui.onboarding.OnboardingScreen
import com.canis.hejje.ui.settings.AboutScreen
import com.canis.hejje.ui.settings.CarScreen
import com.canis.hejje.ui.settings.HealthScreen
import com.canis.hejje.ui.settings.ParkedScreen
import com.canis.hejje.ui.settings.SettingsScreen
import com.canis.hejje.ui.settings.ZonesScreen
import com.canis.hejje.ui.theme.HejjeTheme
import com.canis.hejje.ui.theme.LocalPalette
import com.canis.hejje.ui.theme.LocalReduceMotion
import com.canis.hejje.ui.trips.TripsScreen
import kotlinx.coroutines.delay

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Creates a ViewModel with access to the app's dependencies. */
@Composable
inline fun <reified VM : ViewModel> hejjeViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalContainer.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

object Routes {
    const val TRIPS = "trips"
    const val INSIGHTS = "insights"
    const val SETTINGS = "settings"
    const val PARKED = "parked"
    const val HEALTH = "health"
    const val ZONES = "zones"
    const val CAR = "car"
    const val ABOUT = "about"
    fun trip(id: Long) = "trip/$id"
    val TOP = setOf(TRIPS, INSIGHTS, SETTINGS)
}

/**
 * @param openTripId a trip to open straight away (from the summary notification)
 */
@Composable
fun HejjeRoot(container: AppContainer, openTripId: Long?, onTripOpened: () -> Unit) {
    val settings by container.settings.data.collectAsStateWithLifecycle()
    HejjeTheme(settings.theme) {
        CompositionLocalProvider(LocalContainer provides container) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box {
                    if (settings.onboardingDone) MainScaffold(container, openTripId, onTripOpened) else OnboardingScreen()
                    BrandSplash()
                }
            }
        }
    }
}

/** The wordmark with the icon, shown for a moment as the system splash hands over, then faded out. */
@Composable
private fun BrandSplash() {
    var shown by rememberSaveable { mutableStateOf(false) }
    var visible by remember { mutableStateOf(!shown) }
    val reduce = LocalReduceMotion.current
    LaunchedEffect(Unit) {
        if (!shown) {
            delay(if (reduce) 250 else 650)
            visible = false
            shown = true
        }
    }
    AnimatedVisibility(visible, enter = fadeIn(tween(0)), exit = fadeOut(tween(260))) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            // Same drawable and size as the system splash, so the icon does not move.
            Image(painterResource(R.drawable.ic_splash), null, Modifier.size(288.dp))
            Text(
                stringResource(R.string.brand_name), style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.offset(y = 84.dp),
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MainScaffold(container: AppContainer, openTripId: Long?, onTripOpened: () -> Unit) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.action_undo)
    val undoHost = remember(undoLabel) { UndoHost(snackbar, scope, container.trips, undoLabel) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val live by container.controller.live.collectAsStateWithLifecycle()

    LaunchedEffect(openTripId) {
        if (openTripId != null) {
            nav.navigate(Routes.trip(openTripId)) { launchSingleTop = true }
            onTripOpened()
        }
    }
    // Quiet update check on start, at most once a day; failures are ignored.
    LaunchedEffect(Unit) { runCatching { container.updater.check(manual = false) } }

    CompositionLocalProvider(LocalUndo provides undoHost) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            // Without the bottom bar the snackbar would sit under the gesture area.
            snackbarHost = { SnackbarHost(snackbar, if (route == null || route in Routes.TOP) Modifier else Modifier.navigationBarsPadding()) },
            bottomBar = {
                AnimatedVisibility(route == null || route in Routes.TOP, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    BottomBar(nav, route)
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                SharedTransitionLayout {
                    CompositionLocalProvider(LocalSharedScope provides this) {
                        NavHost(
                            nav, startDestination = Routes.TRIPS,
                            enterTransition = { fadeIn(tween(220)) }, exitTransition = { fadeOut(tween(160)) },
                            popEnterTransition = { fadeIn(tween(220)) }, popExitTransition = { fadeOut(tween(160)) },
                        ) {
                            composable(Routes.TRIPS) {
                                CompositionLocalProvider(LocalNavScope provides this) {
                                    TripsScreen(onTrip = { nav.navigate(Routes.trip(it)) }, onParked = { nav.navigate(Routes.PARKED) })
                                }
                            }
                            composable(Routes.INSIGHTS) {
                                CompositionLocalProvider(LocalNavScope provides this) { InsightsScreen(onTrip = { nav.navigate(Routes.trip(it)) }) }
                            }
                            composable(Routes.SETTINGS) { SettingsScreen(onOpen = { nav.navigate(it) }) }
                            composable("trip/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { back ->
                                CompositionLocalProvider(LocalNavScope provides this) {
                                    TripDetailScreen(back.arguments?.getLong("id") ?: -1L, onBack = { nav.popBackStack() })
                                }
                            }
                            composable(Routes.PARKED) { ParkedScreen(onBack = { nav.popBackStack() }) }
                            composable(Routes.HEALTH) { HealthScreen(onBack = { nav.popBackStack() }, onCar = { nav.navigate(Routes.CAR) }) }
                            composable(Routes.ZONES) { ZonesScreen(onBack = { nav.popBackStack() }) }
                            composable(Routes.CAR) { CarScreen(onBack = { nav.popBackStack() }) }
                            composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
                        }
                    }
                }
                // Thin accent bar across the top while a drive is being recorded.
                AnimatedVisibility(live != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.statusBarsPadding()) {
                    Box(Modifier.fillMaxWidth().height(3.dp).background(LocalPalette.current.accent))
                }
            }
        }
    }
}

@Composable
private fun BottomBar(nav: NavHostController, route: String?) {
    val items = listOf(
        Triple(Routes.TRIPS, HejjeIcons.Trips, R.string.tab_trips),
        Triple(Routes.INSIGHTS, HejjeIcons.Insights, R.string.tab_insights),
        Triple(Routes.SETTINGS, HejjeIcons.Settings, R.string.tab_settings),
    )
    Box {
        NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
            items.forEach { (target, icon, label) ->
                NavigationBarItem(
                    selected = route == target,
                    onClick = {
                        if (route != target) nav.navigate(target) {
                            popUpTo(Routes.TRIPS) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(icon, null) },
                    label = { Text(stringResource(label)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
