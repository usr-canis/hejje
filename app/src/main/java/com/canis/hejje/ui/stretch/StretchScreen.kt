package com.canis.hejje.ui.stretch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.canis.hejje.R
import com.canis.hejje.domain.Formatter
import com.canis.hejje.domain.Insights
import com.canis.hejje.domain.StretchPass
import com.canis.hejje.domain.StretchSummary
import com.canis.hejje.ui.components.HejjeCard
import com.canis.hejje.ui.components.HejjeTopBar
import com.canis.hejje.ui.components.LocalContainer
import com.canis.hejje.ui.components.ScreenPadding
import com.canis.hejje.ui.components.SectionTitle
import com.canis.hejje.ui.components.StatRow
import com.canis.hejje.ui.components.StatTile
import com.canis.hejje.ui.components.rememberHaptics
import com.canis.hejje.ui.hejjeViewModel
import com.canis.hejje.ui.map.MapRoute
import com.canis.hejje.ui.rememberFormatter
import com.canis.hejje.ui.theme.LocalPalette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Pick two points on roads you drove and compare every drive between them. */
@Composable
fun StretchScreen(fromTrip: Long?, onBack: () -> Unit, onTrip: (Long) -> Unit) {
    val vm = hejjeViewModel(key = "stretch-$fromTrip") { StretchViewModel(it, fromTrip) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by LocalContainer.current.settings.data.collectAsStateWithLifecycle()
    val f = rememberFormatter(settings.miles)
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val mapHeight = (windowHeight * 0.42f).coerceIn(240.dp, 440.dp)
    val summary = state.summary
    val shown = summary?.let { s -> s.passes.firstOrNull { it.tripId == state.fromTrip } ?: s.passes.first() }
    val stretchLine = remember(shown) { shown?.let { MapRoute(it.lat, it.lon, FloatArray(it.lat.size)) } }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        HejjeTopBar(stringResource(R.string.stretch_title), onBack = onBack)
        Box(Modifier.fillMaxWidth().height(mapHeight).clipToBounds()) {
            StretchMap(
                lines = state.lines, frame = state.frame, a = state.a, b = state.b, stretch = stretchLine,
                onTap = { lat, lon, reach -> haptics.tick(); vm.tap(lat, lon, reach) },
                modifier = Modifier.fillMaxSize(), description = stringResource(R.string.stretch_map_description),
            )
            if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        Guide(state, onSwap = { haptics.tick(); vm.swap() }, onClear = { haptics.tick(); vm.clear() })
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 32.dp)) {
            when {
                state.a == null || state.b == null -> item(key = "intro") {
                    Text(
                        stringResource(R.string.stretch_intro), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 20.dp),
                    )
                }
                state.searching -> item(key = "searching") {
                    Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                summary == null -> item(key = "none") {
                    Column(Modifier.padding(top = 20.dp)) {
                        Text(stringResource(R.string.stretch_none), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { haptics.tick(); vm.swap() }, contentPadding = PaddingValues(0.dp)) {
                            Text(stringResource(R.string.stretch_swap))
                        }
                    }
                }
                else -> {
                    item(key = "summary") { SummaryCard(summary, f) }
                    if (summary.passes.size >= 3) {
                        item(key = "chart") {
                            SectionTitle(stringResource(R.string.stretch_by_time))
                            TimeOfDayChart(summary.passes, state.fromTrip, f)
                        }
                    }
                    item(key = "each") { SectionTitle(stringResource(R.string.stretch_each)) }
                    items(summary.passes, key = { "${it.tripId}-${it.start}" }) { p ->
                        PassRow(p, f, thisTrip = p.tripId == state.fromTrip, summary) { onTrip(p.tripId) }
                    }
                }
            }
        }
    }
}

/** One line under the map: what to do next, and swap / start over once both points are set. */
@Composable
private fun Guide(state: StretchState, onSwap: () -> Unit, onClear: () -> Unit) {
    val text = when {
        state.message != null -> stringResource(state.message)
        state.loading -> stringResource(R.string.stretch_loading)
        state.a == null -> stringResource(R.string.stretch_pick_start)
        state.b == null -> stringResource(R.string.stretch_pick_end)
        else -> stringResource(R.string.stretch_tap_again)
    }
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(start = ScreenPadding, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, modifier = Modifier.weight(1f),
            color = if (state.message != null) LocalPalette.current.accentText else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.a != null && state.b != null) TextButton(onClick = onSwap) { Text(stringResource(R.string.stretch_swap_short)) }
        if (state.a != null) TextButton(onClick = onClear) { Text(stringResource(R.string.stretch_clear)) }
    }
}

