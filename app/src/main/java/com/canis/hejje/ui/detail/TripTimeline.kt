package com.canis.hejje.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canis.hejje.R
import com.canis.hejje.data.PlaceRepository
import com.canis.hejje.domain.Formatter
import com.canis.hejje.domain.Meal
import com.canis.hejje.domain.NamedPlace
import com.canis.hejje.domain.Places
import com.canis.hejje.domain.TimelineEntry
import com.canis.hejje.domain.TimelineEntry.Kind
import com.canis.hejje.ui.LocalPlaces
import com.canis.hejje.ui.map.Flags
import com.canis.hejje.ui.map.rememberFlags
import com.canis.hejje.ui.theme.LocalPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Looks up place names for timeline points, one at a time (cached on the phone after the first time). */
class TimelineNames(private val places: PlaceRepository, private val scope: CoroutineScope) {
    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())
    val names: StateFlow<Map<String, String>> = _names
    private var job: Job? = null

    fun lookUp(points: List<TimelineEntry.Point>) {
        job?.cancel()
        job = scope.launch {
            for (p in points) {
                val key = PlaceRepository.keyOf(p.lat, p.lon)
                if (key in _names.value) continue
                val name = runCatching { places.name(p.lat, p.lon) }.getOrNull() ?: continue
                _names.update { it + (key to name) }
            }
        }
    }
}

/** The points of [entries] that need a looked-up name (not one of your places, not already named). */
@Composable
internal fun timelineLookups(entries: List<TimelineEntry>, startName: String?, endName: String?): List<TimelineEntry.Point> {
    val places = LocalPlaces.current
    return remember(entries, places, startName, endName) {
        entries.filterIsInstance<TimelineEntry.Point>().filter { p ->
            Places.placeAt(p.lat, p.lon, places) == null &&
                !(p.kind == Kind.START && startName != null) && !(p.kind == Kind.END && endName != null)
        }
    }
}

/**
 * The trip told in order: start, each leg with its numbers, every parked stop (the phone
 * disconnected from the car) with where and how long, and the finish. Tapping a stop shows it on
 * the map; tapping a leg fits that leg on the map.
 */
@Composable
internal fun TripTimeline(
    entries: List<TimelineEntry>,
    names: Map<String, String>,
    startName: String?,
    endName: String?,
    f: Formatter,
    /** Name a parked stop that is not one of your places yet (food or coffee). */
    onName: ((TimelineEntry.Point) -> Unit)? = null,
    /** Rate (or re-rate) a food or coffee place. */
    onRate: ((NamedPlace) -> Unit)? = null,
    onFocus: (points: List<Pair<Double, Double>>, spot: Pair<Double, Double>?) -> Unit,
) {
    val places = LocalPlaces.current
    Column(Modifier.fillMaxWidth()) {
        entries.forEachIndexed { i, e ->
            when (e) {
                is TimelineEntry.Point -> {
                    val here = Places.placeAt(e.lat, e.lon, places)
                    // One small link on parked stops: name it, or rate a food/coffee place.
                    val link: Pair<String, () -> Unit>? = when {
                        e.kind != Kind.PARKED -> null
                        here == null && onName != null -> stringResource(R.string.tl_name) to { onName(e) }
                        here != null && here.isFoodOrCoffee && onRate != null ->
                            (when (here.rating) { 1 -> "👍"; -1 -> "👎"; else -> stringResource(R.string.tl_rate) }) to { onRate(here) }
                        else -> null
                    }
                    val name = here?.name
                        ?: (if (e.kind == Kind.START) startName else null)
                        ?: (if (e.kind == Kind.END) endName else null)
                        ?: names[PlaceRepository.keyOf(e.lat, e.lon)]
                    PointRow(e, name, first = i == 0, last = i == entries.size - 1, f, link) { onFocus(listOf(e.lat to e.lon), e.lat to e.lon) }
                }
                is TimelineEntry.Leg -> LegRow(e, f) {
                    val step = (e.lat.size / 60).coerceAtLeast(1)
                    onFocus(e.lat.indices.step(step).map { e.lat[it] to e.lon[it] } + (e.lat.last() to e.lon.last()), null)
                }
            }
        }
    }
}

private val TIME_WIDTH = 50.dp
private val RAIL_WIDTH = 34.dp

