package com.canis.hejje.ui.stretch

import android.view.Gravity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.canis.hejje.domain.Geo
import com.canis.hejje.ui.components.LocalContainer
import com.canis.hejje.ui.map.MapEngine
import com.canis.hejje.ui.map.MapRoute
import com.canis.hejje.ui.map.MapStyle
import com.canis.hejje.ui.theme.LocalPalette
import com.canis.hejje.ui.theme.Palette
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** How far (in screen distance) from a road a tap may land and still pick that road. */
private val TAP_REACH = 28.dp

/**
 * Map for measuring a stretch: every road driven (faint, darker where driven often), the chosen
 * stretch highlighted, and the start and end points. A tap reports its position and how many
 * metres the tap reach covers at the current zoom.
 */
@Composable
fun StretchMap(
    lines: List<MapRoute>,
    frame: List<MapRoute>,
    a: Pair<Double, Double>?,
    b: Pair<Double, Double>?,
    stretch: MapRoute?,
    onTap: (lat: Double, lon: Double, reachM: Double) -> Unit,
    modifier: Modifier = Modifier,
    description: String = "",
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val container = LocalContainer.current
    val density = LocalDensity.current
    val inspection = LocalInspectionMode.current
    val tap by rememberUpdatedState(onTap)
    val mapView = remember {
        if (inspection) null else try {
            MapEngine.init(context, container)
            val options = MapLibreMapOptions.createFromAttributes(context)
                .logoEnabled(false).compassEnabled(false).attributionGravity(Gravity.BOTTOM or Gravity.END)
                .attributionTintColor(if (palette.isDark) 0xFFA39E99.toInt() else 0xFF68625D.toInt())
                .foregroundLoadColor(MapStyle.backgroundColor(palette.isDark).toColorInt())
                .textureMode(true)
            MapView(context, options)
        } catch (t: Throwable) {
            null
        }
    }
    if (mapView == null) {
        StretchCanvas(lines, frame, a, b, stretch, onTap, modifier, description)
        return
    }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val reachPx = with(density) { TAP_REACH.toPx() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(mapView, lifecycle) {
        var started = false
        var resumed = false
        mapView.onCreate(null)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> { mapView.onStop(); started = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.setMaxZoomPreference(18.0)
            m.addOnMapClickListener { p ->
                val reach = m.projection.getMetersPerPixelAtLatitude(p.latitude) * reachPx
                tap(p.latitude, p.longitude, reach)
                true
            }
            map = m
        }
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(map, palette.isDark) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(Style.Builder().fromJson(MapStyle.json(palette.isDark))) { style = it }
    }

    // Frame the starting view once; later, frame the chosen stretch.
    var framed by remember { mutableStateOf(false) }
    LaunchedEffect(map, frame) {
        val m = map ?: return@LaunchedEffect
        if (framed || frame.isEmpty()) return@LaunchedEffect
        bounds(frame.flatMap { r -> (0 until r.size).map { LatLng(r.lat[it], r.lon[it]) } })?.let {
            runCatching { m.moveCamera(CameraUpdateFactory.newLatLngBounds(it, with(density) { 40.dp.roundToPx() })) }
        }
        framed = true
    }
    LaunchedEffect(map, stretch) {
        val m = map ?: return@LaunchedEffect
        val s = stretch ?: return@LaunchedEffect
        bounds((0 until s.size).map { LatLng(s.lat[it], s.lon[it]) })?.let {
            runCatching { m.animateCamera(CameraUpdateFactory.newLatLngBounds(it, with(density) { 56.dp.roundToPx() }), 600) }
        }
    }

    LaunchedEffect(style, lines) {
        val s = style ?: return@LaunchedEffect
        runCatching { Layers.lines(s, lines, palette) }
    }
    LaunchedEffect(style, a, b, stretch) {
        val s = style ?: return@LaunchedEffect
        runCatching { Layers.stretch(s, a, b, stretch, palette) }
    }

    AndroidView(factory = { mapView }, modifier = modifier.semantics { contentDescription = description })
}

private fun bounds(points: List<LatLng>): LatLngBounds? {
    if (points.size < 2 || points.all { it.latitude == points[0].latitude && it.longitude == points[0].longitude }) return null
    return LatLngBounds.Builder().includes(points).build()
}

private object Layers {
    private fun num(v: Double) = String.format(Locale.US, "%.6f", v)

    private fun line(r: MapRoute): String = buildString {
        append("""{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[""")
        for (i in 0 until r.size) {
            if (i > 0) append(',')
            append('[').append(num(r.lon[i])).append(',').append(num(r.lat[i])).append(']')
        }
        append("]}}")
    }

    private fun collection(features: List<String>) = features.joinToString(",", """{"type":"FeatureCollection","features":[""", "]}")

    private fun source(style: Style, id: String, json: String) {
        val existing = style.getSourceAs<GeoJsonSource>(id)
        if (existing != null) existing.setGeoJson(json) else style.addSource(GeoJsonSource(id, json, GeoJsonOptions().withTolerance(0.3f)))
    }

    private val round = arrayOf(PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND))

    fun lines(style: Style, lines: List<MapRoute>, palette: Palette) {
        source(style, "driven", collection(lines.map(::line)))
        if (style.getLayer("driven") == null) {
            style.addLayer(
                LineLayer("driven", "driven").withProperties(
                    PropertyFactory.lineColor(palette.accent.toArgb()), PropertyFactory.lineOpacity(0.32f),
                    PropertyFactory.lineWidth(3f), *round,
                ),
            )
        }
    }

    fun stretch(style: Style, a: Pair<Double, Double>?, b: Pair<Double, Double>?, stretch: MapRoute?, palette: Palette) {
        source(style, "stretch", collection(listOfNotNull(stretch?.takeIf { it.size > 1 }?.let(::line))))
        val points = listOfNotNull(a?.let { Triple(it.first, it.second, 0) }, b?.let { Triple(it.first, it.second, 1) })
        source(
            style, "ends",
            collection(points.map { (lat, lon, kind) -> """{"type":"Feature","properties":{"kind":$kind},"geometry":{"type":"Point","coordinates":[${num(lon)},${num(lat)}]}}""" }),
        )
        if (style.getLayer("stretch") == null) {
            val accent = palette.accent.toArgb()
            val casing = palette.routeCasing.toArgb()
            style.addLayer(LineLayer("stretch-casing", "stretch").withProperties(PropertyFactory.lineColor(casing), PropertyFactory.lineWidth(9f), *round))
            style.addLayer(LineLayer("stretch", "stretch").withProperties(PropertyFactory.lineColor(accent), PropertyFactory.lineWidth(5.5f), *round))
            fun end(id: String, kind: Int, fill: Int, stroke: Int) = style.addLayer(
                CircleLayer(id, "ends").withFilter(Expression.eq(Expression.get("kind"), Expression.literal(kind))).withProperties(
                    PropertyFactory.circleRadius(8f), PropertyFactory.circleColor(fill),
                    PropertyFactory.circleStrokeColor(stroke), PropertyFactory.circleStrokeWidth(3f),
                ),
            )
            end("end-a", 0, casing, accent)
            end("end-b", 1, accent, casing)
        }
    }
}

