package com.canis.hejje.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.canis.hejje.AppContainer
import com.canis.hejje.data.SettingsData
import com.canis.hejje.data.db.TripWithTags
import com.canis.hejje.domain.Insights
import com.canis.hejje.domain.Totals
import com.canis.hejje.recording.LiveState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

data class DayGroup(val day: LocalDate, val trips: List<TripWithTags>) {
    val distanceM: Double get() = trips.sumOf { it.trip.distanceM }
}

data class TripsState(
    val loading: Boolean = true,
    val groups: List<DayGroup> = emptyList(),
    val totalTrips: Int = 0,
    val tags: List<String> = emptyList(),
    val selectedTag: String? = null,
    val query: String = "",
    val week: Totals = Totals(),
    val settings: SettingsData = SettingsData(),
)

class TripsViewModel(private val container: AppContainer) : ViewModel() {
    private data class Filter(val tag: String? = null, val query: String = "")

    private val filter = MutableStateFlow(Filter())

    val live: StateFlow<LiveState?> = container.controller.live

    /** Today's date, which changes at midnight, so the week total and "Today" move on while the app is open. */
    private val today = flow {
        while (true) {
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            emit(now.toLocalDate())
            delay(Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(zone)).toMillis() + 1_000)
        }
    }.distinctUntilChanged()

    val state: StateFlow<TripsState> = combine(container.trips.trips, container.settings.data, filter, today) { trips, settings, f, day ->
        val zone = ZoneId.systemDefault()
        val monday = Insights.weekStart(day)
        val week = trips.filter { Insights.dayOf(it.trip.startTime, zone) >= monday }
        val q = f.query.trim().lowercase()
        val shown = trips.filter { t ->
            (f.tag == null || t.tags.any { it.name == f.tag }) && (
                q.isEmpty() || listOfNotNull(t.trip.startPlace, t.trip.endPlace, t.trip.note).any { it.lowercase().contains(q) } ||
                    t.tags.any { it.name.lowercase().contains(q) }
                )
        }
        TripsState(
            loading = false,
            groups = shown.groupBy { Insights.dayOf(it.trip.startTime, zone) }.map { DayGroup(it.key, it.value) }.sortedByDescending { it.day },
            totalTrips = trips.size,
            // Only tags that are in use are offered as filters.
            tags = trips.flatMap { t -> t.tags.map { it.name } }.distinct().sorted(),
            selectedTag = f.tag,
            query = f.query,
            week = Totals(week.size, week.sumOf { it.trip.distanceM }, week.sumOf { it.trip.movingMs }, week.sumOf { it.trip.totalMs }),
            settings = settings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripsState())

    fun selectTag(tag: String?) = filter.update { it.copy(tag = if (it.tag == tag) null else tag) }

    fun search(query: String) = filter.update { it.copy(query = query) }

    fun startRecording() = container.controller.manualStart()

    fun stopRecording() = container.controller.manualStop()
}
