package com.canis.hejje.ui

import androidx.activity.BackEventCompat
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.canis.hejje.AppContainer
import com.canis.hejje.R
import com.canis.hejje.data.toPlace
import com.canis.hejje.domain.NamedPlace
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
import com.canis.hejje.ui.stretch.SectionScreen
import com.canis.hejje.ui.stretch.StretchScreen
import com.canis.hejje.ui.theme.HejjeTheme
import com.canis.hejje.ui.theme.LocalPalette
import com.canis.hejje.ui.theme.LocalReduceMotion
import com.canis.hejje.ui.theme.WordmarkFont
import com.canis.hejje.ui.trips.TripsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map


/** Creates a ViewModel with access to the app's dependencies. */
@Composable
inline fun <reified VM : ViewModel> hejjeViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalContainer.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

/**
 * Opening a screen: Material shared-axis motion (slides in a short way from the right while
 * fading). Switching bottom tabs: a plain fade-through, with no sideways movement.
 */
private object NavMotion {
    private const val DURATION = 300
    private val easing = CubicBezierEasing(0.2f, 0f, 0f, 1f) // Material "emphasized"
    private fun <T> slide() = tween<T>(DURATION, easing = easing)
    private fun shift(full: Int) = full / 10

    val forwardEnter: EnterTransition = slideInHorizontally(slide()) { shift(it) } + fadeIn(tween(210, delayMillis = 90))
    val forwardExit: ExitTransition = slideOutHorizontally(slide()) { -shift(it) } + fadeOut(tween(90))
    val tabEnter: EnterTransition = fadeIn(tween(210, delayMillis = 90))
    val tabExit: ExitTransition = fadeOut(tween(90))
}

/**
 * Going back looks like Android's own back animation between apps' screens, with the values
 * Android itself uses: as the finger moves, the screen shrinks to 90% with rounded corners, over
 * the previous screen waiting 96 dp to the left under a dim scrim; on release the screen slides
 * away and fades while the previous one comes forward (Material "emphasized", 450 ms). The back
 * button and the app's back arrow play the same animation.
 */
private class BackMotion(private val shiftPx: Int, private val reduceMotion: Boolean) {
    /** True while the current navigation is a step back (not a tab switch). Read while drawing. */
    var active = false

    private val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** The screen being closed. A swipe from the left edge shrinks it towards the right, as Android does. */
    private fun closing(origin: TransformOrigin): ExitTransition =
        if (reduceMotion) fadeOut(tween(150))
        else scaleOut(keyframes { durationMillis = TOTAL_MS; SCALE at SHRINK_MS }, targetScale = SCALE, transformOrigin = origin) +
            slideOutHorizontally(keyframes { durationMillis = TOTAL_MS; IntOffset.Zero at SHRINK_MS using emphasized }) { shiftPx } +
            fadeOut(keyframes { durationMillis = TOTAL_MS; 1f at SHRINK_MS; 0f at SHRINK_MS + COMMIT_MS / 5 })

    private val exitCentred = closing(TransformOrigin.Center)
    private val exitFromLeft = closing(TransformOrigin(1f, 0.5f))

    fun exit(swipeEdge: Int? = null): ExitTransition = if (swipeEdge == BackEventCompat.EDGE_LEFT) exitFromLeft else exitCentred

    /** The screen coming back: waits 96 dp to the left, shrinking with the other one, then comes forward. */
    val enter: EnterTransition =
        if (reduceMotion) fadeIn(tween(150))
        else slideInHorizontally(
            keyframes { durationMillis = TOTAL_MS; IntOffset(-shiftPx, 0) at SHRINK_MS using emphasized },
        ) { -shiftPx } + scaleIn(keyframes { durationMillis = TOTAL_MS; SCALE at SHRINK_MS using emphasized }, initialScale = 0.999f)

