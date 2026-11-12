package com.canis.hejje.ui.trips

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.canis.hejje.R
import com.canis.hejje.data.SettingsData
import com.canis.hejje.data.db.TripEntity
import com.canis.hejje.data.db.TripWithTags
import com.canis.hejje.domain.Formatter
import com.canis.hejje.domain.Totals
import com.canis.hejje.recording.LiveState
import com.canis.hejje.ui.LocalNavScope
import com.canis.hejje.ui.LocalSharedScope
import com.canis.hejje.ui.components.EmptyState
import com.canis.hejje.ui.components.HejjeCard
import com.canis.hejje.ui.components.HejjeIcons
import com.canis.hejje.ui.components.HejjeTopBar
import com.canis.hejje.ui.components.LocalContainer
import com.canis.hejje.ui.components.LocalUndo
import com.canis.hejje.ui.components.PulsingDot
import com.canis.hejje.ui.components.RouteThumb
import com.canis.hejje.ui.components.ScreenPadding
import com.canis.hejje.ui.components.rememberHaptics
import com.canis.hejje.ui.hejjeViewModel
import com.canis.hejje.ui.rememberFormatter
import com.canis.hejje.ui.theme.LocalPalette
import com.canis.hejje.update.UpdateError
import com.canis.hejje.update.UpdateState
import kotlinx.coroutines.delay
import java.time.LocalDate

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TripsScreen(onTrip: (Long) -> Unit, onParked: () -> Unit) {
    val container = LocalContainer.current
    val vm = hejjeViewModel { TripsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val update by container.updater.state.collectAsStateWithLifecycle()
    val f = rememberFormatter(state.settings.miles)
    val haptics = rememberHaptics()
    val undo = LocalUndo.current
    val context = LocalContext.current
    var searching by rememberSaveable { mutableStateOf(false) }
    val needPermission = stringResource(R.string.trips_need_location)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) vm.startRecording() else undo.show(needPermission)
    }
    val start = {
        haptics.confirm()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            vm.startRecording()
        } else {
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    Column(Modifier.fillMaxSize()) {
        HejjeTopBar(
            title = stringResource(R.string.brand_name),
            actions = {
                if (state.live != null) PulsingDot(Modifier.padding(end = 4.dp))
                IconButton(onClick = { searching = !searching; if (!searching) vm.search("") }) {
                    Icon(if (searching) HejjeIcons.Close else HejjeIcons.Search, stringResource(if (searching) R.string.action_close_search else R.string.action_search))
                }
                if (state.live == null) {
                    IconButton(onClick = start) { Icon(HejjeIcons.Record, stringResource(R.string.action_start_recording)) }
                }
            },
        )
        AnimatedVisibility(searching) {
            OutlinedTextField(
                value = state.query, onValueChange = vm::search, singleLine = true,
                placeholder = { Text(stringResource(R.string.trips_search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding).padding(bottom = 8.dp),
            )
        }
        val today = LocalDate.now()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "update") {
                UpdateCard(
                    update, Modifier.padding(horizontal = ScreenPadding).padding(bottom = 12.dp),
                    onUpdate = { haptics.confirm(); container.updater.download(it) },
                    onSkip = container.updater::skip, onDismiss = container.updater::dismiss,
                )
            }
            item(key = "summary") {
                SummaryCard(
                    state.week, state.settings, state.live, f, Modifier.padding(horizontal = ScreenPadding),
                    onParked = onParked, onStop = { haptics.confirm(); vm.stopRecording() },
                )
            }
            if (state.tags.isNotEmpty()) {
                item(key = "tags") {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = ScreenPadding).padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.tags.forEach { tag ->
                            FilterChip(
                                selected = tag == state.selectedTag, onClick = { haptics.tick(); vm.selectTag(tag) }, label = { Text(tag) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                    }
                }
            }
            if (!state.loading && state.groups.isEmpty()) {
                item(key = "empty") {
                    EmptyState(stringResource(if (state.totalTrips == 0) R.string.trips_empty else R.string.trips_no_match))
                }
            }
            state.groups.forEach { group ->
                stickyHeader(key = "day-${group.day}") {
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                            .padding(horizontal = ScreenPadding).padding(top = 20.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            f.dayLabel(group.day, today), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() },
                        )
                        Text(f.distance(group.distanceM), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(group.trips, key = { it.trip.id }) { item ->
                    TripCard(item, f, Modifier.padding(horizontal = ScreenPadding, vertical = 5.dp).animateItem()) { onTrip(item.trip.id) }
                }
            }
        }
    }
}

fun tripTitle(trip: TripEntity, f: Formatter): String {
    val from = trip.startPlace ?: f.coordinates(trip.startLat, trip.startLon)
    val to = trip.endPlace ?: f.coordinates(trip.endLat, trip.endLon)
    return if (from == to) from else "$from → $to"
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TripCard(item: TripWithTags, f: Formatter, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val trip = item.trip
    val shared = LocalSharedScope.current
    val navScope = LocalNavScope.current
    val title = tripTitle(trip, f)
    val time = "${f.time(trip.startTime)} – ${f.time(trip.endTime)}"
    val stats = listOf(f.distance(trip.distanceM), f.duration(trip.totalMs), f.speed(trip.avgMovingSpeed)).joinToString("  ·  ")
    val bounds = if (shared != null && navScope != null) {
        with(shared) { Modifier.sharedBounds(rememberSharedContentState("trip-${trip.id}"), navScope) }
    } else Modifier
    HejjeCard(modifier.then(bounds), onClick = onClick, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RouteThumb(trip.thumb, Modifier.size(60.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(time, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(stats, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                if (item.tags.isNotEmpty()) {
                    Text(
                        item.tags.joinToString("  ") { it.name }, style = MaterialTheme.typography.labelMedium,
                        color = LocalPalette.current.accentText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** Top card: what is happening now (recording or parked car) plus this week's totals. */
@Composable
private fun SummaryCard(
    week: Totals, settings: SettingsData, live: LiveState?, f: Formatter, modifier: Modifier,
    onParked: () -> Unit, onStop: () -> Unit,
) {
    HejjeCard(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.this_week), style = MaterialTheme.typography.labelMedium, color = LocalPalette.current.accentText)
        Spacer(Modifier.height(2.dp))
        Text(f.distance(week.distanceM), style = MaterialTheme.typography.displaySmall)
        Text(
            pluralStringResource(R.plurals.trip_count, week.trips, week.trips) +
                if (week.trips > 0) "  ·  " + f.duration(week.movingMs) else "",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (live != null) {
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(1_000)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot()
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.recording_now), style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (live.hasFix) "${f.duration(now - live.startedAt)}  ·  ${f.distance(live.distanceM)}" else stringResource(R.string.notif_waiting_gps),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onStop) { Text(stringResource(R.string.action_stop), color = LocalPalette.current.accentText) }
            }
        } else if (settings.parkedLat != null && settings.parkedLon != null) {
            HorizontalDivider(Modifier.padding(top = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
            val description = stringResource(R.string.parked_open)
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).semantics { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HejjeIcons.Pin, null, tint = LocalPalette.current.accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(stringResource(R.string.parked_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        f.relative(settings.parkedAt, System.currentTimeMillis()), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onParked) { Text(stringResource(R.string.parked_show), color = LocalPalette.current.accentText) }
            }
        }
    }
}

/** Clean, dismissable card that appears when a newer release exists. */
@Composable
fun UpdateCard(
    state: UpdateState, modifier: Modifier = Modifier,
    onUpdate: (com.canis.hejje.update.UpdateInfo) -> Unit, onSkip: (com.canis.hejje.update.UpdateInfo) -> Unit, onDismiss: () -> Unit,
) {
    val info = when (state) {
        is UpdateState.Available -> state.info
        is UpdateState.Downloading -> state.info
        is UpdateState.Installing -> state.info
        is UpdateState.Failed -> state.info
        else -> null
    }
    AnimatedVisibility(info != null) {
        if (info == null) return@AnimatedVisibility
        HejjeCard(modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(HejjeIcons.Download, null, tint = LocalPalette.current.accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.update_available, info.version), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (state is UpdateState.Available || state is UpdateState.Failed) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(HejjeIcons.Close, stringResource(R.string.action_dismiss), Modifier.size(18.dp))
                    }
                }
            }
            if (info.notes.isNotBlank()) {
                Text(
                    info.notes, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
                )
            }
            when (state) {
                is UpdateState.Downloading -> {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { state.progress }, modifier = Modifier.fillMaxWidth(),
                        color = LocalPalette.current.accent, trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    Text(
                        stringResource(R.string.update_downloading, (state.progress * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                is UpdateState.Installing -> Text(
                    stringResource(R.string.update_confirm_hint), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
                )
                else -> {
                    if (state is UpdateState.Failed) {
                        Text(
                            stringResource(updateErrorText(state.error)), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onSkip(info) }) { Text(stringResource(R.string.update_skip), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = { onUpdate(info) }) {
                            Text(stringResource(if (state is UpdateState.Failed) R.string.action_retry else R.string.update_now), color = LocalPalette.current.accentText)
                        }
                    }
                }
            }
        }
    }
}

fun updateErrorText(error: UpdateError): Int = when (error) {
    UpdateError.NETWORK -> R.string.update_error_network
    UpdateError.WIFI_ONLY -> R.string.update_error_wifi
    UpdateError.VERIFY -> R.string.update_error_verify
    UpdateError.INSTALL -> R.string.update_error_install
}

