package com.canis.hejje.ui.trips

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canis.hejje.R
import com.canis.hejje.domain.Formatter
import com.canis.hejje.domain.Geo
import com.canis.hejje.domain.Meal
import com.canis.hejje.domain.RawPoint
import com.canis.hejje.food.NextStop
import com.canis.hejje.recording.LiveState
import com.canis.hejje.recording.TraceState
import com.canis.hejje.ui.components.HejjeIcons
import com.canis.hejje.ui.components.PulsingDot
import com.canis.hejje.ui.theme.LocalPalette
import com.canis.hejje.ui.theme.LocalReduceMotion
import com.canis.hejje.ui.theme.WordmarkFont
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The speedometer's scale runs from 150° to 390° (clockwise from 3 o'clock), like a car's. */
private const val START = 150f
private const val SWEEP = 240f

/** Dial height as a multiple of the speedometer's radius (scale, digits and unit below the hub). */
private const val DIAL_HEIGHT = 1.76f
private val ODO_ROW = 52.dp
private val ROW_GAP = 8.dp

/**
 * The Trips card while a drive is recorded, as an instrument cluster: a speedometer coloured by
 * speed, a compass around the line driven so far, the trip odometer and the next saved stop ahead.
 */
@Composable
internal fun LiveCluster(live: LiveState, trace: TraceState, next: NextStop?, f: Formatter, onStop: () -> Unit) {
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    Column(
        Modifier.fillMaxWidth().height((windowHeight * 0.4f).coerceIn(320.dp, 460.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Header(live, f, onStop)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            // The speedometer sits against the left edge and the compass against the right one, both
            // centred on the same line, so the card's padding is the same on both sides.
            val r = minOf(maxWidth * 0.29f, (maxHeight - ODO_ROW - ROW_GAP) / DIAL_HEIGHT).coerceAtLeast(40.dp)
            val speedoCx = r * 1.035f
            val rc = minOf(r * 0.76f, (maxWidth - speedoCx * 2 - 14.dp) / 2)
            Column {
                val speed = live.last?.speed?.takeIf { live.hasFix && !it.isNaN() }
                Dials(speed, trace, live.last, f, r, rc, Modifier.fillMaxWidth().height(r * DIAL_HEIGHT))
                Spacer(Modifier.height(ROW_GAP))
                Box(Modifier.fillMaxWidth().height(ODO_ROW)) {
                    Box(Modifier.width(speedoCx * 2).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Box(Modifier.wrapContentWidth(unbounded = true)) {
                            Odometer(live.distanceM, f, rolling = true, label = stringResource(R.string.cluster_trip_description, f.distance(live.distanceM)))
                        }
                    }
                    Box(Modifier.align(Alignment.CenterEnd).width(rc * 2).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text(headingLabel(trace.heading), style = labelStyle.copy(fontSize = 12.sp), color = Dash.muted, maxLines = 1)
                    }
                }
            }
        }
        if (next != null) NextStopBar(next, live, f)
    }
}

private val labelStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp)

private fun headingLabel(heading: Float?): String {
    if (heading == null) return "—"
    val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return "${points[((heading + 22.5f) / 45f).toInt() % 8]} · ${heading.roundToInt() % 360}°"
}

/** Elapsed time as on a stopwatch: 18:42, or 1:05:12 from an hour. */
private fun elapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, s % 3600 / 60, s % 60)
    else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

@Composable
private fun Header(live: LiveState, f: Formatter, onStop: () -> Unit) {
    val dash = Dash
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            stringResource(R.string.brand_name),
            style = TextStyle(fontFamily = WordmarkFont, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = dash.text, shadow = dash.wordShadow),
            maxLines = 1,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.weight(1f))
        PulsingDot(size = 7.dp)
        Text(
            if (live.hasFix) elapsed(now - live.startedAt) else stringResource(R.string.notif_waiting_gps),
            style = chipText.copy(fontSize = 13.sp), color = dash.text, maxLines = 1,
            modifier = Modifier.padding(start = 7.dp, end = 10.dp),
        )
        Chip(onClick = onStop, description = stringResource(R.string.action_stop)) {
            Icon(HejjeIcons.Stop, null, Modifier.size(14.dp), tint = dash.text)
            Text(stringResource(R.string.action_stop), style = chipText, color = dash.text)
        }
    }
}

/** The compass follows the car's heading the short way round (350° to 10° turns 20°, not 340°). */
@Composable
private fun rememberTurn(target: Float?): Float {
    val anim = remember { Animatable(target ?: 0f) }
    val reduceMotion = LocalReduceMotion.current
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        val delta = ((target - anim.value) % 360f + 540f) % 360f - 180f
        if (reduceMotion) anim.snapTo(anim.value + delta) else anim.animateTo(anim.value + delta, tween(700, easing = FastOutSlowInEasing))
    }
    return anim.value
}