    companion object {
        /** The part the finger drives (the shrink), then Android's 450 ms finish. */
        const val SHRINK_MS = 180
        const val COMMIT_MS = 450
        const val TOTAL_MS = SHRINK_MS + COMMIT_MS
        const val SHRINK_SHARE = SHRINK_MS.toFloat() / TOTAL_MS
        const val SCALE = 0.9f
        val SHIFT = 96.dp
        val CORNER = 32.dp
        const val SCRIM_LIGHT = 0.2f
        const val SCRIM_DARK = 0.8f
    }
}

private val LocalBackMotion = staticCompositionLocalOf<BackMotion?> { null }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean =
    initialState.destination.route in Routes.TOP && targetState.destination.route in Routes.TOP

/** Places the user named, for trip titles anywhere in the app. */
val LocalPlaces = staticCompositionLocalOf<List<NamedPlace>> { emptyList() }

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
    fun stretch(fromTrip: Long? = null) = if (fromTrip != null) "stretch?trip=$fromTrip" else "stretch"
    fun section(tripId: Long, from: Long, to: Long) = "section/$tripId/$from/$to"
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
                stringResource(R.string.brand_name),
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = WordmarkFont, fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.offset(y = 84.dp),
            )
        }
    }
}

/**
 * Every screen's base. During a back step it also draws Android's back look: rounded corners on
 * the screen being closed, and the dim scrim over the screen coming back.
 */
@Composable
private fun AnimatedContentScope.ScreenBackground(content: @Composable () -> Unit) {
    val back = LocalBackMotion.current
    val progress by transition.animateFloat(
        transitionSpec = { tween(BackMotion.TOTAL_MS, easing = LinearEasing) }, label = "back",
    ) { if (it == EnterExitState.Visible) 0f else 1f }
    val scrim = if (LocalPalette.current.isDark) BackMotion.SCRIM_DARK else BackMotion.SCRIM_LIGHT
    val corner = with(LocalDensity.current) { BackMotion.CORNER.toPx() }
    val t = transition
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                val closing = back?.active == true && t.targetState == EnterExitState.PostExit
                val r = if (closing) corner * (progress / BackMotion.SHRINK_SHARE).coerceIn(0f, 1f) else 0f
                clip = r > 0f
                shape = RoundedCornerShape(r)
            }
            .drawWithContent {
                drawContent()
                val entering = back?.active == true && t.targetState == EnterExitState.Visible && t.currentState == EnterExitState.PreEnter
                if (entering) {
                    val done = 1f - progress
                    val alpha = if (done <= BackMotion.SHRINK_SHARE) scrim else scrim * (1f - (done - BackMotion.SHRINK_SHARE) / (1f - BackMotion.SHRINK_SHARE))
                    // Like Android's scrim, it also covers the space around this (slightly shrunk) screen.
                    if (alpha > 0f) {
                        drawRect(
                            Color.Black.copy(alpha = alpha.coerceIn(0f, 1f)),
                            topLeft = Offset(-size.width, -size.height), size = Size(size.width * 3, size.height * 3),
                        )
                    }
                }
            }
            .background(MaterialTheme.colorScheme.background),
    ) { content() }
}

