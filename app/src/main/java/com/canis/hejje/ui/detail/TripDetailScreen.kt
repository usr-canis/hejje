package com.canis.hejje.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.canis.hejje.R
import com.canis.hejje.domain.Formatter
import com.canis.hejje.domain.Insights
import com.canis.hejje.domain.PrivacyZones
import com.canis.hejje.domain.TripStats
import com.canis.hejje.ui.components.BandBars
import com.canis.hejje.ui.components.HejjeIcons
import com.canis.hejje.ui.components.LocalUndo
import com.canis.hejje.ui.components.ScreenPadding
import com.canis.hejje.ui.components.SectionTitle
import com.canis.hejje.ui.components.SpeedChart
import com.canis.hejje.ui.components.StatRow
import com.canis.hejje.ui.components.StatTile
import com.canis.hejje.ui.components.countUp
import com.canis.hejje.ui.components.rememberHaptics
import com.canis.hejje.ui.hejjeViewModel
import com.canis.hejje.ui.map.TripMap
import com.canis.hejje.ui.rememberFormatter
import com.canis.hejje.ui.share.ShareCard
import com.canis.hejje.ui.theme.LocalPalette
import com.canis.hejje.ui.trips.tripTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private enum class EditMode { TRIM, SPLIT }

/** Slightly longer than the 300 ms screen transition. */
private const val MAP_AFTER_TRANSITION_MS = 320L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TripDetailScreen(tripId: Long, onBack: () -> Unit) {
    val vm = hejjeViewModel(key = "trip-$tripId") { TripDetailViewModel(it, tripId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val f = rememberFormatter(state.settings.miles)
    val undo = LocalUndo.current
    val haptics = rememberHaptics()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current

    LaunchedEffect(state.missing) { if (state.missing) onBack() }

    var selected by rememberSaveable { mutableIntStateOf(-1) }
    var menu by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<EditMode?>(null) }
    var newTag by remember { mutableStateOf(false) }
    var editPoint by remember { mutableIntStateOf(-1) }

    val msgDeleted = stringResource(R.string.detail_deleted)
    val msgTrimmed = stringResource(R.string.detail_trimmed)
    val msgSplit = stringResource(R.string.detail_split_done)
    val msgMerged = stringResource(R.string.detail_merged)
    val msgFailed = stringResource(R.string.detail_edit_failed)
    val msgGpxSaved = stringResource(R.string.detail_gpx_saved)
    val shareTitle = stringResource(R.string.action_share)
    val labelDistance = stringResource(R.string.stat_distance)
    val labelDuration = stringResource(R.string.stat_duration)
    val labelAvg = stringResource(R.string.stat_avg_speed)
    val labelStops = stringResource(R.string.stat_stops)

    val gpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if (uri != null) vm.exportGpx(uri) { ok -> undo.show(if (ok) msgGpxSaved else msgFailed) }
    }

    // The map keeps a fixed height (about 40% of the screen) while the stats scroll below it.
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val mapHeight = (windowHeight * 0.4f).coerceIn(240.dp, 420.dp)

    val item = state.item
    val trip = item?.trip
    val title = trip?.let { tripTitle(it, f) }.orEmpty()
    val highlightIndex = if (edit != null) editPoint else selected
    val highlight = state.chart.takeIf { highlightIndex in 0 until it.size }?.let { it.lat[highlightIndex] to it.lon[highlightIndex] }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(mapHeight).clipToBounds().background(palette.mapBackground)) {
                // The map is heavy to create, so it waits until the screen has finished sliding in
                // (no stutter during the transition), then fades in and draws the route.
                var mapReady by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    if (!mapReady) {
                        delay(MAP_AFTER_TRANSITION_MS)
                        mapReady = true
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = mapReady && state.routes.isNotEmpty(),
                    enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)),
                    exit = androidx.compose.animation.ExitTransition.None,
                ) {
                    TripMap(
                        routes = state.routes, modifier = Modifier.fillMaxSize(),
                        bySpeed = state.settings.routeBySpeed, markers = state.markers, highlight = highlight,
                        animate = true, description = stringResource(R.string.detail_map_description, title),
                    )
                }
                Surface(
                    onClick = { haptics.tick(); vm.setRouteBySpeed(!state.settings.routeBySpeed) },
                    shape = CircleShape, color = colors.surfaceContainerLow.copy(alpha = 0.94f),
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                ) {
                    Text(
                        stringResource(if (state.settings.routeBySpeed) R.string.detail_colour_speed else R.string.detail_colour_plain),
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = ScreenPadding, end = ScreenPadding, top = 20.dp,
                    bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
            ) {
                if (trip == null) return@LazyColumn
                item(key = "head") {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(4.dp))
                    val zone = ZoneId.systemDefault()
                    Text(
                        f.dayLabel(Insights.dayOf(trip.startTime, zone), LocalDate.now(zone)) + "  ·  " +
                            f.time(trip.startTime) + " – " + f.time(trip.endTime),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    StatRow {
                        StatTile(labelDistance, f.distanceValue(countUp(trip.distanceM.toFloat()).toDouble()) + " " + f.distanceUnit, Modifier.weight(1f))
                        StatTile(labelDuration, f.duration(countUp(trip.totalMs.toFloat()).toLong()), Modifier.weight(1f))
                    }
                    StatRow {
                        StatTile(labelAvg, f.speed(countUp(trip.avgMovingSpeed)), Modifier.weight(1f))
                        StatTile(stringResource(R.string.stat_max_speed), f.speed(trip.maxSpeed), Modifier.weight(1f))
                    }
                }
                item(key = "tags") {
                    SectionTitle(stringResource(R.string.detail_tags))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.allTags.forEach { tag ->
                            val on = item.tags.any { it.id == tag.id }
                            FilterChip(
                                selected = on, onClick = { haptics.tick(); vm.setTag(tag.name, !on) }, label = { Text(tag.name) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colors.primaryContainer, selectedLabelColor = colors.onPrimaryContainer,
                                ),
                            )
                        }
                        FilterChip(
                            selected = false, onClick = { newTag = true }, label = { Text(stringResource(R.string.detail_new_tag)) },
                            leadingIcon = { Icon(HejjeIcons.Plus, null, Modifier.size(16.dp)) },
                        )
                    }
                }
                item(key = "note") {
                    var note by rememberSaveable(trip.id) { mutableStateOf(trip.note) }
                    LaunchedEffect(note) {
                        if (note != trip.note) {
                            delay(600)
                            vm.setNote(note)
                        }
                    }
                    OutlinedTextField(
                        value = note, onValueChange = { note = it }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        placeholder = { Text(stringResource(R.string.detail_note_hint)) }, minLines = 1, maxLines = 6,
                    )
                }
                val stats = state.stats
                if (stats != null) {
                    item(key = "chart") {
                        SectionTitle(stringResource(R.string.detail_speed_over_time))
                        SpeedChart(
                            state.chart.speeds, selected, onSelect = { if (it != selected) haptics.tick(); selected = it }, formatter = f,
                            description = stringResource(R.string.detail_chart_description, f.speed(trip.avgMovingSpeed), f.speed(trip.maxSpeed)),
                        )
                        Text(
                            if (selected in 0 until state.chart.size) f.time(state.chart.times[selected]) else stringResource(R.string.detail_chart_hint),
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    item(key = "stats") { StatsSections(stats, trip.baro, f) }
                    if (state.segments.size > 1) {
                        item(key = "segments") {
                            SectionTitle(stringResource(R.string.detail_segments))
                            state.segments.forEachIndexed { i, s ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${i + 1}.  ${f.time(s.start)} – ${f.time(s.end)}", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        f.distance(s.data.profile.dist.sum().toDouble()), style = MaterialTheme.typography.bodyLarge,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Floating buttons over the map.
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            OverlayButton(HejjeIcons.Back, stringResource(R.string.action_back), onBack)
            Row {
                OverlayButton(HejjeIcons.Share, shareTitle) {
                    val t = trip ?: return@OverlayButton
                    haptics.confirm()
                    scope.launch {
                        val zones = vm.zones()
                        val bitmap = withContext(Dispatchers.Default) {
                            ShareCard.render(
                                context, state.segments.flatMap { PrivacyZones.trim(it.data.route, zones) },
                                // Place names could reveal a hidden zone, so the card is titled by date.
                                title = Insights.dayOf(t.startTime, ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")),
                                subtitle = "${f.time(t.startTime)} – ${f.time(t.endTime)}",
                                stats = listOf(
                                    ShareCard.Stat(labelDistance, f.distance(t.distanceM)), ShareCard.Stat(labelDuration, f.duration(t.totalMs)),
                                    ShareCard.Stat(labelAvg, f.speed(t.avgMovingSpeed)), ShareCard.Stat(labelStops, t.stopCount.toString()),
                                ),
                                palette = palette, background = colors.background.toArgb(), surface = colors.surfaceContainerHigh.toArgb(),
                                onSurface = colors.onSurface.toArgb(), onSurfaceVariant = colors.onSurfaceVariant.toArgb(),
                            )
                        }
                        runCatching { ShareCard.share(context, bitmap, shareTitle) }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box {
                    OverlayButton(HejjeIcons.More, stringResource(R.string.action_more)) { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        @Composable
                        fun entry(text: Int, enabled: Boolean = true, action: () -> Unit) = DropdownMenuItem(
                            text = { Text(stringResource(text)) }, enabled = enabled, onClick = { menu = false; action() },
                        )
                        entry(R.string.detail_trim, state.chart.size > 2) { editPoint = 0; edit = EditMode.TRIM }
                        entry(R.string.detail_split, state.chart.size > 2) { editPoint = state.chart.size / 2; edit = EditMode.SPLIT }
                        state.previous?.let { p -> entry(R.string.detail_merge_previous) { vm.merge(p.id) { u -> haptics.confirm(); undo.show(if (u != null) msgMerged else msgFailed, u) } } }
                        state.next?.let { n -> entry(R.string.detail_merge_next) { vm.merge(n.id) { u -> haptics.confirm(); undo.show(if (u != null) msgMerged else msgFailed, u) } } }
                        entry(R.string.detail_export_gpx) {
                            val day = trip?.let { Insights.dayOf(it.startTime, ZoneId.systemDefault()) } ?: LocalDate.now()
                            gpx.launch("hejje-$day-${trip?.let { f.time(it.startTime).replace(":", "") }}.gpx")
                        }
                        entry(R.string.action_delete) { vm.delete { u -> haptics.confirm(); undo.show(if (u != null) msgDeleted else msgFailed, u) } }
                    }
                }
            }
        }
    }

    if (newTag) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newTag = false },
            title = { Text(stringResource(R.string.detail_new_tag)) },
            text = { OutlinedTextField(name, { name = it.take(24) }, singleLine = true, placeholder = { Text(stringResource(R.string.detail_tag_name)) }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.setTag(name, true); newTag = false }) {
                    Text(stringResource(R.string.action_add), color = palette.accentText)
                }
            },
            dismissButton = { TextButton(onClick = { newTag = false }) { Text(stringResource(R.string.action_cancel), color = colors.onSurfaceVariant) } },
        )
    }

    val mode = edit
    if (mode != null && state.chart.size > 2) {
        val last = (state.chart.size - 1).toFloat()
        var range by remember(mode) { mutableStateOf(0f..last) }
        var cut by remember(mode) { mutableFloatStateOf(last / 2) }
        val sliderColors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent)
        ModalBottomSheet(onDismissRequest = { edit = null }, containerColor = colors.surfaceContainerLow) {
            Column(Modifier.padding(horizontal = ScreenPadding).padding(bottom = 24.dp)) {
                Text(stringResource(if (mode == EditMode.TRIM) R.string.detail_trim else R.string.detail_split), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (mode == EditMode.TRIM) R.string.detail_trim_hint else R.string.detail_split_hint),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                if (mode == EditMode.TRIM) {
                    RangeSlider(
                        value = range, valueRange = 0f..last, colors = sliderColors,
                        onValueChange = {
                            editPoint = (if (it.start != range.start) it.start else it.endInclusive).roundToInt()
                            range = it
                        },
                    )
                    Text(
                        f.time(state.chart.times[range.start.roundToInt()]) + " – " + f.time(state.chart.times[range.endInclusive.roundToInt()]),
                        style = MaterialTheme.typography.titleMedium,
                    )
                } else {
                    Slider(value = cut, valueRange = 1f..(last - 1).coerceAtLeast(1f), colors = sliderColors, onValueChange = { cut = it; editPoint = it.roundToInt() })
                    Text(f.time(state.chart.times[cut.roundToInt()]), style = MaterialTheme.typography.titleMedium)
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { edit = null }) { Text(stringResource(R.string.action_cancel), color = colors.onSurfaceVariant) }
                    TextButton(onClick = {
                        val t = trip ?: return@TextButton
                        haptics.confirm()
                        if (mode == EditMode.TRIM) {
                            val from = if (range.start.roundToInt() == 0) t.startTime else state.chart.times[range.start.roundToInt()]
                            val to = if (range.endInclusive.roundToInt() >= state.chart.size - 1) t.endTime else state.chart.times[range.endInclusive.roundToInt()]
                            vm.trim(from, to) { u -> undo.show(if (u != null) msgTrimmed else msgFailed, u) }
                        } else {
                            vm.split(state.chart.times[cut.roundToInt()]) { u -> undo.show(if (u != null) msgSplit else msgFailed, u) }
                        }
                        edit = null
                    }) { Text(stringResource(R.string.action_apply), color = palette.accentText) }
                }
            }
        }
    }
}

@Composable
private fun OverlayButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f), shadowElevation = 1.dp) {
        IconButton(onClick = onClick) { Icon(icon, description, tint = MaterialTheme.colorScheme.onSurface) }
    }
}

