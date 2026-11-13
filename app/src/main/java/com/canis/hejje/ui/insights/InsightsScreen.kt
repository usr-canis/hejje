package com.canis.hejje.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.canis.hejje.AppContainer
import com.canis.hejje.R
import com.canis.hejje.data.SettingsData
import com.canis.hejje.data.db.TripWithTags
import com.canis.hejje.data.toPlace
import com.canis.hejje.domain.Formatter
import com.canis.hejje.domain.Insights
import com.canis.hejje.domain.InsightsData
import com.canis.hejje.domain.Places
import com.canis.hejje.domain.Totals
import com.canis.hejje.domain.TripSummary
import com.canis.hejje.ui.components.EmptyState
import com.canis.hejje.ui.components.Heatmap
import com.canis.hejje.ui.components.HejjeCard
import com.canis.hejje.ui.components.HejjeIcons
import com.canis.hejje.ui.components.HejjeTopBar
import com.canis.hejje.ui.components.ScreenPadding
import com.canis.hejje.ui.components.SectionTitle
import com.canis.hejje.ui.components.StatRow
import com.canis.hejje.ui.components.StatTile
import com.canis.hejje.ui.components.countUp
import com.canis.hejje.ui.components.rememberHaptics
import com.canis.hejje.ui.hejjeViewModel
import com.canis.hejje.ui.rememberFormatter
import com.canis.hejje.ui.theme.LocalPalette
import com.canis.hejje.ui.trips.TripCard
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

data class InsightsState(
    val loading: Boolean = true,
    val data: InsightsData? = null,
    val trips: List<TripWithTags> = emptyList(),
    val settings: SettingsData = SettingsData(),
    val today: LocalDate = LocalDate.now(),
)

class InsightsViewModel(container: AppContainer) : ViewModel() {
    val state: StateFlow<InsightsState> = combine(container.trips.trips, container.settings.data, container.db.zones().observe()) { trips, settings, zones ->
        val places = zones.map { it.toPlace() }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val summaries = trips.map { t ->
            TripSummary(t.trip.id, t.trip.startTime, t.trip.endTime, t.trip.distanceM, t.trip.movingMs, t.trip.maxSpeed,
                Places.placeAt(t.trip.endLat, t.trip.endLon, places)?.name ?: t.trip.endPlace, t.tags.map { it.name })
        }
        InsightsState(false, if (trips.isEmpty()) null else Insights.compute(summaries, today, zone), trips, settings, today)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsState())
}

