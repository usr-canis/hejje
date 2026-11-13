package com.canis.hejje.ui.stretch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.canis.hejje.AppContainer
import com.canis.hejje.R
import com.canis.hejje.data.SettingsData
import com.canis.hejje.data.db.TripEntity
import com.canis.hejje.domain.Insights
import com.canis.hejje.domain.PrivacyZones
import com.canis.hejje.domain.RouteShape
import com.canis.hejje.domain.StatsCalculator
import com.canis.hejje.domain.TripEditor
import com.canis.hejje.domain.TripStats
import com.canis.hejje.domain.Zone
import com.canis.hejje.ui.LocalPlaces
import com.canis.hejje.ui.components.HejjeTopBar
import com.canis.hejje.ui.components.ScreenPadding
import com.canis.hejje.ui.components.SectionTitle
import com.canis.hejje.ui.components.SpeedChart
import com.canis.hejje.ui.components.StatRow
import com.canis.hejje.ui.components.StatTile
import com.canis.hejje.ui.components.rememberHaptics
import com.canis.hejje.ui.detail.ChartSeries
import com.canis.hejje.ui.detail.StatsSections
import com.canis.hejje.ui.hejjeViewModel
import com.canis.hejje.ui.map.MapMarker
import com.canis.hejje.ui.map.MapRoute
import com.canis.hejje.ui.map.TripMap
import com.canis.hejje.ui.rememberFormatter
import com.canis.hejje.ui.trips.tripTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

data class SectionState(
    val loading: Boolean = true,
    val trip: TripEntity? = null,
    val stats: TripStats? = null,
    val baro: Boolean = false,
    val chart: ChartSeries = ChartSeries.EMPTY,
    val routes: List<MapRoute> = emptyList(),
    val markers: List<MapMarker> = emptyList(),
    val settings: SettingsData = SettingsData(),
)

/** The part of one trip between [from] and [to] (epoch ms), with the same stats as a whole trip. */
class SectionViewModel(container: AppContainer, tripId: Long, from: Long, to: Long) : ViewModel() {
    private val _state = MutableStateFlow(SectionState(settings = container.settings.current))
    val state: StateFlow<SectionState> = _state

    init {
        viewModelScope.launch {
            val trip = container.trips.trip(tripId)
            val segments = container.trips.segments(tripId)
            val settings = container.settings.current
            val hidden = if (settings.zonesInApp) {
                container.db.zones().all().filter { it.hide }.map { Zone(it.lat, it.lon, it.radiusM) }
            } else emptyList()
            val loaded = withContext(Dispatchers.Default) {
                val part = TripEditor.trim(segments, from, to)
                if (part.isEmpty()) return@withContext null
                val computed = StatsCalculator.compute(part)
                // Measured between the two chosen points, so always against the straight line.
                val shape = RouteShape.of(part.map { it.data.route.lat to it.data.route.lon }, computed.distanceM, roundTrips = false)
                val routes = part.flatMap { s -> PrivacyZones.trim(s.data.route, hidden).map(MapRoute::of) }.filter { it.size > 0 }
                val markers = buildList {
                    routes.firstOrNull()?.let { add(MapMarker(it.lat.first(), it.lon.first(), 0)) }
                    routes.lastOrNull()?.let { add(MapMarker(it.lat.last(), it.lon.last(), 1)) }
                }
                SectionState(
                    loading = false, trip = trip, stats = computed.copy(shape = shape), baro = part.any { it.data.baro },
                    chart = ChartSeries.of(part), routes = routes, markers = markers, settings = settings,
                )
            }
            _state.value = loaded ?: SectionState(loading = false, trip = trip, settings = settings)
        }
    }
}

@Composable
fun SectionScreen(tripId: Long, from: Long, to: Long, onBack: () -> Unit, onTrip: (Long) -> Unit) {
    val vm = hejjeViewModel(key = "section-$tripId-$from-$to") { SectionViewModel(it, tripId, from, to) }
    val state by vm.state.collectAsStateWithLifecycle()
    val f = rememberFormatter(state.settings.miles)
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val mapHeight = (windowHeight * 0.36f).coerceIn(220.dp, 380.dp)
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    val stats = state.stats
    val trip = state.trip

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        HejjeTopBar(stringResource(R.string.section_title), onBack = onBack)
        Box(Modifier.fillMaxWidth().height(mapHeight).clipToBounds()) {
            TripMap(
                state.routes, Modifier.fillMaxSize(), bySpeed = state.settings.routeBySpeed, markers = state.markers,
                highlight = state.chart.takeIf { selected in 0 until it.size }?.let { it.lat[selected] to it.lon[selected] },
                description = stringResource(R.string.section_map_description),
            )
            if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ScreenPadding, end = ScreenPadding, top = 20.dp,
                bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
        ) {
            if (stats == null || trip == null) {
                if (!state.loading) item(key = "missing") { Text(stringResource(R.string.section_missing), style = MaterialTheme.typography.bodyLarge) }
                return@LazyColumn
            }
            item(key = "head") {
                val zone = ZoneId.systemDefault()
                Text(
                    f.dayLabel(Insights.dayOf(stats.start, zone), LocalDate.now(zone)) + "  ·  " + f.time(stats.start) + " – " + f.time(stats.end),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.section_part_of, tripTitle(trip, f, LocalPlaces.current)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                StatRow {
                    StatTile(stringResource(R.string.stat_distance), f.distance(stats.distanceM), Modifier.weight(1f))
                    StatTile(stringResource(R.string.stat_duration), f.duration(stats.totalMs), Modifier.weight(1f))
                }
                StatRow {
                    StatTile(stringResource(R.string.stat_avg_speed), f.speed(stats.avgMovingSpeed), Modifier.weight(1f))
                    StatTile(stringResource(R.string.stat_max_speed), f.speed(stats.maxSpeed), Modifier.weight(1f))
                }
                OutlinedButton(onClick = { haptics.tick(); onTrip(trip.id) }, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.section_open_trip))
                }
            }
            item(key = "chart") {
                SectionTitle(stringResource(R.string.detail_speed_over_time))
                SpeedChart(
                    state.chart.speeds, selected, onSelect = { if (it != selected) haptics.tick(); selected = it }, formatter = f,
                    description = stringResource(R.string.detail_chart_description, f.speed(stats.avgMovingSpeed), f.speed(stats.maxSpeed)),
                )
                Text(
                    if (selected in 0 until state.chart.size) f.time(state.chart.times[selected]) else stringResource(R.string.detail_chart_hint),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }
            item(key = "stats") { StatsSections(stats, state.baro, f) }
        }
    }
}