@Composable
private fun StatsSections(s: TripStats, baro: Boolean, f: Formatter) {
    Column {
        SectionTitle(stringResource(R.string.detail_time))
        StatRow {
            StatTile(stringResource(R.string.stat_moving), f.duration(s.movingMs), Modifier.weight(1f))
            StatTile(stringResource(R.string.stat_idle), f.duration(s.idleMs), Modifier.weight(1f))
        }
        StatRow {
            StatTile(stringResource(R.string.stat_stopped), f.duration(s.stoppedMs), Modifier.weight(1f))
            StatTile(
                stringResource(R.string.stat_day_night),
                stringResource(R.string.stat_day_night_value, ((1 - s.nightShare) * 100).roundToInt(), (s.nightShare * 100).roundToInt()),
                Modifier.weight(1f),
            )
        }
        SectionTitle(stringResource(R.string.detail_speed_bands))
        BandBars(s.bandsMs.toList(), f)

        SectionTitle(stringResource(R.string.stat_stops))
        StatRow {
            StatTile(stringResource(R.string.stat_stop_count), pluralStringResource(R.plurals.stop_count, s.stops.size, s.stops.size), Modifier.weight(1f))
            StatTile(stringResource(R.string.stat_stop_time), f.duration(s.stopMs), Modifier.weight(1f))
        }
        s.stops.take(8).forEach { stop ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    f.time(stop.start) + "  ·  " + stringResource(if (stop.parked) R.string.stop_parked else R.string.stop_idle),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(f.duration(stop.durationMs), style = MaterialTheme.typography.bodyMedium)
            }
        }

        SectionTitle(stringResource(R.string.detail_style))
        StatRow {
            StatTile(stringResource(R.string.stat_smoothness), stringResource(R.string.stat_smoothness_value, countUp(s.smoothness.toFloat()).roundToInt()), Modifier.weight(1f))
            StatTile(stringResource(R.string.stat_hard_braking), s.hardBrakes.toString(), Modifier.weight(1f))
        }
        StatRow {
            StatTile(stringResource(R.string.stat_hard_accel), s.hardAccels.toString(), Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }

        SectionTitle(stringResource(R.string.detail_elevation))
        if (s.altMin.isNaN()) {
            Text(stringResource(R.string.detail_no_elevation), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            StatRow {
                StatTile(stringResource(R.string.stat_gain), f.height(s.elevGain), Modifier.weight(1f))
                StatTile(stringResource(R.string.stat_loss), f.height(s.elevLoss), Modifier.weight(1f))
            }
            StatRow {
                StatTile(stringResource(R.string.stat_alt_min), f.height(s.altMin), Modifier.weight(1f))
                StatTile(stringResource(R.string.stat_alt_max), f.height(s.altMax), Modifier.weight(1f))
            }
            Text(
                stringResource(if (baro) R.string.detail_elevation_baro else R.string.detail_elevation_gps),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