@Composable
fun InsightsScreen(onTrip: (Long) -> Unit, onStretch: () -> Unit = {}) {
    val vm = hejjeViewModel { InsightsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val f = rememberFormatter(state.settings.miles)
    val haptics = rememberHaptics()
    var period by rememberSaveable { mutableIntStateOf(0) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    val data = state.data

    Column(Modifier.fillMaxSize()) {
        HejjeTopBar(stringResource(R.string.tab_insights))
        if (data == null) {
            if (!state.loading) EmptyState(stringResource(R.string.insights_empty))
            return@Column
        }
        val zone = ZoneId.systemDefault()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "totals") {
                Column(Modifier.padding(horizontal = ScreenPadding)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(R.string.this_week, R.string.this_month, R.string.all_time).forEachIndexed { i, label ->
                            FilterChip(
                                selected = period == i, onClick = { haptics.tick(); period = i }, label = { Text(stringResource(label)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    val totals = listOf(data.week, data.month, data.allTime)[period]
                    TotalsCard(totals, if (period == 0) data.lastWeek else null, f)
                }
            }
            item(key = "calendar") {
                SectionTitle(stringResource(R.string.insights_calendar), Modifier.padding(horizontal = ScreenPadding))
                val picked = selectedDay?.let(LocalDate::parse)
                val none = stringResource(R.string.insights_day_none)
                Heatmap(
                    data.daily, state.today, picked,
                    onDay = { haptics.tick(); selectedDay = if (selectedDay == it.toString()) null else it.toString() },
                    dayDescription = { day, distance -> f.dayLabel(day, state.today) + ", " + if (distance > 0) f.distance(distance) else none },
                )
                if (picked != null) {
                    val dayTrips = state.trips.filter { Insights.dayOf(it.trip.startTime, zone) == picked }
                    Column(Modifier.padding(horizontal = ScreenPadding).padding(top = 14.dp)) {
                        Text(
                            f.dayLabel(picked, state.today) + "  ·  " +
                                if (dayTrips.isEmpty()) none else f.distance(dayTrips.sumOf { it.trip.distanceM }),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        dayTrips.forEach { t -> TripCard(t, f, Modifier.padding(top = 10.dp)) { onTrip(t.trip.id) } }
                    }
                }
            }
            item(key = "stretch") {
                Column(Modifier.padding(horizontal = ScreenPadding)) {
                    SectionTitle(stringResource(R.string.stretch_title))
                    HejjeCard(Modifier.fillMaxWidth(), onClick = onStretch) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(HejjeIcons.Navigate, null, tint = LocalPalette.current.accent)
                            Text(
                                stringResource(R.string.stretch_entry_text), style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                            )
                            Icon(HejjeIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item(key = "records") {
                Column(Modifier.padding(horizontal = ScreenPadding)) {
                    SectionTitle(stringResource(R.string.insights_records))
                    StatRow {
                        StatTile(stringResource(R.string.insights_longest_trip), data.longest?.let { f.distance(it.distanceM) } ?: "–", Modifier.weight(1f))
                        StatTile(stringResource(R.string.insights_top_speed), data.topSpeed?.let { f.speed(it.maxSpeed) } ?: "–", Modifier.weight(1f))
                    }
                    StatRow {
                        StatTile(
                            stringResource(R.string.insights_current_streak),
                            pluralStringResource(R.plurals.day_count, data.currentStreak, data.currentStreak), Modifier.weight(1f),
                        )
                        StatTile(
                            stringResource(R.string.insights_longest_streak),
                            pluralStringResource(R.plurals.day_count, data.longestStreak, data.longestStreak), Modifier.weight(1f),
                        )
                    }
                }
            }
            if (data.places.isNotEmpty()) {
                item(key = "places") {
                    Column(Modifier.padding(horizontal = ScreenPadding)) {
                        SectionTitle(stringResource(R.string.insights_places))
                        val top = data.places.maxOf { it.second }
                        data.places.forEach { (name, count) ->
                            BarRow(name, pluralStringResource(R.plurals.trip_count, count, count), count.toFloat() / top)
                        }
                    }
                }
            }
            if (data.tags.isNotEmpty()) {
                item(key = "tags") {
                    Column(Modifier.padding(horizontal = ScreenPadding)) {
                        SectionTitle(stringResource(R.string.insights_tags))
                        val top = max(1.0, data.tags.maxOf { it.distanceM })
                        data.tags.forEach { tag ->
                            BarRow(
                                tag.name, pluralStringResource(R.plurals.trip_count, tag.trips, tag.trips) + "  ·  " + f.distance(tag.distanceM),
                                (tag.distanceM / top).toFloat(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalsCard(t: Totals, previous: Totals?, f: Formatter) {
    HejjeCard(Modifier.fillMaxWidth()) {
        Text(
            f.distanceValue(countUp(t.distanceM.toFloat()).toDouble()) + " " + f.distanceUnit,
            style = MaterialTheme.typography.displaySmall,
        )
        if (previous != null && previous.distanceM > 0) {
            val diff = t.distanceM - previous.distanceM
            Text(
                when {
                    kotlin.math.abs(diff) < 500 -> stringResource(R.string.insights_same_as_last_week)
                    diff > 0 -> stringResource(R.string.insights_more_than_last_week, f.distance(diff))
                    else -> stringResource(R.string.insights_less_than_last_week, f.distance(-diff))
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        StatRow {
            StatTile(stringResource(R.string.insights_trips), t.trips.toString(), Modifier.weight(1f), MaterialTheme.typography.titleMedium)
            StatTile(stringResource(R.string.stat_moving), f.duration(t.movingMs), Modifier.weight(1f), MaterialTheme.typography.titleMedium)
            StatTile(
                stringResource(R.string.insights_per_trip), f.distance(if (t.trips > 0) t.distanceM / t.trips else 0.0),
                Modifier.weight(1f), MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun BarRow(title: String, value: String, share: Float) {
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val color = LocalPalette.current.accentShade(0.3f + 0.7f * share)
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(
            Modifier.padding(top = 6.dp).fillMaxWidth().height(8.dp).drawBehind {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = r)
                drawRoundRect(color, size = Size(max(size.height, size.width * share.coerceIn(0f, 1f)), size.height), cornerRadius = r)
            },
        )
    }
}