@Composable
private fun Dials(speedMs: Float?, trace: TraceState, here: RawPoint?, f: Formatter, r: Dp, rc: Dp, modifier: Modifier) {
    val dash = Dash
    val palette = LocalPalette.current
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val perMs = if (f.miles) 2.236936f else 3.6f
    val max = if (f.miles) 120 else 200
    val shown = ((speedMs ?: 0f) * perMs).coerceIn(0f, max.toFloat())
    val needle by animateFloatAsState(
        shown, if (LocalReduceMotion.current) snap() else tween(900, easing = LinearEasing), label = "speed",
    )
    val turn = rememberTurn(trace.heading)
    val unit = f.speedUnit.uppercase(LocalConfiguration.current.locales[0])
    val digits = if (speedMs == null) "–" else shown.roundToInt().toString()
    val description = stringResource(R.string.cluster_speed_description, if (speedMs == null) "–" else f.speed(speedMs), headingLabel(trace.heading))
    // The same colours as the routes on the map: 0 to 120 km/h along the ramp, faster stays at its end.
    val kmhPerUnit = if (f.miles) 1.609344f else 1f
    fun stop(kmh: Float) = ((START + SWEEP * (kmh / kmhPerUnit) / max) % 360f) / 360f
    val ramp = palette.speedRamp
    val stops = buildList {
        add(0f to ramp.last())
        add(stop(max * kmhPerUnit) + 0.01f to ramp.last())
        ramp.forEachIndexed { i, c -> add(stop(120f * i / (ramp.size - 1)) to c) }
        add(1f to ramp.last())
    }.toTypedArray()
    Canvas(modifier.semantics { contentDescription = description }) {
        val radius = r.toPx()
        val u = radius / 100f
        val stroke = 7 * u
        val c = Offset(radius * 1.035f, radius * 1.035f)
        speedometer(dash, c, radius, u, stroke, needle, max, Brush.sweepGradient(*stops, center = c), measurer, digits, unit)
        val cr = rc.toPx()
        compass(dash, Offset(size.width - cr, c.y), cr, turn, trace, here, measurer)
    }
}

private fun DrawScope.speedometer(
    dash: DashColors, c: Offset, radius: Float, u: Float, stroke: Float, value: Float, max: Int, colours: Brush, measurer: TextMeasurer, digits: String, unit: String,
) {
    val topLeft = Offset(c.x - radius, c.y - radius)
    val box = Size(radius * 2, radius * 2)
    drawArc(dash.track, START, SWEEP, false, topLeft, box, style = Stroke(stroke, cap = StrokeCap.Round))
    if (value > 0.5f) drawArc(colours, START, SWEEP * value / max, false, topLeft, box, style = Stroke(stroke, cap = StrokeCap.Round))
    fun at(angle: Float, dist: Float): Offset {
        val a = Math.toRadians(angle.toDouble())
        return Offset(c.x + dist * cos(a).toFloat(), c.y + dist * sin(a).toFloat())
    }
    val labelStyle = TextStyle(fontSize = (11 * u).toSp(), color = dash.muted, fontFeatureSettings = "tnum")
    for (v in 0..max step 10) {
        val angle = START + SWEEP * v / max
        val major = v % 20 == 0
        drawLine(
            if (major) dash.text.copy(alpha = 0.85f) else dash.muted.copy(alpha = 0.7f),
            at(angle, radius - 11 * u), at(angle, radius - (if (major) 21 else 16) * u),
            strokeWidth = (if (major) 2.2f else 1.3f) * u, cap = StrokeCap.Round,
        )
        if (major) {
            val text = measurer.measure(v.toString(), labelStyle)
            val p = at(angle, radius - 33 * u)
            drawText(text, topLeft = Offset(p.x - text.size.width / 2f, p.y - text.size.height / 2f))
        }
    }
    val angle = START + SWEEP * value / max
    val tip = at(angle, radius - 12 * u)
    val tail = at(angle + 180f, 14 * u)
    drawLine(dash.accent.copy(alpha = 0.25f), tail, tip, strokeWidth = 9 * u, cap = StrokeCap.Round)
    drawLine(dash.accent, tail, tip, strokeWidth = 3.5f * u, cap = StrokeCap.Round)
    drawCircle(dash.panelBottom, 9 * u, c)
    drawCircle(dash.accent, 9 * u, c, style = Stroke(2.5f * u))
    val big = measurer.measure(digits, TextStyle(fontSize = (36 * u).toSp(), fontWeight = FontWeight.SemiBold, color = dash.text, fontFeatureSettings = "tnum"))
    drawText(big, topLeft = Offset(c.x - big.size.width / 2f, c.y + 50 * u - big.firstBaseline))
    val small = measurer.measure(unit, TextStyle(fontSize = (11 * u).toSp(), letterSpacing = (1.5f * u).toSp(), color = dash.muted))
    drawText(small, topLeft = Offset(c.x - small.size.width / 2f, c.y + 67 * u - small.firstBaseline))
}