@Composable
private fun MainScaffold(container: AppContainer, openTripId: Long?, onTripOpened: () -> Unit) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.action_undo)
    val undoHost = remember(undoLabel) { UndoHost(snackbar, scope, container.trips, undoLabel) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    // Only "recording or not" matters here; the live distance would otherwise recompose the whole app every second.
    val recordingFlow = remember { container.controller.live.map { it != null }.distinctUntilChanged() }
    val recording by recordingFlow.collectAsStateWithLifecycle(initialValue = false)

    LaunchedEffect(openTripId) {
        if (openTripId != null) {
            nav.navigate(Routes.trip(openTripId)) { launchSingleTop = true }
            onTripOpened()
        }
    }
    // Quiet update check on start, at most once a day; failures are ignored.
    LaunchedEffect(Unit) { runCatching { container.updater.check(manual = false) } }

    val placesFlow = remember { container.db.zones().observe().map { list -> list.map { it.toPlace() } } }
    val places by placesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val density = LocalDensity.current
    val reduceMotion = LocalReduceMotion.current
    val back = remember(density, reduceMotion) { BackMotion(with(density) { BackMotion.SHIFT.roundToPx() }, reduceMotion) }
    CompositionLocalProvider(LocalUndo provides undoHost, LocalPlaces provides places, LocalBackMotion provides back) {
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
                NavHost(
                    nav, startDestination = Routes.TRIPS,
                    enterTransition = { back.active = false; if (isTabSwitch()) NavMotion.tabEnter else NavMotion.forwardEnter },
                    exitTransition = { back.active = false; if (isTabSwitch()) NavMotion.tabExit else NavMotion.forwardExit },
                    popEnterTransition = { back.active = !isTabSwitch(); if (isTabSwitch()) NavMotion.tabEnter else back.enter },
                    popExitTransition = { back.active = !isTabSwitch(); if (isTabSwitch()) NavMotion.tabExit else back.exit() },
                    // The back swipe (predictive back) follows the finger with the same animation.
                    predictivePopEnterTransition = { back.active = !isTabSwitch(); if (isTabSwitch()) NavMotion.tabEnter else back.enter },
                    predictivePopExitTransition = { edge -> back.active = !isTabSwitch(); if (isTabSwitch()) NavMotion.tabExit else back.exit(edge) },
                ) {
                    composable(Routes.TRIPS) {
                        ScreenBackground { TripsScreen(onTrip = { nav.navigate(Routes.trip(it)) }, onParked = { nav.navigate(Routes.PARKED) }) }
                    }
                    composable(Routes.INSIGHTS) {
                        ScreenBackground { InsightsScreen(onTrip = { nav.navigate(Routes.trip(it)) }, onStretch = { nav.navigate(Routes.stretch()) }) }
                    }
                    composable(Routes.SETTINGS) { ScreenBackground { SettingsScreen(onOpen = { nav.navigate(it) }) } }
                    composable("trip/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { back ->
                        val id = back.arguments?.getLong("id") ?: -1L
                        ScreenBackground { TripDetailScreen(id, onBack = { nav.popBackStack() }, onStretch = { nav.navigate(Routes.stretch(id)) }) }
                    }
                    composable(
                        "stretch?trip={trip}",
                        arguments = listOf(navArgument("trip") { type = NavType.LongType; defaultValue = -1L }),
                    ) { back ->
                        val from = back.arguments?.getLong("trip")?.takeIf { it > 0 }
                        ScreenBackground {
                            StretchScreen(from, onBack = { nav.popBackStack() }, onSection = { id, a, b -> nav.navigate(Routes.section(id, a, b)) })
                        }
                    }
                    composable(
                        "section/{trip}/{from}/{to}",
                        arguments = listOf(
                            navArgument("trip") { type = NavType.LongType },
                            navArgument("from") { type = NavType.LongType },
                            navArgument("to") { type = NavType.LongType },
                        ),
                    ) { back ->
                        val args = back.arguments
                        ScreenBackground {
                            SectionScreen(
                                args?.getLong("trip") ?: -1L, args?.getLong("from") ?: 0L, args?.getLong("to") ?: 0L,
                                onBack = { nav.popBackStack() }, onTrip = { nav.navigate(Routes.trip(it)) },
                            )
                        }
                    }
                    composable(Routes.PARKED) { ScreenBackground { ParkedScreen(onBack = { nav.popBackStack() }) } }
                    composable(Routes.HEALTH) { ScreenBackground { HealthScreen(onBack = { nav.popBackStack() }, onCar = { nav.navigate(Routes.CAR) }, onTrip = { nav.navigate(Routes.trip(it)) }) } }
                    composable(Routes.ZONES) { ScreenBackground { ZonesScreen(onBack = { nav.popBackStack() }) } }
                    composable(Routes.CAR) { ScreenBackground { CarScreen(onBack = { nav.popBackStack() }) } }
                    composable(Routes.ABOUT) { ScreenBackground { AboutScreen(onBack = { nav.popBackStack() }) } }
                }
                // Thin accent bar across the top while a drive is being recorded.
                AnimatedVisibility(recording, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.statusBarsPadding()) {
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