@Composable
private fun SummaryCard(s: StretchSummary, f: Formatter) {
    HejjeCard(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text(
            pluralStringResource(R.plurals.stretch_drives, s.passes.size, s.passes.size) + " · " + f.distance(s.distanceM),
            style = MaterialTheme.typography.titleMedium,
        )
        StatRow {
            StatTile(stringResource(R.string.stretch_typical), f.duration(s.typicalMs), Modifier.weight(1f))
            StatTile(stringResource(R.string.stretch_fastest), f.duration(s.fastest.durationMs), Modifier.weight(1f))
            StatTile(stringResource(R.string.stretch_slowest), f.duration(s.slowest.durationMs), Modifier.weight(1f))
        }
        if (s.bestHour != null && s.worstHour != null) {
            Text(
                stringResource(R.string.stretch_hours, hour(s.bestHour.first), f.duration(s.bestHour.second), hour(s.worstHour.first), f.duration(s.worstHour.second)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun hour(h: Int) = String.format(Locale.ROOT, "%02d:00", h)

@Composable
private fun PassRow(p: StretchPass, f: Formatter, thisTrip: Boolean, s: StretchSummary, onClick: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val day = f.dayLabel(Insights.dayOf(p.start, zone), LocalDate.now(zone))
    val details = buildList {
        add(stringResource(R.string.stretch_avg, f.speed(p.avgSpeed)))
        if (p.stoppedMs >= 30_000) add(stringResource(R.string.stretch_waited, f.duration(p.stoppedMs)))
        if (p.hardBrakes > 0) add(pluralStringResource(R.plurals.hard_brake_count, p.hardBrakes, p.hardBrakes))
    }.joinToString(" · ")
    val mark = when {
        thisTrip -> stringResource(R.string.stretch_this_trip)
        p === s.fastest -> stringResource(R.string.stretch_fastest)
        else -> null
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$day, ${f.time(p.start)}", style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(f.duration(p.durationMs), style = MaterialTheme.typography.titleMedium)
            if (mark != null) Text(mark, style = MaterialTheme.typography.labelMedium, color = LocalPalette.current.accentText)
        }
    }
}

/** Each drive as a dot: when it passed (across the day) against how long it took. */
@Composable
private fun TimeOfDayChart(passes: List<StretchPass>, fromTrip: Long?, f: Formatter) {
    val palette = LocalPalette.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val zone = ZoneId.systemDefault()
    val maxMs = passes.maxOf { it.durationMs } * 1.15
    val description = stringResource(R.string.stretch_chart_description)
    Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = description }) {
        val bottom = size.height - 18.dp.toPx()
        val left = 0f
        val width = size.width
        for (h in 0..24 step 6) {
            val x = left + width * h / 24f
            drawLine(grid, Offset(x, 0f), Offset(x, bottom), 1f)
            val label = measurer.measure(hour(h % 24).take(2), labelStyle)
            val lx = (x - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
            drawText(label, topLeft = Offset(lx, bottom + 4.dp.toPx()))
        }
        drawLine(grid, Offset(0f, bottom), Offset(size.width, bottom), 1f)
        val top = measurer.measure(f.duration(maxMs.toLong()), labelStyle)
        drawText(top, topLeft = Offset(4.dp.toPx(), 0f))
        for (p in passes.sortedBy { it.tripId == fromTrip }) {
            val t = Instant.ofEpochMilli(p.start).atZone(zone)
            val x = left + width * ((t.hour * 60 + t.minute) / 1440f)
            val y = bottom - (bottom - 16.dp.toPx()) * (p.durationMs / maxMs).toFloat()
            if (p.tripId == fromTrip) {
                drawCircle(palette.accent, 6.dp.toPx(), Offset(x, y))
                drawCircle(palette.routeCasing, 6.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
            } else {
                drawCircle(palette.accent.copy(alpha = 0.55f), 4.5.dp.toPx(), Offset(x, y))
            }
        }
    }
}