/** Plain drawing used when the map engine is unavailable; taps still work. */
@Composable
private fun StretchCanvas(
    lines: List<MapRoute>,
    frame: List<MapRoute>,
    a: Pair<Double, Double>?,
    b: Pair<Double, Double>?,
    stretch: MapRoute?,
    onTap: (Double, Double, Double) -> Unit,
    modifier: Modifier,
    description: String,
) {
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val tap by rememberUpdatedState(onTap)
    val view = (frame.ifEmpty { lines })
    val lats = view.flatMap { it.lat.asList() }
    val lons = view.flatMap { it.lon.asList() }
    if (lats.isEmpty()) {
        Canvas(modifier) { drawRect(palette.mapBackground) }
        return
    }
    val lat0 = (lats.min() + lats.max()) / 2
    val lon0 = (lons.min() + lons.max()) / 2
    val spanX = max(1.0, Geo.x(lons.max(), lon0, lat0) - Geo.x(lons.min(), lon0, lat0))
    val spanY = max(1.0, Geo.y(lats.max(), lat0) - Geo.y(lats.min(), lat0))
    val pad = with(density) { 40.dp.toPx() }
    val reachPx = with(density) { TAP_REACH.toPx() }
    Canvas(
        modifier.semantics { contentDescription = description }.pointerInput(lat0, lon0, spanX, spanY) {
            detectTapGestures { o ->
                val scale = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
                val lat = lat0 + (size.height / 2 - o.y) / scale / 110_540.0
                val lon = lon0 + (o.x - size.width / 2) / scale / (111_320.0 * Math.cos(Math.toRadians(lat0)))
                tap(lat, lon, reachPx / scale)
            }
        },
    ) {
        drawRect(palette.mapBackground)
        val scale = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY).toFloat()
        fun at(lat: Double, lon: Double) = Offset(
            size.width / 2 + (Geo.x(lon, lon0, lat0) * scale).toFloat(),
            size.height / 2 - (Geo.y(lat, lat0) * scale).toFloat(),
        )
        fun path(r: MapRoute) = Path().apply {
            for (i in 0 until r.size) at(r.lat[i], r.lon[i]).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) }
        }
        fun stroke(width: Float) = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (r in lines) drawPath(path(r), palette.accent.copy(alpha = 0.32f), style = stroke(3.dp.toPx()))
        stretch?.let { r ->
            val p = path(r)
            drawPath(p, palette.routeCasing, style = stroke(9.dp.toPx()))
            drawPath(p, palette.accent, style = stroke(5.5.dp.toPx()))
        }
        a?.let { val p = at(it.first, it.second); drawCircle(palette.accent, 9.5.dp.toPx(), p); drawCircle(palette.routeCasing, 6.5.dp.toPx(), p) }
        b?.let { val p = at(it.first, it.second); drawCircle(palette.routeCasing, 9.5.dp.toPx(), p); drawCircle(palette.accent, 6.5.dp.toPx(), p) }
    }
}