/**
 * A compass ring that turns with the car (heading up), around the line driven so far. The car is
 * the arrow in the middle; the view covers the last few hundred metres up to 2.5 km.
 */
private fun DrawScope.compass(dash: DashColors, c: Offset, radius: Float, turn: Float, trace: TraceState, here: RawPoint?, measurer: TextMeasurer) {
    val v = radius / 74f
    val face = radius - 16 * v
    drawCircle(dash.dial, face, c)
    val lat0 = here?.lat ?: trace.lat.lastOrNull()
    val lon0 = here?.lon ?: trace.lon.lastOrNull()
    if (lat0 != null && lon0 != null && trace.size > 0) {
        val h = Math.toRadians(turn.toDouble())
        val sinH = sin(h)
        val cosH = cos(h)
        var far = 0.0
        val xs = DoubleArray(trace.size + 1)
        val ys = DoubleArray(trace.size + 1)
        for (i in 0..trace.size) {
            val lat = if (i < trace.size) trace.lat[i] else lat0
            val lon = if (i < trace.size) trace.lon[i] else lon0
            val dx = Geo.x(lon, lon0, lat0)
            val dy = Geo.y(lat, lat0)
            xs[i] = dx * cosH - dy * sinH
            ys[i] = dx * sinH + dy * cosH
            far = maxOf(far, kotlin.math.hypot(dx, dy))
        }
        val scale = (face * 0.9f / far.coerceIn(250.0, 2_500.0)).toFloat()
        val path = Path()
        for (i in xs.indices) {
            val x = c.x + xs[i].toFloat() * scale
            val y = c.y - ys[i].toFloat() * scale
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        clipPath(Path().apply { addOval(Rect(c, face)) }) {
            drawPath(path, dash.accent, style = Stroke(4 * v, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(dash.accent, 4.5f * v, Offset(c.x + xs[0].toFloat() * scale, c.y - ys[0].toFloat() * scale))
        }
    }
    drawCircle(dash.track, face, c, style = Stroke(1.5f * v))
    fun at(deg: Float, dist: Float): Offset {
        val a = Math.toRadians((deg - turn - 90f).toDouble())
        return Offset(c.x + dist * cos(a).toFloat(), c.y + dist * sin(a).toFloat())
    }
    for (d in 0 until 360 step 15) {
        if (d % 90 == 0) continue
        drawLine(dash.muted, at(d.toFloat(), radius - 4 * v), at(d.toFloat(), radius - (if (d % 45 == 0) 10 else 8) * v), strokeWidth = 1.3f * v, cap = StrokeCap.Round)
    }
    listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f).forEach { (letter, deg) ->
        val text = measurer.measure(letter, TextStyle(fontSize = (12 * v).toSp(), fontWeight = FontWeight.Bold, color = if (letter == "N") dash.accent else dash.muted))
        val p = at(deg, radius - 8 * v)
        drawText(text, topLeft = Offset(p.x - text.size.width / 2f, p.y - text.size.height / 2f))
    }
    // Which way you are heading: the mark at the top of the ring, and the car in the middle.
    drawPath(Path().apply {
        moveTo(c.x, c.y - radius - 3 * v); lineTo(c.x + 5 * v, c.y - radius + 4 * v); lineTo(c.x - 5 * v, c.y - radius + 4 * v); close()
    }, dash.text)
    if (trace.heading != null) {
        val car = Path().apply {
            moveTo(c.x, c.y - 9 * v); lineTo(c.x + 7 * v, c.y + 7 * v); lineTo(c.x, c.y + 3 * v); lineTo(c.x - 7 * v, c.y + 7 * v); close()
        }
        drawPath(car, dash.accent)
        drawPath(car, dash.panelBottom, style = Stroke(1.5f * v, join = StrokeJoin.Round))
    } else {
        drawCircle(dash.accent, 5 * v, c)
    }
}

/** "NEXT STOP" over the place's name and how far along the road it is. */
@Composable
private fun NextStopBar(next: NextStop, live: LiveState, f: Formatter) {
    val dash = Dash
    val left = (next.aheadM - (live.distanceM - next.atDriveM)).coerceAtLeast(0.0)
    val icon = when (Meal.of(next.purpose)) {
        Meal.FOOD -> HejjeIcons.Food
        Meal.COFFEE -> HejjeIcons.Coffee
        null -> HejjeIcons.place("pin")
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(dash.window)
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 9.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(stringResource(R.string.cluster_next_stop).uppercase(LocalConfiguration.current.locales[0]), style = labelStyle, color = dash.muted, maxLines = 1)
        Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = dash.accent)
            Text(
                next.name, Modifier.weight(1f).padding(start = 8.dp, end = 12.dp),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = dash.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(f.distance(left), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), color = dash.accent, maxLines = 1)
        }
    }
}
