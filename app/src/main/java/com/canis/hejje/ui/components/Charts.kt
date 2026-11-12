package com.canis.hejje.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.canis.hejje.domain.Formatter
import com.canis.hejje.domain.Insights
import com.canis.hejje.ui.theme.LocalPalette
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Speed over time as a soft area chart in accent shades. Tap or drag to pick a moment; the
 * caller highlights that point on the route.
 * @param speeds average speed per sample, m/s
 * @param selected index of the picked sample, or -1
 */
@Composable
fun SpeedChart(
    speeds: FloatArray,
    selected: Int,
    onSelect: (Int) -> Unit,
    formatter: Formatter,
    description: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val unitFactor = if (formatter.miles) 2.236936f else 3.6f
    val peak = (speeds.maxOrNull() ?: 0f) * unitFactor
    val top = max(20f, ceil(peak / 20f) * 20f)
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${top.roundToInt()} ${formatter.speedUnit}", style = MaterialTheme.typography.labelMedium, color = labelColor)
            if (selected in speeds.indices) {
                Text(formatter.speed(speeds[selected]), style = MaterialTheme.typography.labelMedium, color = palette.accentText)
            }
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier.fillMaxWidth().height(132.dp)
                .semantics { contentDescription = description }
                .pointerInput(speeds.size) {
                    detectTapGestures { o -> if (speeds.isNotEmpty()) onSelect(((o.x / size.width) * (speeds.size - 1)).roundToInt().coerceIn(0, speeds.size - 1)) }
                }
                .pointerInput(speeds.size) {
                    detectHorizontalDragGestures { change, _ ->
                        if (speeds.isNotEmpty()) onSelect(((change.position.x / size.width) * (speeds.size - 1)).roundToInt().coerceIn(0, speeds.size - 1))
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            for (i in 0..2) drawLine(grid, Offset(0f, h * i / 2f), Offset(w, h * i / 2f), 1.dp.toPx())
            if (speeds.size < 2) return@Canvas
            fun x(i: Int) = w * i / (speeds.size - 1)
            fun y(i: Int) = h - (speeds[i] * unitFactor / top).coerceIn(0f, 1f) * h
            val line = Path().apply {
                moveTo(x(0), y(0))
                for (i in 1 until speeds.size) lineTo(x(i), y(i))
            }
            val area = Path().apply {
                addPath(line)
                lineTo(w, h); lineTo(0f, h); close()
            }
            drawPath(area, Brush.verticalGradient(listOf(palette.accent.copy(alpha = 0.32f), palette.accent.copy(alpha = 0.03f))))
            drawPath(line, palette.accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (selected in speeds.indices) {
                val p = Offset(x(selected), y(selected))
                drawLine(palette.accent.copy(alpha = 0.5f), Offset(p.x, 0f), Offset(p.x, h), 1.dp.toPx())
                drawCircle(palette.routeCasing, 6.dp.toPx(), p)
                drawCircle(palette.accent, 4.dp.toPx(), p)
            }
        }
    }
}

/** Time spent in each speed band, as rounded bars in accent shades. */
@Composable
fun BandBars(bandsMs: List<Long>, formatter: Formatter, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val total = bandsMs.sum().coerceAtLeast(1)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        bandsMs.forEachIndexed { i, ms ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatter.band(i)} ${formatter.speedUnit}", Modifier.width(112.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
                val share = ms.toFloat() / total
                val color = palette.accentShade(0.25f + 0.75f * i / (bandsMs.size - 1).coerceAtLeast(1))
                Box(
                    Modifier.weight(1f).height(10.dp).drawBehind {
                        val r = CornerRadius(size.height / 2)
                        drawRoundRect(track, cornerRadius = r)
                        if (share > 0f) drawRoundRect(color, size = Size(max(size.height, size.width * share), size.height), cornerRadius = r)
                    },
                )
                Text(
                    formatter.duration(ms), Modifier.width(76.dp).padding(start = 10.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                )
            }
        }
    }
}

/**
 * GitHub-style calendar: one column per week (Monday on top), one rounded cell per day, shaded by
 * the distance driven. Opens scrolled to the current week; tap a day for its trips.
 */
@Composable
fun Heatmap(
    daily: Map<LocalDate, Double>,
    today: LocalDate,
    selected: LocalDate?,
    onDay: (LocalDate) -> Unit,
    dayDescription: (LocalDate, Double) -> String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val empty = MaterialTheme.colorScheme.surfaceContainerHigh
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lastWeek = Insights.weekStart(today)
    val first = daily.keys.minOrNull()?.let(Insights::weekStart) ?: lastWeek
    val weeks = max(26, ChronoUnit.WEEKS.between(first, lastWeek).toInt() + 1)
    val start = lastWeek.minusWeeks((weeks - 1).toLong())
    val peak = remember(daily) { daily.values.maxOrNull() ?: 0.0 }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = weeks - 1)
    val cell = 18.dp
    val locale = LocalConfiguration.current.locales[0]
    val gap = 4.dp
    LazyRow(modifier, state = state, horizontalArrangement = Arrangement.spacedBy(gap), contentPadding = PaddingValues(horizontal = ScreenPadding)) {
        items(weeks) { w ->
            val monday = start.plusWeeks(w.toLong())
            Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.Start) {
                // Month name above the first week that contains the 1st of a month.
                val monthStart = (0..6).map { monday.plusDays(it.toLong()) }.firstOrNull { it.dayOfMonth == 1 }
                Box(Modifier.height(16.dp).width(cell)) {
                    if (monthStart != null || w == 0) {
                        Text(
                            (monthStart ?: monday).month.getDisplayName(TextStyle.SHORT, locale),
                            style = MaterialTheme.typography.labelMedium, color = labelColor, maxLines = 1, softWrap = false,
                            modifier = Modifier.width(40.dp),
                        )
                    }
                }
                for (d in 0..6) {
                    val day = monday.plusDays(d.toLong())
                    val distance = daily[day] ?: 0.0
                    val color = when {
                        day > today -> Color.Transparent
                        distance <= 0.0 -> empty
                        else -> palette.accentShade(sqrt(distance / peak.coerceAtLeast(1.0)).toFloat())
                    }
                    val ring = if (day == selected) palette.accentText else Color.Transparent
                    val description = dayDescription(day, distance)
                    Box(
                        Modifier.size(cell).clip(RoundedCornerShape(5.dp))
                            .drawBehind {
                                drawRect(color)
                                drawRoundRect(ring, style = Stroke(2.dp.toPx()), cornerRadius = CornerRadius(5.dp.toPx()))
                            }
                            .then(if (day <= today) Modifier.clickable { onDay(day) }.semantics { contentDescription = description } else Modifier),
                    )
                }
            }
        }
    }
}
