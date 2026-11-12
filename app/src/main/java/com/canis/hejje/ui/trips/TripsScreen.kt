package com.canis.hejje.ui.trips

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import java.time.DayOfWeek
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Surface
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import com.canis.hejje.ui.components.CardShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.canis.hejje.R
import com.canis.hejje.data.SettingsData
import com.canis.hejje.data.db.TripEntity
import com.canis.hejje.data.db.TripWithTags
import com.canis.hejje.domain.Formatter
import com.canis.hejje.domain.Totals
import com.canis.hejje.recording.LiveState
import com.canis.hejje.ui.components.EmptyState
import com.canis.hejje.ui.components.HejjeCard
import com.canis.hejje.ui.components.HejjeIcons
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
    // Collected separately: it changes with every GPS fix and must not redraw the whole list.
    val live by vm.live.collectAsStateWithLifecycle()
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
        // Actions only; the wordmark sits next to the summary card below.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(start = ScreenPadding, end = ScreenPadding - 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            run {
                if (live != null) PulsingDot(Modifier.padding(end = 4.dp))
                IconButton(onClick = { searching = !searching; if (!searching) vm.search("") }) {
                    Icon(if (searching) HejjeIcons.Close else HejjeIcons.Search, stringResource(if (searching) R.string.action_close_search else R.string.action_search))
                }
                if (live == null) {
                    IconButton(onClick = start) { Icon(HejjeIcons.Record, stringResource(R.string.action_start_recording)) }
                }
            }
        }
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
                    update, Modifier.padding(horizontal = ScreenPadding).padding(bottom = 16.dp),
                    onUpdate = { haptics.confirm(); container.updater.download(it) },
                    onSkip = container.updater::skip, onDismiss = container.updater::dismiss,
                )
            }
            item(key = "summary") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Shrinks to fit with very large font sizes instead of being cut off.
                    // A lighter weight at about 80% of its half keeps the brand from outshouting the numbers.
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        BasicText(
                            stringResource(R.string.brand_name),
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground,
                            ),
                            maxLines = 1,
                            autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 80.sp, stepSize = 2.sp),
                            modifier = Modifier.fillMaxWidth(0.8f).semantics { heading() },
                        )
                    }
                    SummaryCard(
                        state.week, state.weekDays, state.todayIndex, state.settings, live, f, Modifier.weight(1f).atLeastSquare(),
                        onParked = onParked, onStop = { haptics.confirm(); vm.stopRecording() },
                    )
                }
            }
            if (state.tags.isNotEmpty()) {
                item(key = "tags") {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = ScreenPadding).padding(top = 16.dp),
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
                            .padding(horizontal = ScreenPadding).padding(top = 24.dp, bottom = 6.dp),
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
                    TripCard(item, f, Modifier.padding(horizontal = ScreenPadding, vertical = 6.dp).animateItem()) { onTrip(item.trip.id) }
                }
            }
        }
    }
}

/**
 * "Indiranagar → MG Road": when both ends are in the same city the city is left out, so most
 * titles fit on one line. Coordinates stand in until a place name is known.
 */
fun tripTitle(trip: TripEntity, f: Formatter): String {
    val from = trip.startPlace ?: f.coordinates(trip.startLat, trip.startLon)
    val to = trip.endPlace ?: f.coordinates(trip.endLat, trip.endLon)
    if (from == to) return from
    val a = trip.startPlace?.split(", ")
    val b = trip.endPlace?.split(", ")
    if (a != null && b != null && a.size > 1 && b.size > 1 && a.last() == b.last()) {
        val x = a.dropLast(1).joinToString(", ")
        val y = b.dropLast(1).joinToString(", ")
        if (x != y) return "$x → $y"
    }
    return "$from → $to"
}