@Composable
private fun PointRow(
    p: TimelineEntry.Point, name: String?, first: Boolean, last: Boolean, f: Formatter, link: Pair<String, () -> Unit>?, onClick: () -> Unit,
) {
    val label = stringResource(
        when (p.kind) { Kind.START -> R.string.tl_start; Kind.PARKED -> R.string.stop_parked; Kind.END -> R.string.tl_finish },
    )
    val duration = if (p.kind == Kind.PARKED) f.duration(p.durationMs) else null
    val title = name ?: label
    val subtitle = when {
        name == null -> duration
        duration != null -> "$label · $duration"
        else -> label
    }
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick).semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            f.time(p.time), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium),
            modifier = Modifier.width(TIME_WIDTH),
        )
        Box(Modifier.width(RAIL_WIDTH).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Rail(top = !first, bottom = !last)
            Marker(p.kind)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp, top = 10.dp, bottom = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), maxLines = 2)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (link != null) {
            TextButton(onClick = link.second) { Text(link.first, color = LocalPalette.current.accentText) }
        }
    }
}

@Composable
private fun LegRow(leg: TimelineEntry.Leg, f: Formatter, onClick: () -> Unit) {
    val main = listOf(
        f.distance(leg.distanceM), f.duration(leg.end - leg.start),
        stringResource(R.string.tl_avg, f.speed(leg.avgSpeed)), stringResource(R.string.tl_top, f.speed(leg.maxSpeed)),
    ).joinToString(" · ")
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick).semantics(mergeDescendants = true) { role = Role.Button }) {
        Box(Modifier.width(TIME_WIDTH))
        Box(Modifier.width(RAIL_WIDTH).fillMaxHeight()) { Rail(top = true, bottom = true, driving = true) }
        Column(Modifier.weight(1f).padding(start = 10.dp, top = 8.dp, bottom = 8.dp)) {
            Text(main, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!leg.circuity.isNaN()) {
                Text(
                    stringResource(R.string.tl_circuity, String.format(LocalConfiguration.current.locales[0], "%.2f", leg.circuity)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The vertical line; solid accent where the car was driving. */
@Composable
private fun Rail(top: Boolean, bottom: Boolean, driving: Boolean = false) {
    val color = if (driving) LocalPalette.current.accent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
        val x = size.width / 2
        val w = 3.dp.toPx()
        if (top) drawLine(color, Offset(x, 0f), Offset(x, size.height / 2), w)
        if (bottom) drawLine(color, Offset(x, size.height / 2), Offset(x, size.height), w)
    }
}

@Composable
private fun Marker(kind: Kind) {
    val palette = LocalPalette.current
    when (kind) {
        Kind.START, Kind.END -> {
            val flags = rememberFlags(palette)
            val image = remember(flags, kind) { (if (kind == Kind.START) flags.first else flags.second).asImageBitmap() }
            Canvas(Modifier.size(24.dp, 28.dp)) {
                // The flag's pole stands on the rail.
                val scale = size.height / image.height
                val w = image.width * scale
                val base = Flags.BASE_DP * density * scale
                drawImage(
                    image, dstOffset = IntOffset((size.width / 2 - base).toInt(), 0),
                    dstSize = IntSize(w.toInt(), size.height.toInt()),
                )
            }
        }
        Kind.PARKED -> Box(
            Modifier.size(22.dp).background(palette.accent, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("P", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold), color = Color.White) }
    }
}

/** Name a parked stop later: food or coffee, and the place's name. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FoodNameSheet(onDismiss: () -> Unit, onSave: (Meal, String) -> Unit) {
    var meal by remember { mutableStateOf(Meal.FOOD) }
    var name by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.food_sheet_title), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Meal.FOOD to R.string.food_action_food, Meal.COFFEE to R.string.food_action_coffee).forEach { (m, label) ->
                    FilterChip(
                        selected = meal == m, onClick = { meal = m }, label = { Text(stringResource(label)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.primaryContainer, selectedLabelColor = colors.onPrimaryContainer),
                    )
                }
            }
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                placeholder = { Text(stringResource(R.string.food_name_hint)) },
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = colors.onSurfaceVariant) }
                TextButton(onClick = { onSave(meal, name) }) { Text(stringResource(R.string.action_save), color = LocalPalette.current.accentText) }
            }
        }
    }
}

/** Rate a food or coffee place: suggest again, or not again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FoodRateSheet(name: String, onDismiss: () -> Unit, onRate: (Boolean) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.food_feedback, name), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onRate(true) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.food_good)) }
                OutlinedButton(onClick = { onRate(false) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.food_not_again)) }
            }
        }
    }
}