@Composable
fun TripCard(item: TripWithTags, f: Formatter, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val trip = item.trip
    val title = tripTitle(trip, f)
    val time = "${f.time(trip.startTime)} – ${f.time(trip.endTime)}"
    val stats = listOf(f.distance(trip.distanceM), f.duration(trip.totalMs), f.speed(trip.avgMovingSpeed)).joinToString("  ·  ")
    HejjeCard(modifier, onClick = onClick, padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RouteThumb(trip.thumb, Modifier.size(64.dp))
            Spacer(Modifier.width(16.dp))
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

/** Square by default; grows taller only if the text needs more room (large font sizes). */
private fun Modifier.atLeastSquare(): Modifier = layout { measurable, constraints ->
    val side = constraints.maxWidth
    val placeable = measurable.measure(
        constraints.copy(minWidth = side, minHeight = side.coerceAtMost(constraints.maxHeight), maxHeight = Constraints.Infinity),
    )
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * Square card: this week's distance and trips, a bar per day, and at the bottom what is happening
 * now (recording, parked car, or a hint when there is neither). Every state uses the same bottom
 * row height, so the card never changes size when a drive starts or ends.
 */
@Composable
private fun SummaryCard(
    week: Totals, weekDays: List<Double>, todayIndex: Int, settings: SettingsData, live: LiveState?, f: Formatter,
    modifier: Modifier, onParked: () -> Unit, onStop: () -> Unit,
) {
    val palette = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    Surface(modifier = modifier, shape = CardShape, color = colors.surfaceContainerLow, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(verticalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                val trips = pluralStringResource(R.plurals.trip_count, week.trips, week.trips)
                val label = stringResource(R.string.this_week)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = palette.accentText)) { append(label) }
                        withStyle(SpanStyle(color = colors.onSurfaceVariant)) { append("  \u00b7  $trips") }
                    },
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    f.distance(week.distanceM),
                    style = MaterialTheme.typography.headlineMedium.copy(color = colors.onSurface),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 30.sp),
                    modifier = Modifier.padding(top = 2.dp),
                )
                WeekBars(weekDays, todayIndex, f, Modifier.fillMaxWidth().padding(top = 10.dp))
            }
            Column(Modifier.padding(bottom = 8.dp)) {
                HorizontalDivider(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), color = colors.outlineVariant)
                val row = Modifier.fillMaxWidth().heightIn(min = 40.dp)
                when {
                    live != null -> {
                        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                        LaunchedEffect(Unit) {
                            while (true) {
                                now = System.currentTimeMillis()
                                delay(1_000)
                            }
                        }
                        Row(row.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            PulsingDot(size = 7.dp)
                            Text(
                                if (live.hasFix) "${f.duration(now - live.startedAt)}  \u00b7  ${f.distance(live.distanceM)}" else stringResource(R.string.notif_waiting_gps),
                                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onStop, contentPadding = PaddingValues(horizontal = 10.dp)) {
                                Text(stringResource(R.string.action_stop), color = palette.accentText)
                            }
                        }
                    }
                    settings.parkedLat != null && settings.parkedLon != null -> {
                        val description = stringResource(R.string.parked_open)
                        Row(
                            row.clickable(onClick = onParked).semantics(mergeDescendants = true) { contentDescription = description }
                                .padding(start = 16.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(HejjeIcons.Pin, null, tint = palette.accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.summary_parked, f.relative(settings.parkedAt, System.currentTimeMillis())),
                                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            Icon(HejjeIcons.ChevronRight, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                    else -> Row(row.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(HejjeIcons.Car, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(if (settings.carAddress != null && settings.autoRecord) R.string.summary_idle_auto else R.string.summary_idle_setup),
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

/** Seven small rounded bars, Monday first, in accent shades; today is the strongest. */
@Composable
private fun WeekBars(days: List<Double>, todayIndex: Int, f: Formatter, modifier: Modifier) {
    val palette = LocalPalette.current
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val locale = LocalConfiguration.current.locales[0]
    val peak = (days.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val title = stringResource(R.string.summary_week_chart)
    val description = remember(days, locale) {
        title + ": " + days.indices.joinToString(", ") {
            DayOfWeek.of(it + 1).getDisplayName(java.time.format.TextStyle.SHORT, locale) + " " + f.distance(days[it])
        }
    }
    Column(modifier.clearAndSetSemantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            days.forEachIndexed { i, metres ->
                val color = when {
                    metres <= 0.0 || i > todayIndex -> track
                    i == todayIndex -> palette.accent
                    else -> palette.accentShade(0.55f)
                }
                val share = if (metres > 0 && i <= todayIndex) (metres / peak).toFloat() else 0f
                Spacer(
                    Modifier.width(BAR_WIDTH).fillMaxHeight().drawBehind {
                        // Days without driving show as a small dot on the baseline.
                        val h = maxOf(size.width, size.height * share)
                        drawRoundRect(color, Offset(0f, size.height - h), Size(size.width, h), CornerRadius(size.width / 2))
                    },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            DayOfWeek.entries.forEachIndexed { i, day ->
                Box(Modifier.width(BAR_WIDTH), contentAlignment = Alignment.Center) {
                    Text(
                        day.getDisplayName(java.time.format.TextStyle.NARROW, locale),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (i == todayIndex) palette.accentText else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, softWrap = false, modifier = Modifier.wrapContentWidth(unbounded = true),
                    )
                }
            }
        }
    }
}

private val BAR_WIDTH = 10.dp

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

